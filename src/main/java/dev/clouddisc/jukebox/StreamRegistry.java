package dev.clouddisc.jukebox;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.audio.PcmAudioStream;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合成 stream id 注册表：把"某次会话的音频流"登记到一个独一无二的
 * {@link Identifier} 上，供 {@code SoundLoaderMixin} 在 {@code loadStreamed} 里取走。
 *
 * <p>为什么需要独一无二的 id：{@code SoundLoader} 对 stream 可能按 id 做缓存，
 * 复用同一个 id 会拿到上一条（已经播完的）流。
 */
public final class StreamRegistry {
	private static final Map<Identifier, AudioStream> STREAMS = new ConcurrentHashMap<>();
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private StreamRegistry() {
	}

	/** 生成一个新的 stream id（形如 {@code clouddisc:stream/7}）。 */
	public static Identifier nextId() {
		return new Identifier("clouddisc", "stream/" + COUNTER.incrementAndGet());
	}

	public static void put(Identifier id, AudioStream stream) {
		STREAMS.put(id, stream);
	}

	public static void remove(Identifier id) {
		STREAMS.remove(id);
	}

	/**
	 * 取一条流。
	 *
	 * <p>传进来的 id 是 {@code Sound#getLocation()} 变换后的结果（形如
	 * {@code clouddisc:sounds/stream/7.ogg}），这里做归一化后再匹配，
	 * 避免依赖具体的前后缀拼接规则。
	 *
	 * <p>刻意**不**在取出时删除：MC 万一重试（同一条声音再次取流）还能拿到，
	 * 真正的清理在会话结束时由 {@link #remove(Identifier)} 负责。
	 * 这条日志是排查"没声音"的第一个地方：能直接看出 hook 有没有被调用、id 有没有匹配上。
	 */
	public static AudioStream take(Identifier transformed) {
		Identifier key = normalize(transformed);
		if (key == null) {
			return null;
		}
		AudioStream stream = STREAMS.get(key);
		CloudDiscClient.LOGGER.info("[CloudDisc] SoundLoader.getStream 被调用: 原始 id={} → 归一化={} → {}",
				transformed, key, stream != null ? "命中我们的流" : "未注册（会退回原版，说明会话已结束或 id 不匹配）");
		return stream;
	}

	public static boolean owns(Identifier transformed) {
		return normalize(transformed) != null;
	}

	private static Identifier normalize(Identifier id) {
		if (id == null || !"clouddisc".equals(id.getNamespace())) {
			return null;
		}
		String path = id.getPath();
		int i = path.indexOf("stream/");
		if (i < 0) {
			return null;
		}
		String key = path.substring(i + "stream/".length());
		if (key.endsWith(".ogg")) {
			key = key.substring(0, key.length() - 4);
		}
		if (key.isEmpty()) {
			return null;
		}
		return new Identifier("clouddisc", "stream/" + key);
	}

	/** 便于调试：当前登记了多少条还没被 MC 取走的流。 */
	public static int size() {
		return STREAMS.size();
	}

	/** 会话被强行终止时保证流也被关掉，避免泄漏 OpenAL source。 */
	public static void abortAll() {
		for (AudioStream s : STREAMS.values()) {
			try {
				if (s instanceof PcmAudioStream p) {
					p.close();
				}
			} catch (Throwable ignored) {
				// ignore
			}
		}
		STREAMS.clear();
	}
}
