package dev.clouddisc.audio;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用 Minecraft 自带的 LWJGL <b>stb_vorbis</b>（BSD-3）解码 OGG。
 *
 * <p>为什么走 OGG：MC 1.20.1 自带 {@code lwjgl-stb}（原生库已由游戏加载，见 vanilla 的
 * {@code OggAudioStream}），所以这是<b>零额外依赖 + 许可最干净</b>的格式。
 * MP3 / FLAC / AAC 见 {@code docs/设计文档.md} 里的解码库选型（含 License 影响）。
 *
 * <p>用 {@code open_memory} 而不是 {@code open_filename}：后者在 Windows 上对非 ASCII 路径
 * 可能失败；Java 侧读文件再用内存打开可以完全绕开这个坑。代价是整曲驻留内存。
 */
public final class OggPcmSource implements PcmSource {
	private final long handle;
	private final ByteBuffer fileData; // 必须持有引用：stb 直接引用这块内存
	private final int channels;
	private final int sampleRate;
	private final long totalSamples;
	private final ShortBuffer scratch;

	private OggPcmSource(long handle, ByteBuffer fileData, int channels, int sampleRate, long totalSamples) {
		this.handle = handle;
		this.fileData = fileData;
		this.channels = channels;
		this.sampleRate = sampleRate;
		this.totalSamples = totalSamples;
		this.scratch = BufferUtils.createShortBuffer(Math.max(4096, channels * 8192));
	}

	public static OggPcmSource open(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		ByteBuffer direct = BufferUtils.createByteBuffer(bytes.length);
		direct.put(bytes).flip();

		IntBuffer error = BufferUtils.createIntBuffer(1);
		long handle = STBVorbis.stb_vorbis_open_memory(direct, error, null);
		if (handle == 0L) {
			throw new IOException("stb_vorbis 打开失败: error=" + error.get(0) + " (" + file + ")");
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			STBVorbisInfo info = STBVorbisInfo.malloc(stack);
			STBVorbis.stb_vorbis_get_info(handle, info);
			long total = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
			return new OggPcmSource(handle, direct, info.channels(), info.sample_rate(), total);
		} catch (Throwable t) {
			STBVorbis.stb_vorbis_close(handle);
			throw t;
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
		int want = Math.min(maxShorts, scratch.capacity());
		scratch.clear();
		scratch.limit(want);
		int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, scratch);
		if (frames <= 0) {
			return 0; // 结束
		}
		int shorts = frames * channels;
		scratch.position(0);
		scratch.limit(shorts);
		scratch.get(dst, 0, shorts);
		return shorts;
	}

	@Override
	public void seekSample(long sampleIndex) {
		STBVorbis.stb_vorbis_seek(handle, (int) Math.max(0L, sampleIndex));
	}

	@Override
	public long positionSample() {
		return STBVorbis.stb_vorbis_get_sample_offset(handle);
	}

	@Override
	public long totalSamples() {
		return totalSamples;
	}

	@Override
	public long durationMillis() {
		if (totalSamples <= 0 || sampleRate <= 0) {
			return -1L;
		}
		return totalSamples * 1000L / sampleRate;
	}

	@Override
	public void close() {
		STBVorbis.stb_vorbis_close(handle);
	}
}
