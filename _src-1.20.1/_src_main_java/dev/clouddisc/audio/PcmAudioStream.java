package dev.clouddisc.audio;

import net.minecraft.client.sound.AudioStream;

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
 * <p>注意 {@code net.minecraft.client.sound.AudioStream}（Yarn 1.20.1）只有
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
	public ByteBuffer getBuffer(int size) throws IOException {
		if (direct == null || direct.capacity() < size) {
			direct = ByteBuffer.allocateDirect(size);
			scratch = new byte[size];
		}
		int n = ring.read(scratch, 0, size, timeoutMs);
		direct.clear();
		if (n < 0) {
			// 真 EOF：返回空缓冲，MC 会正常收尾并释放 OpenAL source
			return direct;
		}
		if (n == 0) {
			// 欠载：返回 null —— 1.20.1 的 Channel.pumpBuffers 会安全跳过这一轮，
			// 下一轮再要数据。绝不返回空缓冲（那会被当成结束），也绝不抛异常
			// （抛了只会记一条日志然后彻底停止 pump，声音就没了）。
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
