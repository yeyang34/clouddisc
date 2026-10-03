package dev.clouddisc.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clouddisc.CloudDiscConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 查询"你自备的" HTTP 接口并把响应规范成 {@link MusicProvider.ResolvedTrack}。
 *
 * <p>只接受以下两种响应，不做任何加密格式处理：
 * <ol>
 *   <li>{@code application/json}：{@code {"title":"...","url":"..."}}</li>
 *   <li>其它（例如音频流本身）：直接把请求地址当作 uri</li>
 * </ol>
 */
final class MusicJson {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private MusicJson() {
	}

	static CompletableFuture<MusicProvider.ResolvedTrack> query(String url, CloudDiscConfig cfg, String trackId, String providerId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
				if (cfg.httpHeaders != null) {
					for (String h : cfg.httpHeaders) {
						int i = h.indexOf(':');
						if (i > 0) {
							b.header(h.substring(0, i).trim(), h.substring(i + 1).trim());
						}
					}
				}
				HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
				if (resp.statusCode() / 100 != 2) {
					throw new IllegalStateException("HTTP " + resp.statusCode());
				}
				String body = new String(resp.body(), StandardCharsets.UTF_8).trim();
				if (body.startsWith("{")) {
					JsonObject o = JsonParser.parseString(body).getAsJsonObject();
					String title = o.has("title") ? o.get("title").getAsString() : trackId;
					String uri = o.has("url") ? o.get("url").getAsString() : url;
					long dur = o.has("durationMs") ? o.get("durationMs").getAsLong() : -1L;
					return new MusicProvider.ResolvedTrack(providerId, trackId, title, uri, dur);
				}
				// 不是 JSON：当成直链音频
				return new MusicProvider.ResolvedTrack(providerId, trackId, trackId, url, -1L);
			} catch (Exception e) {
				throw new RuntimeException("音源查询失败: " + e, e);
			}
		});
	}
}
