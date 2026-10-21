package dev.clouddisc.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.CompletableFuture;

/**
 * 选歌界面「B站」页用的解析器：把用户粘贴的视频页链接变成可选列表。
 *
 * <p>支持粘贴：完整视频页 URL（含 {@code ?p=2}）、纯 {@code BV} 号、{@code av} 号、{@code b23.tv} 短链
 * —— 短链的跳转由服务端跟（客户端这里只做转发，省一次跨境请求）。
 *
 * <p>多P 视频会展开成多行（P1…Pn），每一行都能单独选 ✓
 */
public final class BiliSearch {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private BiliSearch() {
	}

	/** 解析结果：要么有 items，要么有 error。 */
	public record Result(List<NeteaseSearch.Item> items, String error) {
		public boolean ok() {
			return error == null;
		}

		static Result fail(String msg) {
			return new Result(List.of(), msg);
		}
	}

	public static CompletableFuture<Result> resolve(CloudDiscConfig cfg, String raw) {
		return CompletableFuture.supplyAsync(() -> {
			String text = raw == null ? "" : raw.trim();
			if (text.isEmpty()) {
				return Result.fail("把 B 站视频链接（或 BV 号）粘到上面的输入框");
			}
			String base = BiliProvider.base(cfg);
			if (base == null || base.isBlank()) {
				return Result.fail("没有可用的解析服务地址（检查 neteaseEndpoint / biliEndpoint）");
			}
			try {
				String url = base + "/bili?url=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
				HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25));
				b.header("User-Agent", "CloudDisc/0.1 (Minecraft mod)");
				// 与其它请求一致：带上 X-Token（配置里没有就用内置预设）
				List<String> hdrs = new ArrayList<>();
				if (cfg != null && cfg.httpHeaders != null) {
					for (String h : cfg.httpHeaders) {
						if (h != null && !h.isBlank()) {
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
				String body = new String(resp.body(), StandardCharsets.UTF_8).trim();
				if (resp.statusCode() / 100 != 2) {
					return Result.fail("解析服务返回 HTTP " + resp.statusCode()
							+ (body.startsWith("{") ? "：" + shortError(body) : ""));
				}
				if (!body.startsWith("{")) {
					return Result.fail("解析服务返回的不是 JSON");
				}
				JsonObject o = JsonParser.parseString(body).getAsJsonObject();
				if (o.has("ok") && !o.get("ok").getAsBoolean()) {
					return Result.fail(o.has("error") ? o.get("error").getAsString() : "解析失败");
				}
				String bvid = o.has("bvid") ? o.get("bvid").getAsString() : "";
				String title = o.has("title") ? o.get("title").getAsString() : bvid;
				String author = o.has("author") ? o.get("author").getAsString() : "";
				long dur = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0L;
				JsonArray parts = o.has("parts") && o.get("parts").isJsonArray()
						? o.getAsJsonArray("parts") : new JsonArray();
				List<NeteaseSearch.Item> items = new ArrayList<>();
				if (parts.size() <= 1) {
					items.add(new NeteaseSearch.Item("bili", 0L, title, author, "", "", dur, bvid));
				} else {
					for (int i = 0; i < parts.size(); i++) {
						JsonObject p = parts.get(i).getAsJsonObject();
						int idx = p.has("p") ? p.get("p").getAsInt() : (i + 1);
						String partTitle = p.has("title") ? p.get("title").getAsString() : title;
						long pdur = p.has("durationMs") ? p.get("durationMs").getAsLong() : 0L;
						items.add(new NeteaseSearch.Item("bili", 0L,
								"P" + idx + "  " + partTitle, author, "", "", pdur, bvid + "#p" + idx));
					}
				}
				if (items.isEmpty()) {
					return Result.fail("这个视频没有可播放的音频");
				}
				return new Result(items, null);
			} catch (Exception e) {
				return Result.fail("解析失败：" + e.getMessage());
			}
		});
	}

	/** 从服务端返回的 JSON 里抠一句人话错误（不把整段 JSON 塞给玩家）。 */
	private static String shortError(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			if (o.has("error")) {
				String e = o.get("error").getAsString();
				return e.length() > 80 ? e.substring(0, 80) : e;
			}
		} catch (Exception ignored) {
			// 忽略
		}
		return "";
	}
}
