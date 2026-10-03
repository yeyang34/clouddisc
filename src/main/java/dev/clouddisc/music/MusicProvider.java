package dev.clouddisc.music;

import dev.clouddisc.CloudDiscConfig;

import java.util.concurrent.CompletableFuture;

/**
 * 音源 provider：把唱片名字里的短 key 解析成一个"可播放的东西"。
 *
 * <p>关键设计：<b>只有发起播放的那个客户端需要真正能解析</b>。解析结果（含 URL）
 * 会随会话消息广播给其它玩家，所以别人不需要各自配置音源/账号。
 */
public interface MusicProvider {
	String id();

	/** 解析失败请返回 failedFuture，不要返回 null。 */
	CompletableFuture<ResolvedTrack> resolve(String trackId, CloudDiscConfig cfg);

	/** 解析结果。{@code uri} 可以是本地路径、file: 或 http(s): 。 */
	record ResolvedTrack(String provider, String trackId, String title, String uri, long durationMsHint) {
	}
}
