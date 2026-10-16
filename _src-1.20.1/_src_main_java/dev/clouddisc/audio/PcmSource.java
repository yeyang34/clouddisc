package dev.clouddisc.audio;

import java.io.IOException;

/**
 * 解码后的 PCM 源：统一的"可读 + 可 seek"抽象。
 *
 * <p>约定：{@link #read} 返回的是<b>交错 short 样本</b>的数量（不是帧数）。
 * 采样率/声道数由源决定，重采样与单声道下混由 {@link AudioPipeline} 负责。
 */
public interface PcmSource extends AutoCloseable {
	int channels();

	int sampleRate();

	/** @return 写入 dst 的 short 数量；<=0 表示结束 */
	int read(short[] dst, int maxShorts) throws IOException;

	/** 按"每声道采样数"定位。 */
	void seekSample(long sampleIndex);

	/** 当前"每声道采样数"位置。 */
	long positionSample();

	/** 总采样数（每声道），-1 表示未知。 */
	long totalSamples();

	long durationMillis();

	@Override
	void close();
}
