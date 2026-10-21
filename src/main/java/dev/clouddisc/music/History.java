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
 * 「历史记录」：最近真的放响过的曲目。
 *
 * <h2>为什么要记"真的放响过"而不是"点了确认"</h2>
 * 点确认之后可能解析失败、可能没有音频、也可能唱片机被人拔了 —— 那些不该算"听过"。
 * 所以打点在 {@code PlaybackController} 里<b>真正开声</b>的那一刻
 * （{@code state = PLAYING}）：本机发起的、<b>别人放的</b>，都走同一处 ✓
 *
 * <p>文件：{@code config/clouddisc-history.json}（纯本地、纯客户端）。
 * 最多 {@value #MAX_ITEMS} 条，同一首重放不会刷屏 —— 提到最前、播放次数 +1 ✓
 *
 * <p>key 的两种形态与收藏一致：网易云用数字 id、B 站用 {@code BV} 号 ✓
 */
public final class History {

	/** 上限（条）。 */
	private static final int MAX_ITEMS = 200;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {
	}.getType();

	private static final List<Entry> ITEMS = new ArrayList<>();
	private static boolean loaded;

	private History() {
	}

	/** 一条历史。{@code trackId} 原样保留（本地文件是 {@code local:名字}，B 站是 BV 号）。 */
	public static final class Entry {
		/** 音源 id（"163" / "bili" / "local" …）；"" = 未知。 */
		public String kind = "";
		/** 数字 id（B 站/本地文件为 0）。 */
		public long id;
		/** 原始 trackId，用来去重与重新播放。 */
		public String trackId = "";
		/** 统一主键。 */
		public String key = "";
		public String title = "";
		public long durationMs;
		public long at;
		/** 是否本机发起（false = 别人放的）。 */
		public boolean mine;
		/** 播放次数（被 {@link #record} 累加）。 */
		public int plays = 1;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("clouddisc-history.json");
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
			List<Entry> list = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), LIST_TYPE);
			if (list != null) {
				for (Entry e : list) {
					if (e == null || e.trackId == null || e.trackId.isBlank()) {
						continue;
					}
					if (e.key == null || e.key.isBlank()) {
						e.key = keyOf(e.kind, e.id, e.trackId);
					}
					ITEMS.add(e);
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 历史记录读取失败（当作空）: {}", t.toString());
		}
	}

	private static synchronized void save() {
		try {
			Path p = file();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(ITEMS, LIST_TYPE), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 历史记录写入失败（本次改动只在内存里）: {}", t.toString());
		}
	}

	private static String keyOf(String kind, long id, String trackId) {
		String k = kind == null ? "" : kind;
		if (trackId != null && !trackId.isBlank()) {
			return k + ":" + trackId;
		}
		return k + ":" + id;
	}

	private static long parseId(String trackId) {
		if (trackId == null) {
			return 0L;
		}
		try {
			return Long.parseLong(trackId.trim());
		} catch (Throwable t) {
			return 0L;
		}
	}

	/**
	 * 记一条（真正开声时调用）。
	 *
	 * @param kind       音源 id（{@code session.provider}）；用于区分 B 站与网易云
	 * @param trackId    原始 trackId（网易云是数字串；B 站是 BV 号；本地文件是 {@code local:名字}）
	 * @param title      显示名
	 * @param durationMs 时长（未知给 0）
	 * @param mine       是否本机发起
	 */
	public static synchronized void record(String kind, String trackId, String title, long durationMs, boolean mine) {
		if (trackId == null || trackId.isBlank()) {
			return;
		}
		ensureLoaded();
		// 【key 一致性】唱片名只写 @BV... 时 session.provider 是空的（靠 trackId 自动识别），
		// 这里把音源补回来 —— 否则历史里的 key 是 ":BV..."，而收藏里是 "bili:BV..."，
		// 同一首歌会出现"历史里星星不亮"✗
		String kk = kind == null ? "" : kind.trim();
		if (kk.isEmpty()) {
			String t = trackId.trim();
			if (dev.clouddisc.music.BiliProvider.looksLikeBili(t)) {
				kk = "bili";
			} else if (t.matches("\\d{1,12}")) {
				kk = "163";
			}
		}
		String k = keyOf(kk, parseId(trackId), trackId);
		// 同一首重放：提到最前、次数 +1，不新增条目（否则单曲循环会把历史刷满）
		for (int i = 0; i < ITEMS.size(); i++) {
			Entry e = ITEMS.get(i);
			if (k.equals(e.key) || trackId.equals(e.trackId)) {
				e.at = System.currentTimeMillis();
				e.plays = Math.max(1, e.plays) + 1;
				e.mine = mine;
				if (title != null && !title.isBlank()) {
					e.title = title;
				}
				if (durationMs > 0) {
					e.durationMs = durationMs;
				}
				ITEMS.remove(i);
				ITEMS.add(0, e);
				save();
				return;
			}
		}
		Entry e = new Entry();
		e.kind = kk;
		e.id = parseId(trackId);
		e.trackId = trackId;
		e.key = k;
		e.title = title == null ? "" : title;
		e.durationMs = durationMs;
		e.at = System.currentTimeMillis();
		e.mine = mine;
		e.plays = 1;
		ITEMS.add(0, e);
		while (ITEMS.size() > MAX_ITEMS) {
			ITEMS.remove(ITEMS.size() - 1);
		}
		save();
	}

	/** 全部历史（新的在前），转成界面用的 {@code Item}。不联网 ✓ */
	public static synchronized List<NeteaseSearch.Item> all() {
		ensureLoaded();
		List<NeteaseSearch.Item> out = new ArrayList<>(ITEMS.size());
		for (Entry e : ITEMS) {
			String name = e.title == null || e.title.isBlank() ? e.trackId : e.title;
			out.add(new NeteaseSearch.Item(
					e.kind == null ? "" : e.kind,
					e.id,
					name,
					"",
					"",
					"",
					e.durationMs,
					e.trackId));
		}
		return out;
	}

	public static synchronized int count() {
		ensureLoaded();
		return ITEMS.size();
	}

	/** 清空历史（界面上没有入口，留给手改文件/以后加按钮用）。 */
	public static synchronized void clear() {
		ensureLoaded();
		ITEMS.clear();
		save();
	}
}
