package dev.clouddisc.audio;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 解码线程：{@link PcmSource} → 单声道下混 → 线性重采样到 48kHz → 字节环形缓冲。
 *
 * <p>把解码与音频线程彻底解耦：MC 的音频回调只从环形缓冲取数据，永远不会因为
 * 网络/解码卡顿而被阻塞；反过来环形缓冲满时解码线程自动阻塞，形成背压。
 */
public final class AudioPipeline implements AutoCloseable {
	/** 输出统一为 48kHz 单声道 16bit —— OpenAL 最稳的组合，且单声道才能被正确空间化。 */
	public static final int OUT_RATE = 48000;
	/** 每毫秒字节数：48000 * 2 / 1000。 */
	public static final int BYTES_PER_MS = OUT_RATE * 2 / 1000;
	/** 环形缓冲容量：4 秒。 */
	public static final int RING_BYTES = BYTES_PER_MS * 4000;

	private final PcmSource source;
	private final PcmRingBuffer ring;
	private final int inRate;
	private final int inChannels;
	private final float outputGain;
	private final Thread worker;
	private final AtomicLong writtenBytes = new AtomicLong();
	private volatile boolean running = true;
	/** 已输出音频的峰值（用于诊断"是不是被削顶了"）。 */
	private volatile float peak;
	private volatile boolean peakLogged;
	/** 期望位置（毫秒）。worker 每轮比对 appliedSeekMs，不等就执行一次 seek。 */
	private volatile long requestedSeekMs = 0L;
	private volatile long appliedSeekMs = Long.MIN_VALUE;

	public AudioPipeline(PcmSource source) {
		this(source, 1.0f);
	}

	/**
	 * @param outputGain 输出余量：先乘这个系数再限幅，用来避免"满刻度商业母带 × 大增益"削顶。
	 *                   见 {@code CloudDiscConfig#outputGain} 的说明。
	 */
	public AudioPipeline(PcmSource source, float outputGain) {
		this.source = source;
		this.ring = new PcmRingBuffer(RING_BYTES);
		this.inRate = Math.max(1, source.sampleRate());
		this.inChannels = Math.max(1, source.channels());
		this.outputGain = Math.max(0.05f, Math.min(2.0f, outputGain));
		this.worker = new Thread(this::loop, "CloudDisc-Decoder");
		this.worker.setDaemon(true);
		this.worker.start();
	}

	/** 已输出音频的峰值（1.0 = 满刻度；接近或等于 1.0 说明正在削顶）。 */
	public float peak() {
		return peak;
	}

	public PcmRingBuffer ring() {
		return ring;
	}

	public PcmSource source() {
		return source;
	}

	/** 已交给 MC（OpenAL）的音频时长估计，用于漂移检测。 */
	public long playedMs() {
		return writtenBytes.get() / BYTES_PER_MS - ring.available() / BYTES_PER_MS;
	}

	public long durationMs() {
		return source.durationMillis();
	}

	/**
	 * 请求一次定位（纠偏/中途加入用），单位毫秒。
	 *
	 * <p><b>必须先清空环形缓冲</b>：解码线程灌满缓冲后会阻塞在 {@code ring.write()} 里等空间，
	 * 而空间要靠 MC 消费才有、MC 又要等我们 {@code play()} —— 不清空的话解码线程永远回不到
	 * 循环顶部去执行 seek，请求就会超时（实测整整 10 秒），然后播出的是缓冲区里那份"开头"
	 * 的数据，表现为"中途加入的人差了半分钟"。清空 `available` 会立刻放开那次写入。
	 */
	public void requestSeek(long ms) {
		this.ring.clear();
		this.requestedSeekMs = Math.max(0L, ms);
	}

