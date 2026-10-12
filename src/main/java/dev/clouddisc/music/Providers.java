package dev.clouddisc.music;

import dev.clouddisc.CloudDiscConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 内置 provider 注册表。
 *
 * <ul>
 *   <li>{@code local} —— 玩家自己电脑上的音乐文件（默认，零第三方依赖）</li>
 *   <li>{@code 163}   —— 网易云（别名 {@code netease} / {@code wy}）。
 *       只做"识别分享链接 / 歌曲 id → 展开成音频地址"，<b>不登录、不带 cookie、不做接口加签</b>。
 *       没配自己的服务时走网易公开外链（免费曲可播、VIP 曲不可播，见 {@link NeteaseLinkProvider}）；
 *       配了 {@code neteaseEndpoint} 就优先走你自己的服务。</li>
 *   <li>{@code url}   —— trackId 直接就是 URL（调试用；铁砧名字限 50 字符，长 URL 放不下）</li>
 * </ul>
 */
public final class Providers {
	private static final Map<String, MusicProvider> REGISTRY = new LinkedHashMap<>();

	static {
		register(new LocalProvider());
		NeteaseLinkProvider netease = new NeteaseLinkProvider();
		register(netease); // id = "163"
		registerAlias("netease", netease);
		registerAlias("wy", netease);
		register(new DirectUrlProvider());
	}

	private Providers() {
	}

	public static void register(MusicProvider provider) {
		REGISTRY.put(provider.id().toLowerCase(Locale.ROOT), provider);
	}

	public static void registerAlias(String alias, MusicProvider provider) {
		REGISTRY.put(alias.toLowerCase(Locale.ROOT), provider);
	}

	public static MusicProvider byId(String id) {
		return id == null ? null : REGISTRY.get(id.toLowerCase(Locale.ROOT));
	}

	public static MusicProvider defaultProvider(CloudDiscConfig cfg) {
		MusicProvider p = byId(cfg.defaultProvider);
		return p != null ? p : byId("local");
	}

	// ------------------------------------------------------------------ local

	/** 在 {@code clouddisc-music/} 下按文件名找音频。 */
	public static final class LocalProvider implements MusicProvider {
		@Override
		public String id() {
			return "local";
		}

		@Override
		public CompletableFuture<MusicProvider.ResolvedTrack> resolve(String trackId, CloudDiscConfig cfg) {
			Path root = cfg.resolveLocalMusicDir().toAbsolutePath().normalize();
			Path found = lookup(root, trackId);
			if (found == null) {
				return CompletableFuture.failedFuture(new IllegalArgumentException("本地音乐目录里找不到: " + trackId));
			}
			return CompletableFuture.completedFuture(new MusicProvider.ResolvedTrack(
					id(), trackId, found.getFileName().toString(), found.toString(), -1L));
		}

		private static Path lookup(Path root, String trackId) {
			List<String> candidates = new ArrayList<>();
			candidates.add(trackId);
			if (!trackId.contains(".")) {
				// 不带扩展名时按这个顺序找（ogg/wav 是当前解码器支持的）
				candidates.add(trackId + ".ogg");
				candidates.add(trackId + ".wav");
				candidates.add(trackId + ".mp3");
				candidates.add(trackId + ".flac");
			}
			for (String c : candidates) {
				Path p = root.resolve(c).normalize();
				// 防目录穿越：必须仍在音乐目录内
				if (p.startsWith(root) && Files.isRegularFile(p)) {
					return p;
				}
			}
			return null;
		}
	}

	// ------------------------------------------------------------ direct url

	/** trackId 本身就是 URL。 */
	public static final class DirectUrlProvider implements MusicProvider {
		@Override
		public String id() {
			return "url";
		}

		@Override
		public CompletableFuture<MusicProvider.ResolvedTrack> resolve(String trackId, CloudDiscConfig cfg) {
			if (!trackId.startsWith("http://") && !trackId.startsWith("https://") && !trackId.startsWith("file:")) {
				return CompletableFuture.failedFuture(new IllegalArgumentException("不是可用的 URL: " + trackId));
			}
			return CompletableFuture.completedFuture(new MusicProvider.ResolvedTrack(
					id(), trackId, trackId, trackId, -1L));
		}
	}
}
