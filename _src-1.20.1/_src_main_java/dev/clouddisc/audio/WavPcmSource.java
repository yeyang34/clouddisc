package dev.clouddisc.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WAV（RIFF/PCM 16bit）解码。
 *
 * <p>为什么要有它：OGG 需要额外工具去转，而 WAV 谁都能生成/导出，最适合"先跑通再说"。
 * 只支持未压缩 PCM：
 * <ul>
 *   <li>16 bit（8 bit 未压缩、24/32 bit、ADPCM、float 都会明确报错并提示怎么转）</li>
 *   <li>任意采样率、单/多声道（重采样与下混由 {@link AudioPipeline} 负责）</li>
 *   <li>只读官方的 {@code fmt } / {@code data} 块，后面的 LIST/fact 等块直接跳过</li>
 * </ul>
 */
public final class WavPcmSource implements PcmSource {
	private static final int FORMAT_PCM = 1;
	private static final int FORMAT_EXTENSIBLE = 0xFFFE;

	private final byte[] file;
	private final int channels;
	private final int sampleRate;
	private final int dataOffset;
	private final int dataLength;
	private final int frameBytes;
	private long framePos;

	private WavPcmSource(byte[] file, int channels, int sampleRate, int dataOffset, int dataLength) {
		this.file = file;
		this.channels = channels;
		this.sampleRate = sampleRate;
		this.dataOffset = dataOffset;
		this.dataLength = dataLength;
		this.frameBytes = channels * 2;
	}

	public static WavPcmSource open(Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		if (bytes.length < 44 || !tagEquals(bytes, 0, "RIFF") || !tagEquals(bytes, 8, "WAVE")) {
			throw new IOException("不是合法的 WAV 文件（缺少 RIFF/WAVE 头）: " + path);
		}
		int channels = 0;
		int sampleRate = 0;
		int bits = 0;
		int fmtFormat = -1;
		int dataOffset = -1;
		int dataLength = 0;

		int p = 12;
		while (p + 8 <= bytes.length) {
			String id = new String(bytes, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
			int size = readIntLE(bytes, p + 4);
			int payload = p + 8;
			if (size < 0 || payload + size > bytes.length) {
				size = bytes.length - payload; // 有些文件最后一个块长度不准
			}
			if ("fmt ".equals(id)) {
				if (size < 16) {
					throw new IOException("fmt 块过短: " + path);
				}
				fmtFormat = readShortLE(bytes, payload) & 0xFFFF;
				channels = readShortLE(bytes, payload + 2) & 0xFFFF;
				sampleRate = readIntLE(bytes, payload + 4);
				bits = readShortLE(bytes, payload + 14) & 0xFFFF;
			} else if ("data".equals(id)) {
				dataOffset = payload;
				dataLength = size;
			}
			p = payload + size + (size & 1); // 块按偶数对齐
		}

		if (dataOffset < 0) {
			throw new IOException("WAV 里没有 data 块: " + path);
		}
		if (fmtFormat != FORMAT_PCM && fmtFormat != FORMAT_EXTENSIBLE) {
			throw new IOException("只支持未压缩 PCM 的 WAV（fmt=" + fmtFormat + "）。"
					+ "请用 ffmpeg -i in.wav -c:a pcm_s16le out.wav 或 Audacity 导出 16-bit PCM WAV: " + path);
		}
		if (bits != 16) {
			throw new IOException("只支持 16 bit 的 WAV（当前 " + bits + " bit）。"
					+ "请转成 16-bit PCM WAV，例如：ffmpeg -i in.wav -c:a pcm_s16le out.wav : " + path);
		}
		if (channels <= 0 || sampleRate <= 0) {
			throw new IOException("WAV 头信息不合法（channels=" + channels + ", rate=" + sampleRate + "）: " + path);
		}
		return new WavPcmSource(bytes, channels, sampleRate, dataOffset, dataLength);
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
		int framesLeft = (int) Math.min((dataLength - (framePos * frameBytes)) / frameBytes, Integer.MAX_VALUE);
		if (framesLeft <= 0) {
			return 0;
		}
		int frames = Math.min(framesLeft, maxShorts / channels);
		int base = dataOffset + (int) (framePos * frameBytes);
		int out = 0;
		for (int f = 0; f < frames; f++) {
			int o = base + f * frameBytes;
			for (int c = 0; c < channels; c++) {
				dst[out++] = (short) ((file[o] & 0xFF) | (file[o + 1] << 8));
				o += 2;
			}
		}
		framePos += frames;
		return out;
	}

	@Override
	public void seekSample(long sampleIndex) {
		long total = totalSamples();
		framePos = Math.max(0L, Math.min(sampleIndex, total));
	}

	@Override
	public long positionSample() {
		return framePos;
	}

	@Override
	public long totalSamples() {
		return dataLength / (long) frameBytes;
	}

	@Override
	public long durationMillis() {
		long total = totalSamples();
		return total <= 0L ? -1L : total * 1000L / sampleRate;
	}

	@Override
	public void close() {
		// 全部在内存里，无需释放
	}

	private static boolean tagEquals(byte[] b, int off, String tag) {
		if (off + 4 > b.length) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			if ((b[off + i] & 0xFF) != tag.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static int readShortLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}
}
