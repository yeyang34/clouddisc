package dev.clouddisc.audio;

import dev.clouddisc.CloudDiscClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 取音频数据：本地文件 或 http(s) 直链，落到缓存目录后再解码。
 *
 * <p>取舍：先落盘再解码，换来"样本级 seek"（同步纠偏与中途加入都必须能 seek）。
 * 边下边播 + seek 需要 stb_vorbis pushdata 或 HTTP Range，放到 v2。
 *
 * <p><b>安全</b>：URI 可能来自别的玩家（会话消息里带 URL），所以这里做了 SSRF 防护：
 * 只允许 http/https、拒绝私网/环回/链路本地地址、限制体积与超时。
 * 注意这挡不住"域名解析到私网"，域名白名单请用配置项进一步收窄。
 */
public final class TrackFetcher {
	private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "CloudDisc-Fetch");
		t.setDaemon(true);
		return t;
	});
	/**
	 * 注意这里是 {@code Redirect.ALWAYS}，不是默认的 {@code NORMAL}。
	 * <p>实测过的坑：网易云的外链会 302 到 <b>http</b> 的 CDN 地址
	 * （{@code http://m801.music.126.net/...mp3}）。而 {@code NORMAL} 的语义是
	 * "除了 https→http 之外都跟随" —— 于是 curl 能拿到音频、Java 却会停在 302 上。
	 * 音频不是机密数据，跟随降级重定向是这里正确的选择。
	 */
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.connectTimeout(Duration.ofSeconds(15))
			.executor(EXEC)
			.build();

	private TrackFetcher() {
	}

	/**
	 * 来源可信度。区分这个很重要，否则自建在本机/内网的音源服务根本用不了。
	 *
	 * <ul>
	 *   <li>{@link #SELF}：URI 由<b>本机自己的 provider</b> 解析出来的（例如你自己配的
	 *       {@code neteaseEndpoint}，或者你自己搭在 {@code 127.0.0.1} 上的服务）→ 只校验协议。</li>
	 *   <li>{@link #PEER_BLOCK_PRIVATE}：URI 来自<b>别的玩家的会话消息</b>，且不允许私网
	 *       → 环回/链路本地/私网/组播全部拒绝（防 SSRF）。</li>
	 *   <li>{@link #PEER_ALLOW_PRIVATE}：URI 来自别的玩家，但允许私网（默认）
	 *       → 为了"同一局域网里的 Navidrome/Jellyfin 也能被同伴播放"这个真实需求；
	 *       仍然拒绝环回、链路本地、云元数据地址。想更严就改用上面那个。</li>
	 * </ul>
	 */
	public enum Trust {
		SELF,
		PEER_BLOCK_PRIVATE,
		PEER_ALLOW_PRIVATE
	}

	/**
	 * 缓存总量上限：按"最久未使用"删除，直到降到上限的 80% 以下。
	 *
	 * <p><b>缓存放在每个玩家自己的机器上</b>（{@code 游戏目录/config/clouddisc/cache}），
	 * 不占服务器硬盘；这个上限是防止把玩家的磁盘慢慢吃满。
	 * 顺手清掉上次异常退出留下的 {@code .part} 临时文件。
	 */
	public static void pruneCache(Path cacheDir, int maxTotalMb) {
		if (maxTotalMb <= 0) {
			return;
		}
		try {
			long limit = maxTotalMb * 1024L * 1024L;
			java.util.List<Path> files = new java.util.ArrayList<>();
			long total = 0L;
			try (java.util.stream.Stream<Path> stream = Files.list(cacheDir)) {
				for (Path p : stream.toList()) {
					if (!Files.isRegularFile(p)) {
						continue;
					}
					if (p.getFileName().toString().endsWith(".part")) {
						Files.deleteIfExists(p); // 异常退出留下的临时文件
						continue;
					}
					files.add(p);
					total += Files.size(p);
				}
			}
			if (total <= limit) {
				return;
			}
			files.sort(java.util.Comparator.comparingLong(TrackFetcher::lastModifiedSafe));
			long target = (long) (limit * 0.8);
			int removed = 0;
			for (Path p : files) {
				if (total <= target) {
					break;
				}
				long size = Files.size(p);
				if (Files.deleteIfExists(p)) {
					total -= size;
					removed++;
				}
			}
			CloudDiscClient.LOGGER.info("[CloudDisc] 缓存清理：删除 {} 个文件，剩余约 {} MB（上限 {} MB，只占本机）",
					removed, total / 1024L / 1024L, maxTotalMb);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.debug("[CloudDisc] 缓存清理跳过: {}", t.toString());
		}
	}

	private static long lastModifiedSafe(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (Exception e) {
			return 0L;
		}
	}

	/**
	 * 取一小段文本（用于查"歌曲元数据"这类小 JSON / 小网页）。
	 *
	 * <p><b>失败一律返回 null</b>：元数据只是锦上添花（显示歌名），
	 * 绝不能因为它超时或格式变了就影响播放。
	 * <p>走的还是本类那个 HttpClient：同一套重定向策略、同一套 {@link Trust} 校验。
	 */
	public static java.util.concurrent.CompletableFuture<String> getText(String uri, Trust trust, long timeoutMs) {
		try {
			java.net.URI target = java.net.URI.create(uri);
			String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(java.util.Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}
			if (target.getHost() == null || target.getHost().isEmpty()) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}
			if (trust != Trust.SELF) {
				String blocked = blockedReason(target.getHost(), trust == Trust.PEER_BLOCK_PRIVATE);
				if (blocked != null) {
					return java.util.concurrent.CompletableFuture.completedFuture(null);
				}
			}
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(target)
					.timeout(Duration.ofMillis(Math.max(300L, timeoutMs)))
					.header("User-Agent", "Mozilla/5.0 (compatible; CloudDisc)")
					.header("Referer", "https://music.163.com/")
					.GET()
					.build();
			return HTTP.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8))
					.thenApply(r -> r.statusCode() / 100 == 2 ? r.body() : null)
					.exceptionally(e -> null);
		} catch (Throwable t) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}
	}

	/** 判断一个 URI 是否可能是本地文件（本地音源 provider 用）。 */
	public static Path asLocalPath(String uri) {		try {
			if (uri.startsWith("file:")) {
				return Path.of(URI.create(uri));
			}
			if (uri.contains("://")) {
				return null;
			}
			return Path.of(uri);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 取回音频文件，返回本地缓存路径。
	 *
	 * @param uri      本地路径 / file: / http(s):
	 * @param cacheDir 缓存目录
	 * @param maxMb    单曲体积上限
	 * @param trust    URI 来源可信度，见 {@link Trust}
	 */
	public static CompletableFuture<Path> fetch(String uri, Path cacheDir, int maxMb, String[] extraHeaders, Trust trust) {
		Path local = asLocalPath(uri);
		if (local != null) {
			if (Files.isRegularFile(local)) {
				return CompletableFuture.completedFuture(local);
			}
			return CompletableFuture.failedFuture(new IllegalArgumentException("文件不存在: " + local));
		}

		URI target;
		try {
			target = URI.create(uri);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("非法 URI: " + uri));
		}
		String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("不支持的协议: " + scheme));
		}
		if (trust != Trust.SELF) {
			String blocked = blockedReason(target.getHost(), trust == Trust.PEER_BLOCK_PRIVATE);
			if (blocked != null) {
				return CompletableFuture.failedFuture(new IllegalArgumentException(
						"出于安全考虑拒绝该地址（" + blocked + "）: " + target.getHost()
								+ "。如果这是你自己内网的音源服务，请在配置里调整 blockPeerPrivateUrls。"));
			}
		}

		String fileName = Integer.toHexString(uri.hashCode()) + guessExtension(uri);
		return CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(cacheDir);
				Path tmp = cacheDir.resolve(fileName + ".part");
				Path dst = cacheDir.resolve(fileName);
				if (Files.isRegularFile(dst) && Files.size(dst) > 0L) {
					return dst; // 已缓存
				}
				HttpRequest.Builder b = HttpRequest.newBuilder(target)
						.timeout(Duration.ofSeconds(60))
						.header("User-Agent", "CloudDisc/0.1 (Minecraft mod)");
				if (extraHeaders != null) {
					for (String h : extraHeaders) {
						int i = h.indexOf(':');
						if (i > 0) {
							String k = h.substring(0, i).trim();
							String v = h.substring(i + 1).trim();
							// 用 setHeader：否则配置里再写一个 User-Agent 会变成两条同名头
							if (k.equalsIgnoreCase("User-Agent")) {
								b.setHeader(k, v);
							} else {
								b.header(k, v);
							}
						}
					}
				}
				HttpResponse<Path> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofFile(tmp));
				int code = resp.statusCode();
				if (code / 100 != 2) {
					Files.deleteIfExists(tmp);
					throw new IllegalStateException("HTTP " + code + " " + target);
				}
				// 有些"看起来正常"的响应其实是网页：HTTP 200 + text/html。
				// 网易云对 VIP/独家/下架曲目就是跳到 /404 页面（实测 107 KB 的 HTML），
				// 如果不拦，后面只会报一个语焉不详的"解码失败"。
				String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
				if (contentType.contains("text/html") || contentType.contains("application/json")) {
					long pageSize = Files.size(tmp);
					Files.deleteIfExists(tmp);
					throw new IllegalStateException("拿到的是" + contentType + "（" + pageSize
							+ " 字节的网页/JSON），不是音频文件。"
							+ "最常见的两个原因：① 该曲目不允许匿名播放（VIP/独家/下架，网易会跳到 /404 页面）；"
							+ "② 这个链接是歌曲页面而不是音频直链。"
							+ "【想放这类歌，两条干净的路】"
							+ "A) 用你自己账号解析：让 neteaseEndpoint 指向你自己的小服务"
							+ "（用你的官方登录/你自己的 cookie 换地址，返回 {\"title\":\"…\",\"url\":\"…\"} 或直接回音频字节）；"
							+ "B) 直接把音频文件放进 clouddisc-music/ 并用 @local:文件名 —— 这条路完全不碰网易服务器。"
							+ "本 Mod 不内置任何账号/cookie，也不解密受保护格式（.ncm 等）。");
				}
				// 按 Content-Type 修正扩展名（只为可读性；解码器是按文件头魔数认格式的，不依赖它）
				String fixedName = Integer.toHexString(uri.hashCode())
						+ extensionForContentType(contentType, guessExtension(uri));
				Path fixed = cacheDir.resolve(fixedName);
				if (!fixed.equals(dst)) {
					dst = fixed;
					if (Files.isRegularFile(dst) && Files.size(dst) > 0L) {
						Files.deleteIfExists(tmp);
						return dst;
					}
				}
				long size = Files.size(tmp);
				if (size <= 0L) {
					Files.deleteIfExists(tmp);
					throw new IllegalStateException("空文件");
				}
				if (maxMb > 0 && size > maxMb * 1024L * 1024L) {
					Files.deleteIfExists(tmp);
					throw new IllegalStateException("超过体积上限 " + maxMb + "MB");
				}
				Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
				return dst;			} catch (Exception e) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 拉取音频失败 {}: {}", uri, e.toString());
				throw new RuntimeException(e);
			}
		}, EXEC);
	}

	private static String guessExtension(String path) {
		if (path == null) {
			return ".bin";
		}
		// 注意传进来的是完整 URI 字符串（不是 URI.getPath()）：
		// 网易云的 .mp3 在 query 里（?id=xxx.mp3），只看 path 会漏掉。
		String p = path.toLowerCase(java.util.Locale.ROOT);
		int q = p.indexOf('?');
		String noQuery = q >= 0 ? p.substring(0, q) : p;
		String query = q >= 0 ? p.substring(q) : "";
		for (String ext : new String[] {".ogg", ".oga", ".mp3", ".flac", ".wav", ".m4a", ".aac"}) {
			if (noQuery.endsWith(ext) || query.contains(ext)) {
				return ext;
			}
		}
		return ".bin";
	}

	/** 下载完成后按 Content-Type 再修正一次扩展名，让缓存目录一眼能看懂。 */
	private static String extensionForContentType(String contentType, String current) {
		if (contentType == null) {
			return current;
		}
		String ct = contentType.toLowerCase(java.util.Locale.ROOT);
		if (ct.contains("mpeg") && ct.contains("audio")) {
			return ".mp3";
		}
		if (ct.contains("ogg")) {
			return ".ogg";
		}
		if (ct.contains("wav") || ct.contains("wave") || ct.contains("x-pn-wav")) {
			return ".wav";
		}
		if (ct.contains("flac")) {
			return ".flac";
		}
		if (ct.contains("mp4") || ct.contains("m4a") || ct.contains("aac")) {
			return ".m4a";
		}
		return current;
	}

	/**
	 * @param blockPrivate 是否连私网（site-local，例如 192.168.x.x）也一并拒绝
	 * @return 被拒绝的原因；null 表示放行
	 */
	private static String blockedReason(String host, boolean blockPrivate) {
		if (host == null || host.isEmpty()) {
			return "地址为空";
		}
		String h = host.toLowerCase();
		if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.equals("[::1]")) {
			return "环回/本地域名";
		}
		if (h.equals("169.254.169.254") || h.equals("metadata.google.internal")) {
			return "云元数据地址";
		}
		try {
			InetAddress addr = InetAddress.getByName(host);
			if (addr.isLoopbackAddress()) {
				return "环回地址";
			}
			if (addr.isLinkLocalAddress()) {
				return "链路本地地址";
			}
			if (addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
				return "通配/组播地址";
			}
			if (blockPrivate && addr.isSiteLocalAddress()) {
				return "私网地址（当前配置不允许同伴使用私网音源）";
			}
			return null;
		} catch (Exception e) {
			return "域名无法解析";
		}
	}
}
