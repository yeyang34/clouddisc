package dev.clouddisc.jukebox;

import dev.clouddisc.audio.AudioPipeline;
import dev.clouddisc.audio.PcmAudioStream;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 一次"唱片机正在播放网络音乐"的会话。
 *
 * <p>身份：{@code (维度, 坐标)} 决定"哪台唱片机"，{@code epoch} 决定"哪一次播放"
 * （同一台唱片机换唱片就是一个新 epoch，用来丢弃迟到的旧消息）。
 */
public final class JukeboxSession {
	public enum State {
		/** 已占位但曲目还没解析出来（发起方本地） */
		RESOLVING,
		/** 已经在下载/解码/预缓冲，等音频就绪 */
		PREPARING,
		/** 音频已就绪，等"统一开始刻"到点 */
		SCHEDULED,
		/** 正在做中途起播的 seek */
		BUFFERING,
		PLAYING,
		DEAD
	}

	public final String dim;
	public final long packedPos;
	public final String key;
	public final String epoch;
	public final String provider;
	public final String trackId;

	public String title;
	public String uri;
	public long durationMs;

	/** 换算到本机 gameTime 的开始刻（含预缓冲 lead）。 */
	public long localStartTick;
	public State state = State.SCHEDULED;
	/** 本机实际开始播放的刻与那一刻的曲内位置。 */
	public long startedTick = -1L;
	public long msAtStart;
	public boolean originator;

	// 本地播放资源
	public Identifier streamId;
	public PcmAudioStream stream;
	public AudioPipeline pipeline;
	public CloudDiscSoundInstance instance;

	// 心跳/纠偏
	public long lastHeartbeatTick = Long.MIN_VALUE / 2;
	public long lastDriftFixTick = Long.MIN_VALUE / 2;
	public boolean hbReceived;
	public long hbPosMs;
	public long hbAtTick;
	/** 诊断用：起播后是否已经打过"MC 有没有在消费音频"的日志。 */
	public boolean consumptionLogged1s;
	public boolean consumptionLogged5s;
	/** 上一次打"进度对照"日志的刻（只记录，不纠偏）。 */
	public long lastDriftLogTick = Long.MIN_VALUE / 2;
	/** 降噪用：上一次打"偏差偏大"警告的刻。 */
	public long lastDriftWarnTick = Long.MIN_VALUE / 2;
	/** 降噪用：上一次打"进度对齐正常"汇总的刻。 */
	public long lastDriftSummaryTick = Long.MIN_VALUE / 2;

	public JukeboxSession(String dim, long packedPos, String epoch, String provider, String trackId) {
		this.dim = dim;
		this.packedPos = packedPos;
		this.key = dim + "@" + packedPos;
		this.epoch = epoch == null || epoch.isEmpty() ? UUID.randomUUID().toString() : epoch;
		this.provider = provider;
		this.trackId = trackId;
	}

	public BlockPos pos() {
		return BlockPos.fromLong(packedPos);
	}

	/** 本地播放资源的整体销毁；MC 调 AudioStream.close() 时也会走到这里。 */
	public void dispose() {
		state = State.DEAD;
		if (instance != null) {
			net.minecraft.client.MinecraftClient.getInstance().getSoundManager().stop(instance);
		}
		if (streamId != null) {
			StreamRegistry.remove(streamId);
		}
		if (stream != null) {
			stream.close();
		}
		if (pipeline != null) {
			pipeline.close();
		}
		instance = null;
		stream = null;
		pipeline = null;
		streamId = null;
	}
}
