package dev.clouddisc.disc;

import net.minecraft.text.Text;

/**
 * 唱片"名字即协议"。
 *
 * <p>为什么必须把曲目信息编码进<b>可见名字</b>而不是 NBT：
 * 铁砧改名是服务端执行的，{@code display.Name} 会跟着物品走（箱子、掉落物、跨玩家）；
 * 而纯客户端 Mod 无法写服务端权威 NBT。
 *
 * <p>名称格式（铁砧输入框上限 50 字符，够用）：
 * <pre>
 *   @&lt;provider&gt;:&lt;trackId&gt;      例：@netease:186016       或   @local:晴天.ogg
 *   @&lt;query&gt;                    例：@晴天                 → 用默认 provider 当搜索词解析
 * </pre>
 */
public final class DiscName {
	private DiscName() {
	}

	/** 解析结果：provider 可为 null（表示用默认 provider）。 */
	public record SongQuery(String provider, String trackId) {
		public String display() {
			return provider == null ? trackId : provider + ":" + trackId;
		}
	}

	/** 是否为 CloudDisc 唱片。 */
	public static boolean isCloudDisc(Text name, String prefix) {
		return parse(name, prefix) != null;
	}

	public static SongQuery parse(Text name, String prefix) {
		if (name == null) {
			return null;
		}
		String raw = name.getString().trim();
		if (prefix == null || prefix.isEmpty() || !raw.startsWith(prefix)) {
			return null;
		}
		String body = raw.substring(prefix.length()).trim();
		if (body.isEmpty()) {
			return null;
		}
		// 直接粘链接的情况：@https://music.163.com/song?id=186016
		// （URL 里自带 ':'，不能按 ':' 切 provider）
		String lower = body.toLowerCase(java.util.Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
			return new SongQuery(null, body);
		}
		int idx = body.indexOf(':');
		if (idx > 0 && idx < body.length() - 1) {
			String maybeProvider = body.substring(0, idx).trim();
			// 只有"冒号前面确实是一个已注册的音源名"才当成 <音源>:<曲目>。
			// 否则把这个冒号当成备注的一部分（例如 @186016（晴天: 现场版）），整串交给音源去解析。
			if (dev.clouddisc.music.Providers.byId(maybeProvider) != null) {
				String trackId = body.substring(idx + 1).trim();
				if (!trackId.isEmpty()) {
					return new SongQuery(maybeProvider, trackId);
				}
			}
			return new SongQuery(null, body);
		}
		// 没写音源，整串当曲目 key（例如 @186016、@186016（晴天））
		return new SongQuery(null, body);
	}

	/** 生成写进铁砧的名字（界面里也可以直接手打）。 */
	public static String encode(String prefix, String provider, String trackId) {
		String p = (prefix == null || prefix.isEmpty()) ? "@" : prefix;
		if (provider == null || provider.isEmpty()) {
			return p + trackId;
		}
		return p + provider + ":" + trackId;
	}

	/**
	 * 把任意 query 规范成一个"能在网络上唯一标识这次播放"的 key。
	 * 说明：真正解析出 URL 只在发起方做，解析结果随会话消息广播，
	 * 因此其它玩家不需要各自配置音源。
	 */
	public static String sessionKey(String provider, String trackId) {
		return (provider == null ? "" : provider) + "|" + trackId;
	}

	/**
	 * 从曲目 key 里取出"备注"部分。
	 *
	 * <p>约定：**开头那串数字是曲目 id，其余都是给人看的备注**。
	 * 例：{@code 186016（晴天）} → {@code 晴天}；{@code 186016 周杰伦} → {@code 周杰伦}；
	 * {@code 186016-我实测能播的} → {@code 我实测能播的}；{@code 186016} → {@code null}。
	 *
	 * <p>备注不只是好看：它会成为**游戏内 HUD 的"正在播放"曲名**，并随会话广播给同伴。
	 */
	public static String annotation(String trackKey) {
		if (trackKey == null) {
			return null;
		}
		String s = trackKey.trim();
		int i = 0;
		while (i < s.length() && Character.isDigit(s.charAt(i))) {
			i++;
		}
		if (i == 0 || i >= s.length()) {
			// 没有数字前缀（不是"id + 备注"形态），或数字后面什么都没有
			return null;
		}
		String rest = stripTrailing(stripLeading(s.substring(i).trim()));
		return rest.isEmpty() ? null : rest;
	}

	private static String stripLeading(String s) {
		String r = s;
		while (!r.isEmpty()) {
			char c = r.charAt(0);
			if (c == '（' || c == '(' || c == '[' || c == '【' || c == '-' || c == '—' || c == '·' || c == ':' || c == '：') {
				r = r.substring(1).trim();
			} else {
				break;
			}
		}
		return r;
	}

	private static String stripTrailing(String s) {
		String r = s;
		while (!r.isEmpty()) {
			char c = r.charAt(r.length() - 1);
			if (c == '）' || c == ')' || c == ']' || c == '】' || c == '-' || c == '—') {
				r = r.substring(0, r.length() - 1).trim();
			} else {
				break;
			}
		}
		return r;
	}
}
