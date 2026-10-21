package dev.clouddisc.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云歌词（带时间轴），给"屏幕下方状态栏跟唱"用。
 *
 * <h2>为什么单独一个类</h2>
 * 选歌界面那边只要"纯文本歌词"（把 {@code [00:12.34]} 时间戳全剥掉就行）；
 * 而状态栏跟唱必须知道<b>每一句从第几毫秒开始</b>。需求不同，所以这里做一份带时间轴的，
 * 选歌界面那份保持原样（不冒险动它）。
 *
 * <p>接口是明文的（实测，无需签名也无需 cookie），直接 GET 即可 ✓
 *
 * <p>行为约定：
 * <ul>
 *   <li>{@link #cachedOrFetch} 命中缓存立刻返回；没有就<b>返回 null 并在后台拉一次</b>（不阻塞 tick）</li>
 *   <li>任何异常都返回 null / 空列表 —— 歌词失败绝不影响播放 ✓</li>
 * </ul>
 */
public final class Lyrics {

	/** 一句歌词。 */
	public record Line(long atMs, String text) {
	}

	/**
	 * 一首歌的两条轨。
	 * <p>接口同时返回 {@code lrc}（原文）与 {@code tlyric}（翻译，外语歌才有）。
	 * 两轨<b>各有自己的时间戳</b>，所以必须分别解析、分别查句 —— 不能拿原文的索引去套翻译 ✗
	 */
	public record Both(List<Line> orig, List<Line> trans) {
		public static final Both EMPTY = new Both(List.of(), List.of());
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	/** {@code [mm:ss.xx]} / {@code [mm:ss:xx]} / {@code [mm:ss]} 都认。 */
	private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

	/**
	 * 内存里最多留这么多首歌词（一首约 10~50KB）；超了按"最久没用到"淘汰。
	 * <p>【为什么必须设上限】原来是无上限的 ConcurrentHashMap —— 开着歌词听一晚上，
	 * 每首歌的整份歌词都会永久留在内存里（100 首 ≈ 几 MB，长会话会一直涨）。
	 */
	private static final int CACHE_MAX = 24;

	private static final Map<Long, Both> CACHE =
			java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, Both>(32, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Both> eldest) {
					return size() > CACHE_MAX;
				}
			});

	/** 已经拉过（成功或失败都算），避免对同一首反复重试；也有上限，免得无限涨。 */
	private static final Set<Long> TRIED = ConcurrentHashMap.newKeySet();
	private static final int TRIED_MAX = 256;

	private Lyrics() {
	}

	/**
	 * 取歌词：命中缓存直接返回；没有就触发一次后台拉取并返回 {@code null}。
	 * <p>调用方每 tick 调也不怕 —— 拉取有去重（{@link #TRIED}）✓
	 */
	public static Both bothOrFetch(long id) {
		if (id <= 0L) {
			return null;
		}
		Both hit = CACHE.get(id);
		if (hit != null) {
			return hit;
		}
		if (TRIED.size() > TRIED_MAX) {
			TRIED.clear(); // 只是"别重复请求"的备忘录，清掉最多多请求一次，不心疼
		}
		if (TRIED.add(id)) {
			Thread t = new Thread(() -> {
				Both v = fetch(id);
				CACHE.put(id, v == null ? Both.EMPTY : v);
			}, "CloudDisc-Lyrics");
			t.setDaemon(true);
			t.start();
		}
		return null;
	}

	/** 当前时刻应该显示哪一句（二分；没有就返回 null）。 */
	public static String lineAt(List<Line> lines, long ms) {
		if (lines == null || lines.isEmpty()) {
			return null;
		}
		int lo = 0;
		int hi = lines.size() - 1;
		int found = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).atMs() <= ms) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		if (found < 0) {
			return null;
		}
		// 空行（间奏）继续往前找最近一句有字的
		for (int i = found; i >= 0 && i > found - 4; i--) {
			String t = lines.get(i).text();
			if (t != null && !t.isBlank()) {
				return t;
			}
		}
		return null;
	}

	/** 同步拉取（在后台线程里跑）。失败返回 {@link Both#EMPTY}。 */
	private static Both fetch(long id) {
		try {
			HttpRequest req = HttpRequest.newBuilder(
							URI.create("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1"))
					.timeout(Duration.ofSeconds(8))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
					.header("Referer", "https://music.163.com/")
					.GET().build();
			HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (resp.statusCode() / 100 != 2) {
				return Both.EMPTY;
			}
			JsonObject o = JsonParser.parseString(new String(resp.body(), StandardCharsets.UTF_8)).getAsJsonObject();
			return new Both(parse(trackText(o, "lrc")), parse(trackText(o, "tlyric")));
		} catch (Throwable t) {
			return Both.EMPTY;
		}
	}

	/** 从响应里取某条轨的原始 LRC 文本（{@code lrc} 原文 / {@code tlyric} 翻译）。 */
	private static String trackText(JsonObject o, String key) {
		if (!o.has(key) || !o.get(key).isJsonObject()) {
			return "";
		}
		JsonObject tr = o.getAsJsonObject(key);
		if (!tr.has("lyric") || tr.get("lyric").isJsonNull()) {
			return "";
		}
		return tr.get("lyric").getAsString();
	}

	/** 解析 LRC。一行可能有多个时间标签（{@code [00:10.00][01:20.00]副歌}）⇒ 拆成多条。 */
	static List<Line> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		List<Line> out = new ArrayList<>();
		for (String row : raw.split("\n")) {
			if (row == null || row.isBlank()) {
				continue;
			}
			Matcher m = TAG.matcher(row);
			List<Long> stamps = new ArrayList<>(2);
			int lastEnd = 0;
			while (m.find()) {
				// 只认"行首连续的时间标签"，正文里出现的方括号不算
				if (m.start() != lastEnd) {
					break;
				}
				lastEnd = m.end();
				try {
					long min = Long.parseLong(m.group(1));
					long sec = Long.parseLong(m.group(2));
					long frac = 0L;
					String f = m.group(3);
					if (f != null) {
						// 两位 = 百分秒，三位 = 毫秒
						frac = Long.parseLong(f);
						frac = (f.length() == 1) ? frac * 100 : (f.length() == 2 ? frac * 10 : frac);
					}
					stamps.add(min * 60_000L + sec * 1000L + frac);
				} catch (NumberFormatException ignored) {
					// [ti:] / [ar:] 这类元信息标签解析会失败，跳过就好
				}
			}
			if (stamps.isEmpty()) {
				continue;
			}
			String text = row.substring(lastEnd).trim();
			for (long at : stamps) {
				out.add(new Line(at, text));
			}
			if (out.size() > 2000) {
				break;
			}
		}
		Collections.sort(out, (a, b) -> Long.compare(a.atMs(), b.atMs()));
		return out;
	}
}
