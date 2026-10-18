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
 * 网易云搜索（走我们自己的解析服务 ✓）。
 *
 * <p>为什么必须经服务端 ✗：网易云对匿名调用返回的是<b>加密响应</b>（实测
 * {@code /api/search/get/web} 返回 {@code {"result":"<hex>"}}），客户端自己解不了 ✓；
 * 而解析服务背后是 NeteaseCloudMusicApi ✓ 它已经处理了签名与加密 ✓。
 *
 * <p>服务端会把响应裁成最小字段集 ✓：
 * <pre>
 * {"ok":true,"type":1,"via":"cloudsearch","items":[
 *    {"kind":"song","id":186016,"name":"晴天","artist":"周杰伦","album":"叶惠美","durationMs":269000}
 *    {"kind":"voice","id":3071458031,"name":"申公豹的故事","artist":"…","cover":"http://…"}
 * ]}
 * </pre>
 *
 * <p>「声音」（播客/电台节目 ✓）用 {@link #TYPE_VOICE} 搜 ✓ 它们的 id 直接就是
 * 可播放的曲目 id（解析服务会把它落到 mainSong ✓ 实测能出 mp3 直链 ✓）。
 */
public final class NeteaseSearch {
	/** 搜索类型：单曲。 */
	public static final int TYPE_SONG = 1;
	/** 搜索类型：声音（播客 / 电台节目）。 */
	public static final int TYPE_VOICE = 2000;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** 缓存有效期（同一关键词反复翻页就不用重复请求 ✓）。 */
	private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
	private static final int MAX_CACHE = 64;

	private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

	private NeteaseSearch() {
	}

	/** 一条搜索结果 ✓。 */
	public record Item(String kind, long id, String name, String artist, String album,
			String cover, long durationMs) {
		/** 声音（播客）还是单曲 ✓。 */
		public boolean isVoice() {
			return "voice".equals(kind) || "radio".equals(kind);
		}

		/** 显示用一行：「名字 - 歌手」✓。 */
		public String display() {
			if (artist == null || artist.isBlank()) {
				return name;
			}
			return name + " - " + artist;
		}
	}

	/** 一次搜索的结果（失败时 error 非空 ✓ 不抛异常，界面好显示 ✓）。 */
	public record Result(String keyword, int type, List<Item> items, String via, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	private record Entry(Result result, long at) {
	}

	// ------------------------------------------------------------------ URL

	/** 从配置的解析服务地址推出搜索根地址 ✓（把 {@code /song/{id}} 那一段去掉 ✓）。 */
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

	public static CompletableFuture<Result> search(CloudDiscConfig cfg, String keyword, int type, int limit) {
		String kw = keyword == null ? "" : keyword.trim();
		if (kw.isEmpty()) {
			return CompletableFuture.completedFuture(
					new Result("", type, List.of(), null, "请先输入歌名 / 歌手 / 播客名"));
		}
		if (cfg != null && !cfg.neteaseEnabled) {
			return CompletableFuture.completedFuture(
					new Result(kw, type, List.of(), null, "网易云音源已在配置里关闭"));
		}
		int lim = Math.max(1, Math.min(30, limit));
		String key = type + "|" + lim + "|" + kw;
		Entry cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
			return CompletableFuture.completedFuture(cached.result());
		}
		String base = searchBase(cfg);
		if (base == null) {
			return CompletableFuture.completedFuture(
					new Result(kw, type, List.of(), null, "没有配置解析服务地址"));
		}
		String url = base + "/search?keywords=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
				+ "&type=" + type + "&limit=" + lim;
		return CompletableFuture.supplyAsync(() -> {
			Result r;
			try {
				r = doQuery(url, cfg, kw, type);
			} catch (Throwable t) {
				String msg = String.valueOf(t.getMessage());
				if (msg.contains("403")) {
					msg = "解析服务拒绝了请求（令牌不对 ✓ 检查配置里的 X-Token）";
				} else if (msg.contains("429")) {
					msg = "请求太频繁，稍等一下再搜 ✓";
				} else {
					msg = "搜索失败：" + msg;
				}
				r = new Result(kw, type, List.of(), null, msg);
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

	private static Result doQuery(String url, CloudDiscConfig cfg, String kw, int type) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8));
		java.util.List<String> hdrs = new ArrayList<>();
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
		String body = new String(resp.body(), StandardCharsets.UTF_8).trim();
		return parse(kw, type, body);
	}

	private static Result parse(String kw, int type, String body) {
		List<Item> items = new ArrayList<>();
		String via = null;
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (o.has("error")) {
				return new Result(kw, type, List.of(), null, "服务端：" + o.get("error").getAsString());
			}
			if (o.has("via")) {
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
				String name = clean(str(it, "name", ""));
				items.add(new Item(kind, id, name, str(it, "artist", ""), str(it, "album", ""),
						str(it, "cover", ""), it.has("durationMs") ? it.get("durationMs").getAsLong() : 0L));
			}
		} catch (Throwable t) {
			return new Result(kw, type, List.of(), via, "服务端响应看不懂：" + t.getMessage());
		}
		if (items.isEmpty()) {
			return new Result(kw, type, List.of(), via, "没有搜到结果");
		}
		CloudDiscClient.LOGGER.info("[CloudDisc] 搜索「{}」type={} ⇒ {} 条（via={}）", kw, type, items.size(), via);
		return new Result(kw, type, items, via, null);
	}

	private static String str(JsonObject o, String key, String def) {
		if (o.has(key) && !o.get(key).isJsonNull()) {
			return o.get(key).getAsString();
		}
		return def;
	}

	/** 剥掉服务端没剥干净的「声音: 」（半角/全角冒号都认 ✓）。 */
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