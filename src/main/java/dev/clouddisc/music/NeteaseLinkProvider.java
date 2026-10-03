package dev.clouddisc.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.audio.TrackFetcher;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云 provider：把"分享链接 / 歌曲 id"变成一条能直接下载的音频地址。
 *
 * <p>支持的输入（都能塞进铁砧 50 字符以内）：
 * <pre>
 *   @163:186016                                  ← 最短，10 字符
 *   @163:https://music.163.com/song?id=186016    ← 41 字符
 *   @https://music.163.com/song?id=186016        ← 直接粘分享链接，前面加个 @ 就行（自动识别）
 * </pre>
 *
 * <p>解析优先级：
 * <ol>
 *   <li>{@code neteaseEndpoint} 非空 → 请求<b>你自己的</b>解析服务（最可靠，能处理的曲目也最多）</li>
 *   <li>否则用 {@code neteaseUrlTemplate} 展开（默认走网易的公开外链）</li>
 * </ol>
 *
 * <p><b>公开外链的实测事实（2026-10 实测，8 首抽样）</b>：
 * <ul>
 *   <li>免费/非独家曲目：{@code 200 + audio/mpeg}，302 到 CDN 的 mp3（3–5 MB）→ <b>可以播</b></li>
 *   <li>VIP/独家/下架曲目：302 到 {@code https://music.163.com/404}，返回 {@code text/html}（107 KB）→ <b>不能播</b></li>
 *   <li>302 的目标是 <b>http</b> 的 CDN 地址 → 客户端必须允许 https→http 的重定向跟随，
 *       否则会停在 302 上（curl 能过、Java 默认不能过，这是个很容易踩的坑）</li>
 *   <li>UA / Referer 无关紧要（用 mod 自己的 UA 也能拿到）</li>
 * </ul>
 * 也就是说：这个 provider <b>只播"匿名就能播的内容"</b>；要放 VIP 曲目只能靠你自己的服务（第 1 条）。
 *
 * <p>它不做的事：不登录、不带 cookie、不做接口加签、不解析加密格式、不缓存后再分发。
 */
public final class NeteaseLinkProvider implements MusicProvider {
	private static final Pattern BARE_ID = Pattern.compile("^(\\d{1,12})$");
	private static final Pattern ID_PARAM = Pattern.compile("(?:[?&#/])id=(\\d{1,12})");
	private static final Pattern SONG_PATH = Pattern.compile("/song/(\\d{1,12})");
	/**
	 * "数字开头"的写法：后面允许跟备注，用来支持
	 * {@code 186016（晴天）}、{@code 186016 晴天}、{@code 186016-晴天} 这类唱片名。
	 * <p>唱片名上限约 50 字符，所以只认开头那串数字，其余一律当备注忽略。
	 */
	private static final Pattern LEADING_ID = Pattern.compile("^\\s*(\\d{1,12})(?!\\d)");

	@Override
	public String id() {
		return "163";
	}

	@Override
	public CompletableFuture<MusicProvider.ResolvedTrack> resolve(String trackId, CloudDiscConfig cfg) {
		if (!cfg.neteaseEnabled) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"网易云 provider 已在配置里关闭（neteaseEnabled = false）"));
		}
		String songId = extractSongId(trackId);
		if (songId == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
					"没能从「" + trackId + "」里认出网易云歌曲 id。"
							+ "可以填：纯数字 id（如 186016）、歌曲页面链接、或分享文本里的那段链接。"
							+ "注意 163cn.tv 短链无法解析（它只会跳到网页），请用完整页面链接。"));
		}
		String endpoint = cfg.neteaseEndpoint;
		if (endpoint != null && !endpoint.isBlank()) {
			return MusicJson.query(endpoint.replace("{id}", songId), cfg, songId, id());
		}
		String template = cfg.neteaseUrlTemplate;
		if (template == null || template.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"neteaseUrlTemplate 与 neteaseEndpoint 都是空的，无法解析曲目"));
		}
		String url = template.replace("{id}", songId);
		// 曲目 id 只用于定位；**显示用的歌名去网易云查真名**（歌名 + 歌手）。
		// 查不到就用兜底标题，不影响播放。
		return fetchMeta(songId, cfg).thenApply(meta -> new MusicProvider.ResolvedTrack(
				id(), songId, meta != null ? meta : ("网易云 #" + songId), url, -1L));
	}

	// ---------------------------------------------------------------- 元数据

	/** 元数据查询的时间预算：超时就放弃（它只是"显示得更好看"）。 */
	private static final long META_TIMEOUT_MS = 1200L;
	private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|#39|apos);");
	/** 页面是 JS 渲染的，`<title>` 实测为空；但分享用的 og: 标签有值（实测）。 */
	private static final Pattern OG_TITLE = Pattern.compile(
			"<meta[^>]*property=[\"']og:title[\"'][^>]*content=[\"'](.*?)[\"']", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern OG_DESC = Pattern.compile(
			"<meta[^>]*property=[\"']og:description[\"'][^>]*content=[\"'](.*?)[\"']", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern BY_ARTIST = Pattern.compile("由\\s*(.{1,60}?)\\s*演唱");

	/**
	 * 查"歌名 + 歌手"（三级兜底，全部实测过）。
	 *
	 * <ol>
	 *   <li>{@code neteaseMetaUrlTemplate}（默认 {@code /api/song/detail/?id=X&ids=[X]}）→ 实测可取到歌名+歌手</li>
	 *   <li>同一接口的另一种参数形态 {@code /api/song/detail?ids=[X]} → 实测同样可用</li>
	 *   <li>歌曲网页的 {@code og:title}（注意：页面 {@code <title>} 实测是<b>空的</b>，
	 *       因为整页是 JS 渲染的；能用的只有 og: 分享标签）</li>
	 * </ol>
	 * 三级都失败就返回 null，由调用方用兜底标题 —— <b>元数据失败绝不影响播放</b>。
	 */
	private static CompletableFuture<String> fetchMeta(String songId, CloudDiscConfig cfg) {
		String tpl = cfg.neteaseMetaUrlTemplate;
		CompletableFuture<String> viaJson = (tpl == null || tpl.isBlank())
				? CompletableFuture.completedFuture(null)
				: TrackFetcher.getText(tpl.replace("{id}", songId), TrackFetcher.Trust.SELF, META_TIMEOUT_MS)
						.thenApply(NeteaseLinkProvider::parseJsonMeta);
		CompletableFuture<String> viaJsonAlt = viaJson.thenCompose(v -> v != null
				? CompletableFuture.completedFuture(v)
				: TrackFetcher.getText("https://music.163.com/api/song/detail?ids=[" + songId + "]",
						TrackFetcher.Trust.SELF, META_TIMEOUT_MS).thenApply(NeteaseLinkProvider::parseJsonMeta));
		return viaJsonAlt.thenCompose(v -> v != null
				? CompletableFuture.completedFuture(v)
				: TrackFetcher.getText("https://music.163.com/song?id=" + songId,
						TrackFetcher.Trust.SELF, META_TIMEOUT_MS).thenApply(NeteaseLinkProvider::parseHtmlMeta));
	}

	/** 解析 {@code {"songs":[{"name":"…","artists":[{"name":"…"}]}]}}。 */
	private static String parseJsonMeta(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			JsonArray songs = root.getAsJsonArray("songs");
			if (songs == null || songs.isEmpty()) {
				return null;
			}
			JsonObject first = songs.get(0).getAsJsonObject();
			String name = first.has("name") ? first.get("name").getAsString() : null;
			String artist = null;
			JsonArray artists = first.getAsJsonArray("artists");
			if (artists != null && !artists.isEmpty()) {
				JsonElement a = artists.get(0);
				if (a.isJsonObject() && a.getAsJsonObject().has("name")) {
					artist = a.getAsJsonObject().get("name").getAsString();
				}
			}
			return joinNameArtist(name, artist);
		} catch (Throwable t) {
			return null;
		}
	}

	/** 解析网页的 {@code og:title} / {@code og:description}（后者里含"由 … 演唱"）。 */
	private static String parseHtmlMeta(String body) {
		if (body == null) {
			return null;
		}
		Matcher title = OG_TITLE.matcher(body);
		if (!title.find()) {
			return null;
		}
		String name = unescape(title.group(1).trim());
		if (name.isEmpty()) {
			return null;
		}
		Matcher desc = OG_DESC.matcher(body);
		if (desc.find()) {
			Matcher artist = BY_ARTIST.matcher(unescape(desc.group(1)));
			if (artist.find()) {
				String a = artist.group(1).trim();
				if (!a.isEmpty() && a.length() <= 80) {
					return name + " - " + a;
				}
			}
		}
		return name;
	}

	private static String joinNameArtist(String name, String artist) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String n = unescape(name.trim());
		if (artist == null || artist.isBlank()) {
			return n;
		}
		return n + " - " + unescape(artist.trim());
	}

	private static String unescape(String s) {
		return ENTITY.matcher(s).replaceAll(m -> switch (m.group(1)) {
			case "amp" -> "&";
			case "lt" -> "<";
			case "gt" -> ">";
			case "quot" -> "\"";
			default -> "'";
		});
	}

	/** 从分享链接 / 分享文本 / 纯数字 / "数字+备注" 里提取歌曲 id。 */
	public static String extractSongId(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim();
		Matcher bare = BARE_ID.matcher(s);
		if (bare.matches()) {
			return bare.group(1);
		}
		Matcher param = ID_PARAM.matcher(s);
		if (param.find()) {
			return param.group(1);
		}
		Matcher path = SONG_PATH.matcher(s);
		if (path.find()) {
			return path.group(1);
		}
		// "数字 + 备注"：@186016（晴天） / @186016 晴天 / @186016-晴天 都能认
		Matcher leading = LEADING_ID.matcher(s);
		if (leading.find()) {
			return leading.group(1);
		}
		return null;
	}

	/**
	 * 用于"唱片名里没写音源"时自动识别：
	 * 明显是网易云链接、或者"以数字开头"（= 歌曲 id，可能后面跟了备注）就交给它。
	 */
	public static boolean looksLikeNetease(String raw) {
		if (raw == null) {
			return false;
		}
		String s = raw.toLowerCase(Locale.ROOT);
		if (s.contains("music.163.com") || s.contains("y.music.163") || s.contains("163cn.tv")) {
			return true;
		}
		return LEADING_ID.matcher(s.trim()).find();
	}
}
