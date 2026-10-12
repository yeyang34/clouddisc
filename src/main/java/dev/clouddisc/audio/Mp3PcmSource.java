package dev.clouddisc.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 解码（基于 JLayer，LGPL-2.1，作为嵌套 jar 打包，见 THIRD-PARTY.md）。
 *
 * <p>为什么需要它：主流在线音源（含网易云）返回的直链基本都是 **mp3**（128/192/320 kbps），
 * 没有这条链路，任何在线音源都落不了地。
 *
 * <p>实现要点与坑：
 * <ul>
 *   <li>JLayer 的 {@code Decoder} <b>不可跨流复用</b>，每次 reset 都要新建；</li>
 *   <li>{@code SampleBuffer.getBuffer()} 返回的是<b>解码器内部复用的数组</b>，
 *       必须在解下一帧前拷走，否则数据会被覆盖；</li>
 *   <li>JLayer 没有 seek API。这里的 seek = 从头解码并丢弃到目标位置。
 *       解码远快于实时，所以"中途加入（几分钟）"大约几秒内完成，
 *       "纠偏（几十~几百毫秒）"基本瞬间完成；</li>
 *   <li>帧与帧之间采样率/声道理论上可变，这里取第一帧的值（MP3 实际几乎不会变）。</li>
 * </ul>
 */
public final class Mp3PcmSource implements PcmSource {
	/** 一帧最多 1152 个每声道样本 × 2 声道；留足冗余。 */
	private static final int FRAME_BUFFER_SHORTS = 8192;

	private final byte[] file;
	private final short[] frameBuffer = new short[FRAME_BUFFER_SHORTS];

	private Bitstream bitstream;
	private Decoder decoder;
	private int channels = 2;
	private int sampleRate = 44100;

	/** 当前帧尚未交出的样本 */
	private int pendingLen;
	private int pendingOff;
	/** 已经解码出来的总帧数（每声道） */
	private long decodedFrames;
	private boolean eof;

	private Mp3PcmSource(byte[] file) {
		this.file = file;
	}

	public static Mp3PcmSource open(Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		Mp3PcmSource source = new Mp3PcmSource(bytes);
		source.reset();
		if (!source.decodeNextFrame()) {
			source.close();
			throw new IOException("MP3 里没有可解码的帧（文件损坏或不是 MP3）: " + path);
		}
		source.reset(); // 回到开头，正式播放
		return source;
	}

	private void reset() {
		closeStream();
		this.bitstream = new Bitstream(new ByteArrayInputStream(file));
		this.decoder = new Decoder();
		this.pendingLen = 0;
		this.pendingOff = 0;
		this.decodedFrames = 0L;
		this.eof = false;
	}

	private void closeStream() {
		if (bitstream != null) {
			try {
				bitstream.close();
			} catch (Throwable ignored) {
				// ignore
			}
			bitstream = null;
		}
	}

	/** @return 是否成功解出一帧 */
	private boolean decodeNextFrame() {
		if (bitstream == null) {
			return false;
		}
		try {
			Header header = bitstream.readFrame();
			if (header == null) {
				return false;
			}
			SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
			bitstream.closeFrame();
			int len = buffer.getBufferLength();
			if (len <= 0) {
				return false;
			}
			channels = Math.max(1, buffer.getChannelCount());
			sampleRate = buffer.getSampleFrequency();
			// 必须立刻拷走：getBuffer() 是解码器内部复用数组
			int n = Math.min(len, FRAME_BUFFER_SHORTS);
			System.arraycopy(buffer.getBuffer(), 0, frameBuffer, 0, n);
			pendingLen = n;
			pendingOff = 0;
			decodedFrames += n / channels;
			return true;
		} catch (BitstreamException | DecoderException | RuntimeException e) {
			return false;
		}
	}

	@Override
	public int channels() {
		return channels;
	}

	@Override
	public int sampleRate() {
		return sampleRate;
	}

	@Override
	public int read(short[] dst, int maxShorts) throws IOException {
		int out = 0;
		while (out < maxShorts) {
			if (pendingLen > 0) {
				int n = Math.min(pendingLen, maxShorts - out);
				System.arraycopy(frameBuffer, pendingOff, dst, out, n);
				pendingOff += n;
				pendingLen -= n;
				out += n;
				continue;
			}
			if (eof || !decodeNextFrame()) {
				eof = true;
				break;
			}
		}
		return out;
	}

	@Override
	public void seekSample(long sampleIndex) {
		long target = Math.max(0L, sampleIndex);
		if (target < positionSample()) {
			reset();
		}
		// 先吐掉缓冲里超出目标的部分
		discard(positionSample() - target);
		// 再解码到目标
		while (positionSample() < target && !eof) {
			if (!decodeNextFrame()) {
				eof = true;
				break;
			}
		}
		discard(positionSample() - target);
	}

	private void discard(long frames) {
		if (frames <= 0L || pendingLen <= 0) {
			return;
		}
		int shorts = (int) Math.min(pendingLen, frames * channels);
		pendingOff += shorts;
		pendingLen -= shorts;
	}

	@Override
	public long positionSample() {
		return decodedFrames - pendingLen / Math.max(1, channels);
	}

	@Override
	public long totalSamples() {
		return -1L; // MP3 需要全文件扫帧才能知道总长，这里不做（服务端到点会发 1011 收尾）
	}

	@Override
	public long durationMillis() {
		return -1L;
	}

	@Override
	public void close() {
		closeStream();
	}
}
