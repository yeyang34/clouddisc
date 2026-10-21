package dev.clouddisc.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.clouddisc.CloudDiscClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 选歌界面的「收藏」。
 *
 * <h2>为什么单独存一份文件</h2>
 * 收藏必须<b>与搜索无关</b>：断网、解析服务挂了、或者只是想快速找到常听的歌，
 * 都得能列出来。所以每条收藏把<b>显示需要的字段一起存下来</b>
 * （名字 / 歌手 / 专辑 / 时长 / 类型 / 原始 key），打开界面时不发任何请求就能渲染 ✓
 *
 * <p>文件：{@code config/clouddisc-favorites.json}（纯本地、纯客户端，不上传、不同步）。
 *
 * <h2>主键（两种音源统一）</h2>
 * <ul>
 *   <li>网易云：数字 id —— <b>按 id 认</b>（同一首歌不管从"单曲"还是"历史"里收藏，都是同一条 ✓）</li>
 *   <li>B 站：{@code BV} 号这种字符串 —— <b>按 trackId 认</b>（没有数字 id）✓</li>
 * </ul>
 *
 * <p>全程 try/catch：收藏是个便利功能，任何 IO 出错都不该影响选歌与播放 ✓
 */
public final class Favorites {

	/** 上限：够用即可，超了从最旧的开始丢。 */
	private static final int MAX_ITEMS = 500;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Fav>>() {
	}.getType();

	private static final List<Fav> ITEMS = new ArrayList<>();
	private static boolean loaded;

	private Favorites() {
	}

	/**
	 * 落盘用的扁平结构。
	 * <p>故意<b>不用</b> {@code NeteaseSearch.Item} 直接序列化：那是个 record，
	 * 依赖 Gson 2.10+ 的 record 支持；用自己的 POJO 就不吃这个版本约束 ✓
	 */
	public static final class Fav {
		public String kind = "";
		/** 数字 id（B 站为 0）。 */
		public long id;
		/** 字符串 key（B 站是 BV 号；网易云留空）。 */
		public String trackId = "";
		/** 统一主键，加载时补齐。 */
		public String key = "";
		public String name = "";
		public String artist = "";
		public String album = "";
		public long durationMs;
		public long addedAt;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("clouddisc-favorites.json");
	}

	private static String keyOf(String kind, long id, String trackId) {
		String k = kind == null ? "" : kind;
		if (trackId != null && !trackId.isBlank()) {
			return k + ":" + trackId;
		}
		return k + ":" + id;
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path p = file();
			if (!Files.isRegularFile(p)) {
				return;
			}
			List<Fav> list = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), LIST_TYPE);
			if (list != null) {
				for (Fav f : list) {
					if (f == null) {
						continue;
					}
					if (f.id == 0L && (f.trackId == null || f.trackId.isBlank())) {
						continue; // 空条目
					}
					if (f.key == null || f.key.isBlank()) {
						f.key = keyOf(f.kind, f.id, f.trackId); // 老文件兼容
					}
					ITEMS.add(f);
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 收藏读取失败（当作空）: {}", t.toString());
		}
	}

	private static synchronized void save() {
		try {
			Path p = file();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(ITEMS, LIST_TYPE), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 收藏写入失败（本次改动只在内存里）: {}", t.toString());
		}
	}

	/**
	 * 两条是不是"同一个东西"。
	 * <p>都有数字 id ⇒ 只比 id（同一首歌从哪个入口来的都算同一条 ✓）；
	 * 否则比字符串 key（B 站）✓
	 */
	private static boolean same(NeteaseSearch.Item it, Fav f) {
		if (it.id() > 0L && f.id > 0L) {
			return it.id() == f.id;
		}
		return it.key().equals(f.key);
	}

	/** 这条是否已收藏。 */
	public static synchronized boolean isFav(NeteaseSearch.Item it) {
		return it != null && isFav(it.kind(), it.id(), it.trackId());
	}

	public static synchronized boolean isFav(String kind, long id, String trackId) {
		ensureLoaded();
		String k = keyOf(kind, id, trackId);
		// 【性能】这个方法【每帧 × 每行】都会被调用（列表里的 ★/☆），
		// 所以先比基本类型、再比字符串，避免每帧造一堆临时对象 ✓
		for (Fav f : ITEMS) {
			if (id > 0L && f.id > 0L) {
				if (f.id == id) {
					return true;
				}
				continue;
			}
			if (k.equals(f.key)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 切换收藏。
	 *
	 * @return {@code true} = 现在已收藏；{@code false} = 现在已取消
	 */
	public static synchronized boolean toggle(NeteaseSearch.Item it) {
		ensureLoaded();
		if (it == null || !it.playable()) {
			return false; // 没有可写进唱片的 key（例如本地文件）⇒ 不能收藏
		}
		for (int i = 0; i < ITEMS.size(); i++) {
			if (same(it, ITEMS.get(i))) {
				ITEMS.remove(i);
				save();
				return false;
			}
		}
		Fav f = new Fav();
		f.kind = it.kind() == null ? "" : it.kind();
		f.id = it.id();
		f.trackId = it.trackId() == null ? "" : it.trackId();
		f.key = it.key();
		f.name = it.name() == null ? "" : it.name();
		f.artist = it.artist() == null ? "" : it.artist();
		f.album = it.album() == null ? "" : it.album();
		f.durationMs = it.durationMs();
		f.addedAt = System.currentTimeMillis();
		ITEMS.add(0, f); // 新的排在最前
		while (ITEMS.size() > MAX_ITEMS) {
			ITEMS.remove(ITEMS.size() - 1);
		}
		save();
		return true;
	}

	/** 全部收藏（新的在前），转换成界面用的 {@code Item}。不联网 ✓ */
	public static synchronized List<NeteaseSearch.Item> all() {
		ensureLoaded();
		List<NeteaseSearch.Item> out = new ArrayList<>(ITEMS.size());
		for (Fav f : ITEMS) {
			String tid = f.trackId == null || f.trackId.isBlank() ? null : f.trackId;
			out.add(new NeteaseSearch.Item(
					f.kind == null ? "" : f.kind,
					f.id,
					f.name == null ? "" : f.name,
					f.artist == null ? "" : f.artist,
					f.album == null ? "" : f.album,
					"",
					f.durationMs,
					tid));
		}
		return out;
	}

	/** 收藏条数。 */
	public static synchronized int count() {
		ensureLoaded();
		return ITEMS.size();
	}
}
