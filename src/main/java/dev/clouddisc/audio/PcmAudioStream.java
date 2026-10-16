package dev.clouddisc.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 {@link PcmRingBuffer} 伪装成 Minecraft 认得的 {@code AudioStream}。
 *
 * <p>这是"真正替换唱片机声音"的核心：声音仍然是 MC 自己的 {@code SoundSystem}
 * 通过 OpenAL 播放的（因此定位衰减、RECORDS 音量滑块、暂停、原版停止逻辑全都照旧生效），
 * 只是喂进去的 PCM 来自我们。
 *
 * <p>注意 {@code net.minecraft.client.sounds.AudioStream}（Yarn 1.20.1）只有
 * {@code getFormat()} 与 {@code getBuffer(int)}，并继承 {@link AutoCloseable}：
 * <b>返回空缓冲 = 流结束</b>，所以欠载时必须补静音（见 {@link PcmRingBuffer#read}）。
 */
public final class PcmAudioStream implements AudioStream {
	private static final AudioFormat FORMAT = new AudioFormat(48000.0f, 16, 1, true, false);

	private final PcmRingBuffer ring;
	private final long timeoutMs;
	private final Runnable onClose;
	private ByteBuffer direct;
	private byte[] scratch;
	private boolean closed;
	/** 循环用：读到真实结尾时调它重开解码器 ✓（返回 false 表示无法循环 ⇒ 按原版契约结束 ✓） */
	private java.util.function.BooleanSupplier restartOnEof;
	/** 循环垫静音的计数（每段 20ms ✓ 上限约 2 秒 ✓ 超过就认定重开失败 ✓） */
	private static final int MAX_SILENCE_PADS = 400;   // 400 × 20ms ≈ 8 秒 ✓ 足够解码器重启 ✓
	private int silencePads = 0;
	private boolean restartPending = false;

	/** 只为把日志收敛成一行 ✓ 避免刷屏 ✓ */
	private static final class RestartLogger {
		private static long last = 0L;

		static void log(int pads) {
			long now = System.currentTimeMillis();
			if (now - last > 500L) {
				last = now;
				dev.clouddisc.CloudDiscClient.LOGGER.info("[CloudDisc] 单曲循环: 已请求重开音源 ✓ 垫静音中（已垫 {} 段，约 {} ms）", pads, pads * 20);
			}
		}
	}

	public void setRestartOnEof(java.util.function.BooleanSupplier s) { this.restartOnEof = s; }

	/** 由管线提供：解码线程是否已经读到文件尾 ✓（欠载 + 已到底 ⇒ 该循环了 ✓） */
	private java.util.function.BooleanSupplier endReached;

	public void setEndReached(java.util.function.BooleanSupplier s) { this.endReached = s; }

	public PcmAudioStream(PcmRingBuffer ring, long timeoutMs, Runnable onClose) {
		this.ring = ring;
		this.timeoutMs = timeoutMs;
		this.onClose = onClose;
	}

	@Override
	public AudioFormat getFormat() {
		return FORMAT;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		if (direct == null || direct.capacity() < size) {
			direct = ByteBuffer.allocateDirect(size);
			scratch = new byte[size];
		}
		int n = ring.read(scratch, 0, size, timeoutMs);
		direct.clear();
		if (n < 0) {
			// 【单曲循环 · 绝不阻塞】这一段跑在【声音引擎线程】上 ✗
			// 实测：在这里等待（最多 12 秒）会让整个游戏卡住 ✗✗
			// 正确做法：只【踢一下】重开（瞬间返回 ✓）然后送回一小段静音 ✓
			//   ⇒ 引擎把静音当数据 ✓ 下一轮还会来取 ✓ 那时解码器已经补上真数据 ✓
			//   ⇒ 连续垫静音超过上限（约 2 秒）说明重开真的失败 ⇒ 按原版契约结束 ✓
			java.util.function.BooleanSupplier r = restartOnEof;
			if (r != null && silencePads < MAX_SILENCE_PADS) {
				boolean justRestarted = false;
				if (!restartPending) {
					try {
						restartPending = r.getAsBoolean();
					} catch (Throwable t) {
						restartPending = false;
					}
					justRestarted = restartPending;
				}
				if (restartPending) {
					// 先试一把非阻塞读（可能已经补上了 ✓）
					int m = ring.read(scratch, 0, size, 0L);
					if (m > 0) {
						restartPending = false;
						silencePads = 0;
						direct.clear();
						direct.put(scratch, 0, m);
						direct.flip();
						return direct;
					}
					if (justRestarted) {
					RestartLogger.log(silencePads);
					}
					// 垫 20ms 静音 ✓（= 1920 字节 @48k/16bit/mono ✓ 取 size 与 1920 的较小值 ✓）
					silencePads++;
					if (silencePads % 25 == 0) {
						dev.clouddisc.CloudDiscClient.LOGGER.info("[CloudDisc] 单曲循环: 仍在等待解码器补数据… 已垫 {} 段（约 {} ms）", silencePads, silencePads * 20);
					}
					int pad = Math.min(size, 1920);
					java.util.Arrays.fill(scratch, 0, pad, (byte) 0);
					direct.clear();
					direct.put(scratch, 0, pad);
					direct.flip();
					return direct;
				}
			}
			return null;   // 无法循环 ⇒ 真正结束 ✓
		}
		if (n == 0 && restartOnEof != null && endReached != null && endReached.getAsBoolean()) {
			// 【循环触发点】环形缓冲欠载，且解码线程已读到文件尾 ⇒ 就是"该重开了"✓
			// 这里绝不再干等（那会卡住声音引擎线程 ✗）—— 只踢一下重开 + 垫 20ms 静音 ✓
			boolean justRestarted2 = false;
			if (!restartPending) {
				try {
					restartPending = restartOnEof.getAsBoolean();
				} catch (Throwable t) {
					restartPending = false;
				}
				justRestarted2 = restartPending;
			}
			if (restartPending) {
				int m2 = ring.read(scratch, 0, size, 0L);
				if (m2 > 0) {
					restartPending = false;
					silencePads = 0;
					direct.clear();
					direct.put(scratch, 0, m2);
					direct.flip();
					return direct;
				}
				if (justRestarted2) {
					RestartLogger.log(silencePads);
				}
				silencePads++;
				int pad2 = Math.min(size, 1920);
				java.util.Arrays.fill(scratch, 0, pad2, (byte) 0);
				direct.clear();
				direct.put(scratch, 0, pad2);
				direct.flip();
				return direct;
			}
		}
		if (n == 0) {
			// 欠载：返回 null —— 1.20.1 的 Channel.pumpBuffers 会安全跳过这一轮，
			// 下一轮再要数据。绝不返回空缓冲（那会被当成结束），也绝不抛异常
			// （抛了只会记一条日志然后彻底停止 pump，声音就没了）。
			// 回退到原版正常契约：结束时返回【空缓冲】✓
			// （返回 null 会让引擎以为"这一段结束"从而可能重新要流/重放开头 ✗ 实测出现"突然从头播放"✗）
			// 欠载（n == 0）：必须返回 null ✓ 原版 Channel.pumpBuffers 会安全跳过这一轮 ✓
			// 绝不能返回空缓冲 ✗（那会被当成"流结束"⇒ 停止/重新取流 ⇒ 听感上"突然从头播放"✗）
			return null;
		}
		direct.put(scratch, 0, n);
		direct.flip();
		return direct;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		ring.abort();
		if (onClose != null) {
			onClose.run();
		}
	}
}
