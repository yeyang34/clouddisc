package dev.clouddisc.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.PresetResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网易云搜索（走我们自己的解析服务）。
 *
 * <p>为什么必须经服务端：网易云对匿名调用返回<b>加密响应</b>（实测
 * {@code /api/search/get/web} 返回 {@code {"result":"&lt;hex&gt;"}}），客户端解不了；
 * 解析服务背后是 NeteaseCloudMusicApi，签名与加密它已经处理好了。
 *
 * <p>服务端响应（已裁成最小字段集）：
 * <pre>
 * {"ok":true,"types":[1,2000,1009],"items":[
 *    {"kind":"song","id":186016,"name":"晴天","artist":"周杰伦","album":"叶惠美","durationMs":269000}
 *    {"kind":"voice","id":3071458031,"name":"申公豹的故事","artist":"…","cover":"http://…"}
 *    {"kind":"radio","id":985021751,"name":"安全警长…","artist":"通航飞行员"}
 * ]}
 * </pre>
 *
 * <p><b>一次搜全部</b>（{@link #searchAll}）：单曲 + 声音 + 电台一次带回，
 * 界面上的筛选只做本地过滤，不用换页签再搜一遍。
 */
public final class NeteaseSearch {
	/** 搜索类型：单曲。 */
	public static final int TYPE_SONG = 1;
	/** 搜索类型：声音（播客 / 电台单集）。 */
	public static final int TYPE_VOICE = 2000;
	/** 搜索类型：电台（整档）。 */
	public static final int TYPE_RADIO = 1009;
	/** 一次搜全部。 */
	public static final int[] ALL_TYPES = {TYPE_SONG, TYPE_VOICE, TYPE_RADIO};

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** 缓存有效期（同一关键词反复翻页不用重复请求）。 */
	private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
	private static final int MAX_CACHE = 64;

	private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

	private NeteaseSearch() {
	}

	/** 一条搜索结果。 */
	public record Item(String kind, long id, String name, String artist, String album,
			String cover, long durationMs, String trackId) {

		/** 网易云这类"数字 id 就是 key"的音源用这个构造器。 */
		public Item(String kind, long id, String name, String artist, String album, String cover, long durationMs) {
			this(kind, id, name, artist, album, cover, durationMs, null);
		}

		/** 能否写成 {@code @<provider>:<trackId>}（能写才谈得上收藏/确认）。 */
		public boolean playable() {
			if (trackId != null && !trackId.isBlank()) {
				return true;
			}
			return id > 0L;
		}

		/** 写进唱片名的那一段（网易云是数字串，B 站是 {@code BV...} / {@code BV...#p2}）。 */
		public String rawTrackId() {
			if (trackId != null && !trackId.isBlank()) {
				return trackId;
			}
			return String.valueOf(id);
		}

		/**
		 * 收藏/历史的主键。
		 * <p>B 站这类 key 是字符串（BV 号），网易云是数字 id —— 统一成一个字符串，
		 * 免得两套主键逻辑各写一遍、还容易对不上 ✓
		 */
		public String key() {
			if (trackId != null && !trackId.isBlank()) {
				return kind + ":" + trackId;
			}
			return kind + ":" + id;
		}

		public boolean isBili() {
			return "bili".equals(kind);
		}

		public boolean isVoice() {
			return "voice".equals(kind);
		}

		public boolean isRadio() {
			return "radio".equals(kind);
		}

		/** 类型短标签（列表里用）。 */
		public String kindLabel() {
			if (isBili()) {
				return "B站";
			}
			if (isRadio()) {
				return "电台";
			}
			if (isVoice()) {
				return "声音";
			}
			if (kind == null || kind.isBlank()) {
				// 历史记录里只知道 id 与歌名，不知道它原本是单曲/声音/电台
				return "曲目";
			}
			return "单曲";
		}

		/** 「名字 - 歌手」。 */
		public String display() {
			if (artist == null || artist.isBlank()) {
				return name;
			}
			return name + " - " + artist;
		}
	}

	/** 一次搜索的结果（失败时 error 非空，不抛异常，界面好显示）。 */
	public record Result(String keyword, int type, List<Item> items, String via, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	private record Entry(Result result, long at) {
	}

	// ------------------------------------------------------------------ URL

	/** 从配置的解析服务地址推出搜索根地址（把 {@code /song/{id}} 去掉）。 */
	public static String searchBase(CloudDiscConfig cfg) {
		String endpoint = cfg == null ? null : cfg.neteaseEndpoint;
		if (endpoint == null || endpoint.isBlank()) {
			endpoint = PresetResolver.ENDPOINT;
		}
		if (endpoint == null || endpoint.isBlank()) {
			return null;
		}
		String e = endpoint.trim();
		int i = e.indexOf("/song/");
		if (i > 0) {
			e = e.substring(0, i);
		} else {
			int j = e.lastIndexOf('/');
			int k = e.indexOf("//");
			if (j > (k < 0 ? 0 : k + 2)) {
				e = e.substring(0, j);
			}
		}
		while (e.endsWith("/")) {
			e = e.substring(0, e.length() - 1);
		}
		return e;
	}

	// ------------------------------------------------------------------ 搜索

	/** 一次搜全部类型（单曲 + 声音 + 电台），只发一个请求。 */
	public static CompletableFuture<Result> searchAll(CloudDiscConfig cfg, String keyword, int limit) {
		String kw = keyword == null ? "" : keyword.trim();
		if (kw.isEmpty()) {
			return CompletableFuture.completedFuture(
					new Result("", TYPE_SONG, List.of(), null, "请先输入歌名 / 歌手 / 播客名"));
		}
		if (cfg != null && !cfg.neteaseEnabled) {
			return CompletableFuture.completedFuture(
					new Result(kw, TYPE_SONG, List.of(), null, "网易云音源已在配置里关闭"));
		}
		final int lim = Math.max(1, Math.min(30, limit));
		final String key = "ALL|" + lim + "|" + kw;
		Entry cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
			return CompletableFuture.completedFuture(cached.result());
		}
		String base = searchBase(cfg);
		if (base == null) {
			return CompletableFuture.completedFuture(
					new Result(kw, TYPE_SONG, List.of(), null, "没有配置解析服务地址"));
		}
		StringBuilder types = new StringBuilder();
		for (int t : ALL_TYPES) {
			if (types.length() > 0) {
				types.append(',');
			}
			types.append(t);
		}
		String url = base + "/search?keywords=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
				+ "&types=" + types + "&limit=" + lim;
		return CompletableFuture.supplyAsync(() -> {
			Result r;
			try {
				r = doQuery(url, cfg, kw, TYPE_SONG);
			} catch (Throwable t) {
				r = new Result(kw, TYPE_SONG, List.of(), null, friendly(t));
			}
			if (r.ok()) {
				if (CACHE.size() > MAX_CACHE) {
					CACHE.clear();
				}
				CACHE.put(key, new Entry(r, System.currentTimeMillis()));
			}
			return r;
		});
	}

	/** 只搜一种类型（备选，一般用 {@link #searchAll}）。 */
	public static CompletableFuture<Result> search(CloudDiscConfig cfg, String keyword, int type, int limit) {
		String kw = keyword == null ? "" : keyword.trim();
		if (kw.isEmpty()) {
			return CompletableFuture.completedFuture(
					new Result("", type, List.of(), null, "请先输入歌名 / 歌手 / 播客名"));
		}
		final int lim = Math.max(1, Math.min(30, limit));
		String base = searchBase(cfg);
		if (base == null) {
			return CompletableFuture.completedFuture(
					new Result(kw, type, List.of(), null, "没有配置解析服务地址"));
		}
		String url = base + "/search?keywords=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
				+ "&type=" + type + "&limit=" + lim;
		return CompletableFuture.supplyAsync(() -> {
			try {
				return doQuery(url, cfg, kw, type);
			} catch (Throwable t) {
				return new Result(kw, type, List.of(), null, friendly(t));
			}
		});
	}

	private static String friendly(Throwable t) {
		String msg = String.valueOf(t == null ? "" : t.getMessage());
		if (msg.contains("403")) {
			return "解析服务拒绝了请求（令牌不对，检查 X-Token）";
		}
		if (msg.contains("429")) {
			return "请求太频繁，稍等一下再搜";
		}
		return "搜索失败：" + msg;
	}

	private static Result doQuery(String url, CloudDiscConfig cfg, String kw, int type) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8));
		List<String> hdrs = new ArrayList<>();
		if (cfg != null && cfg.httpHeaders != null) {
			for (String h : cfg.httpHeaders) {
				if (h != null) {
					hdrs.add(h);
				}
			}
		}
		boolean hasToken = false;
		for (String h : hdrs) {
			if (h.toLowerCase(Locale.ROOT).startsWith("x-token")) {
				hasToken = true;
				break;
			}
		}
		if (!hasToken && !PresetResolver.TOKEN.isBlank()) {
			hdrs.add("X-Token: " + PresetResolver.TOKEN);
		}
		for (String h : hdrs) {
			int i = h.indexOf(':');
			if (i > 0) {
				b.header(h.substring(0, i).trim(), h.substring(i + 1).trim());
			}
		}
		HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() / 100 != 2) {
			throw new IllegalStateException("HTTP " + resp.statusCode());
		}
		return parse(kw, type, new String(resp.body(), StandardCharsets.UTF_8).trim());
	}

	private static Result parse(String kw, int type, String body) {
		List<Item> items = new ArrayList<>();
		String via = null;
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (o.has("error")) {
				return new Result(kw, type, List.of(), null, "服务端：" + o.get("error").getAsString());
			}
			if (o.has("codes")) {
				via = "codes=" + o.get("codes");
			} else if (o.has("via")) {
				via = o.get("via").getAsString();
			}
			JsonArray arr = o.has("items") && o.get("items").isJsonArray()
					? o.getAsJsonArray("items") : new JsonArray();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject it = el.getAsJsonObject();
				long id = it.has("id") ? it.get("id").getAsLong() : 0L;
				if (id <= 0) {
					continue;
				}
				String kind = str(it, "kind", "song");
				items.add(new Item(kind, id, clean(str(it, "name", "")), str(it, "artist", ""),
						str(it, "album", ""), str(it, "cover", ""),
						it.has("durationMs") ? it.get("durationMs").getAsLong() : 0L));
			}
		} catch (Throwable t) {
			return new Result(kw, type, List.of(), via, "服务端响应看不懂：" + t.getMessage());
		}
		if (items.isEmpty()) {
			return new Result(kw, type, List.of(), via, "没有搜到结果");
		}
		CloudDiscClient.LOGGER.info("[CloudDisc] 搜索「{}」⇒ {} 条（{}）", kw, items.size(), via);
		return new Result(kw, type, items, via, null);
	}

	private static String str(JsonObject o, String key, String def) {
		if (o.has(key) && !o.get(key).isJsonNull()) {
			return o.get(key).getAsString();
		}
		return def;
	}

	/** 剥掉服务端没剥净的「声音: 」前缀（半角/全角冒号都认）。 */
	private static String clean(String s) {
		if (s == null) {
			return "";
		}
		String t = s.trim();
		if (t.startsWith("声音:") || t.startsWith("声音：")) {
			t = t.substring(3).trim();
			if (t.startsWith(":") || t.startsWith("：")) {
				t = t.substring(1).trim();
			}
		}
		return t;
	}
}
