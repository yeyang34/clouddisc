package dev.clouddisc.music;

import dev.clouddisc.CloudDiscConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * B 站音源：把唱片名里的 {@code BV 号} 解析成一个<b>可播放的 mp3 地址</b>。
 *
 * <h2>为什么客户端只拿一个 URL 就行</h2>
 * B 站音频直链有三个坑，全都在解析服务那边解决掉了：
 * <ol>
 *   <li>不带 {@code Referer} 直接 403</li>
 *   <li>直链约 2 小时后过期</li>
 *   <li><b>只有 AAC（MP4 容器），而本 mod 只认 mp3/ogg/wav/flac</b></li>
 * </ol>
 * ⇒ 服务端负责"现场解析 + 转成 mp3 + 缓存"，客户端拿到的就是普通的 mp3 地址 ✓
 *
 * <h2>trackId 格式</h2>
 * <pre>
 *   BV1GJ411x7h7       第 1 个分P（单P视频就是它）
 *   BV1GJ411x7h7#p3    第 3 个分P
 * </pre>
 * 用 {@code #} 而不是 {@code :}：唱片名是 {@code @bili:BV...}，冒号已经被 provider 占了 ✗
 */
public final class BiliProvider implements MusicProvider {

	/**
	 * 唱片名里"没写音源"时的自动识别。
	 * <p>为什么用 {@code BV} 前缀而不是数字：网易云的识别规则是<b>以数字开头即为歌曲 id</b>
	 * （{@code LEADING_ID}），所以任何"纯数字"的 B 站标识都会跟网易云撞车 ✗。
	 * {@code BV} 开头的串在网易云那边永远不成立 ⇒ 天然不冲突 ✓
	 */
	private static final java.util.regex.Pattern BV_RE =
			java.util.regex.Pattern.compile("^BV[0-9A-Za-z]{10}(#.*)?$");

	public static boolean looksLikeBili(String raw) {
		if (raw == null) {
			return false;
		}
		return BV_RE.matcher(raw.trim()).matches();
	}

	@Override
	public String id() {
		return "bili";
	}

	@Override
	public CompletableFuture<ResolvedTrack> resolve(String trackId, CloudDiscConfig cfg) {
		String raw = trackId == null ? "" : trackId.trim();
		if (raw.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("B 站 trackId 为空"));
		}
		String base = base(cfg);
		if (base == null || base.isBlank()) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("没有可用的解析服务地址（检查 neteaseEndpoint / biliEndpoint）"));
		}
		String bvid = raw;
		int page = 1;
		int hash = raw.indexOf('#');
		if (hash > 0) {
			bvid = raw.substring(0, hash).trim();
			String rest = raw.substring(hash + 1).trim();
			if (rest.startsWith("p") || rest.startsWith("P")) {
				rest = rest.substring(1);
			}
			try {
				page = Math.max(1, Integer.parseInt(rest.trim()));
			} catch (NumberFormatException ignored) {
				page = 1;
			}
		}
		String url = base + "/bili?bvid=" + URLEncoder.encode(bvid, StandardCharsets.UTF_8) + "&p=" + page;
		return MusicJson.query(url, cfg, trackId, id());
	}

	/**
	 * 解析服务基址。
	 * <p>默认<b>复用网易云那台解析服务</b>（同一个 host、换个路由），这样不需要多配一份地址；
	 * 想单独部署时把 {@code biliEndpoint} 填上即可 ✓
	 */
	public static String base(CloudDiscConfig cfg) {
		if (cfg == null) {
			return null;
		}
		String explicit = cfg.biliEndpoint;
		if (explicit != null && !explicit.isBlank()) {
			return explicit.trim().replaceAll("/+$", "");
		}
		return NeteaseSearch.searchBase(cfg);
	}
}