	/**
	 * 请求定位并等到 worker 真正执行完。
	 *
	 * @return 是否成功生效（超时返回 false —— 调用方应当把这件事报出来，而不是假装对齐了）
	 */
	public boolean requestSeekBlocking(long ms, long timeoutMs) {
		long target = Math.max(0L, ms);
		this.ring.clear(); // 见 requestSeek 的说明：这一步是让阻塞中的解码线程能回到循环顶部
		this.requestedSeekMs = target;
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		synchronized (this) {
			while (appliedSeekMs != target && System.nanoTime() < deadline) {
				try {
					wait(20L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return appliedSeekMs == target;
		}
	}

	/** 预缓冲：等到环形缓冲里至少有 ms 毫秒音频。 */
	public boolean awaitPrebuffer(long ms, long timeoutMs) {
		int wantBytes = (int) Math.min(Integer.MAX_VALUE, ms * BYTES_PER_MS);
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (System.nanoTime() < deadline) {
			if (ring.available() >= wantBytes) {
				return true;
			}
			try {
				Thread.sleep(10L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private void loop() {
		final short[] in = new short[8192];
		float[] buf = new float[32768];
		int len = 0;
		double pos = 0.0;
		final double ratio = inRate / (double) OUT_RATE;
		final byte[] out = new byte[65536];

		try {
			while (running) {
				long seek = requestedSeekMs;
				if (seek != appliedSeekMs) {
					try {
						source.seekSample(seek * inRate / 1000L);
					} catch (Throwable t) {
						CloudDiscAudio.LOG.warn("[CloudDisc] seek 失败: {}", t.toString());
					}
					len = 0;
					pos = 0.0;
					ring.clear();
					writtenBytes.set(BYTES_PER_MS * seek);
					appliedSeekMs = seek;
					synchronized (this) {
						notifyAll();
					}
				}

				// 1) 把缓冲区里能重采样的样本全部输出
				int outBytes = 0;
				while (pos + 1.0 < len && outBytes + 2 <= out.length) {
					int i = (int) pos;
					double frac = pos - i;
					float sample = (float) (buf[i] * (1.0 - frac) + buf[i + 1] * frac) * outputGain;
					float abs = Math.abs(sample);
					if (abs > peak) {
						peak = abs;
					}
					// 软限幅（归一化空间：0.95 以上才温和压缩，避免硬削顶的刺耳失真）
					if (abs > 0.95f) {
						sample = Math.signum(sample) * (0.95f + 0.05f * (1.0f - (float) Math.exp(-(abs - 0.95f) / 0.05f)));
					}
					// 写回 16 位 PCM：归一化 → 有符号 16 位（这一步以前漏了，也是静音的原因之一）
					int v = Math.round(sample * 32767.0f);
					if (v > 32767) {
						v = 32767;
					} else if (v < -32768) {
						v = -32768;
					}
					out[outBytes++] = (byte) (v & 0xFF);
					out[outBytes++] = (byte) ((v >> 8) & 0xFF);
					pos += ratio;
				}
				if (outBytes > 0) {
					ring.write(out, 0, outBytes);
					long written = writtenBytes.addAndGet(outBytes);
					// 输出满 10 秒后打一次峰值，方便判断"音量/爆音"问题。
					// 注意单位：BYTES_PER_MS 是"每毫秒字节数"，所以 10 秒 = 10000 * BYTES_PER_MS
					// （这里一开始少乘了 1000，变成了 10 毫秒窗口，导致误报"峰值 0.000"）
					if (!peakLogged && written > 10_000L * BYTES_PER_MS) {
						peakLogged = true;
						CloudDiscAudio.LOG.info("[CloudDisc] 输出峰值 {}（前 10 秒）{}",
								String.format(java.util.Locale.ROOT, "%.3f", peak),
								peak >= 0.999f ? "！已到满刻度，可能削顶：请降低 outputGain 或 jukeboxVolume" : "（未削顶）");
					}
				}

				// 2) 丢弃已经消费掉的输入
				int consumed = (int) pos;
				if (consumed > 0) {
					System.arraycopy(buf, consumed, buf, 0, len - consumed);
					len -= consumed;
					pos -= consumed;
				}

				// 3) 输出还没排空就继续，避免读入过多
				if (outBytes >= out.length - 2) {
					continue;
				}

				// 4) 补充输入
				int n = source.read(in, in.length);
				if (n <= 0) {
					ring.finish();
					return;
				}
				int frames = n / inChannels;
				if (len + frames > buf.length) {
					buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + frames));
				}
				for (int f = 0; f < frames; f++) {
					int sum = 0;
					int base = f * inChannels;
					for (int c = 0; c < inChannels; c++) {
						sum += in[base + c];
					}
					// 统一到"归一化浮点"空间（±1.0 = 满刻度）。
					// 这一句至关重要：以前这里只除以声道数，缓冲区里是原始 16 位幅度（±32768），
					// 而下面的 outputGain 与软限幅都按 ±1.0 语义写的 —— 结果限幅器把每个样本
					// 都压成 ±0.95，取整后就是 ±1 LSB，表现为"声音在播但完全听不到"（实测踩过：
					// 日志里峰值打出 32230 这种不可能的数字）。
					buf[len + f] = sum / (float) inChannels / 32768.0f;
				}
				// 唱片机物理声效：按遮挡度对这一段（已下混、已归一化）做低通
				// —— 隔墙变闷、远处高频先没。完全通畅时 isActive() 为假，不碰任何样本。
				Acoustics.filterMono(buf, len, frames, inRate);
				len += frames;
			}
		} catch (IOException e) {
			CloudDiscAudio.LOG.warn("[CloudDisc] 解码中断: {}", e.toString());
			ring.finish();
		} catch (Throwable t) {
			CloudDiscAudio.LOG.warn("[CloudDisc] 解码线程异常", t);
			ring.finish();
		}
	}

	@Override
	public void close() {
		running = false;
		ring.abort();
		worker.interrupt();
		try {
			source.close();
		} catch (Throwable ignored) {
			// ignore
		}
	}
}
