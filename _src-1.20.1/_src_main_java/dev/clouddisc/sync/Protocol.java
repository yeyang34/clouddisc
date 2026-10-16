package dev.clouddisc.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 玩家间同步协议（v1）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>曲目身份由发起方权威决定</b>：唱片名字里的 key 只在发起方解析成真实 URI，
 *       解析结果随会话消息广播；其它玩家不需要各自配置音源。</li>
 *   <li><b>时间基准用"发送方 gameTime"</b>：每条消息都带 {@code sentTick}，
 *       接收方用 {@code 本地gameTime - sentTick} 得到偏移，无需单独的时钟同步协议。</li>
 * </ul>
 */
public final class Protocol {
	public static final int VERSION = 1;
	/**
	 * 聊天兜底通道的标记前缀。
	 * <p>没装本 Mod 的玩家会照原样看到这一行，所以前缀要"自解释"，别用 {@code [CD1]} 这种天书。
	 * 载荷还会先 gzip 再 base64url，尽量短。
	 */
	public static final String CHAT_MAGIC = "[CloudDisc]";

	public static final String T_PLAY = "PLAY";
	public static final String T_STOP = "STOP";
	public static final String T_QUERY = "Q";
	public static final String T_ANSWER = "A";
	/**
	 * "谁在放什么？"的全量询问（不针对某个坐标）。
	 * <p>用途：<b>中途加入</b>——原版不会给新来的玩家重发 1010 事件，
	 * 所以必须由新来的人主动问一轮，会话持有者才会回 {@code A}。
	 */
	public static final String T_QUERY_ALL = "QA";
	public static final String T_HEARTBEAT = "HB";
	/**
	 * 发起方在"右键把 CloudDisc 唱片放进唱片机"的瞬间抢先广播的占位消息。
	 * 作用是让同伴先把原版唱片声静音、把等待窗口拉长，等真正的 PLAY（含解析结果）到达。
	 */
	public static final String T_CLAIM = "CLAIM";

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private Protocol() {
	}

	public static final class Msg {
		public int v = VERSION;
		public String t;
		public String s;          // 发送者标识（用于排查）
		public long sentTick;     // 发送方本地 gameTime
		public String dim;        // 维度 id
		public long pos;          // BlockPos.asLong()
		public String epoch;      // 会话唯一 id
		public String prov;       // provider id
		public String track;      // 唱片名里的 key
		public String title;
		public String uri;        // 仅 PLAY/ANSWER 携带；聊天通道会剥掉
		public long startTick;    // 发起方 gameTime 语义下的"开始刻"（含 lead）
		public long durMs;
		public long posMs;        // HB：发出时本曲已播到多少毫秒

		public static Msg of(String type) {
			Msg m = new Msg();
			m.t = type;
			return m;
		}

		/** 聊天通道用：去掉可能超长的 uri。 */
		public Msg withoutUri() {
			Msg c = new Msg();
			c.v = v;
			c.t = t;
			c.s = s;
			c.sentTick = sentTick;
			c.dim = dim;
			c.pos = pos;
			c.epoch = epoch;
			c.prov = prov;
			c.track = track;
			c.title = title;
			c.startTick = startTick;
			c.durMs = durMs;
			c.posMs = posMs;
			return c;
		}
	}

	public static String toJson(Msg m) {
		return GSON.toJson(m);
	}

	public static Msg fromJson(String json) {
		try {
			Msg m = GSON.fromJson(json, Msg.class);
			if (m == null || m.t == null || m.v != VERSION) {
				return null;
			}
			return m;
		} catch (Exception e) {
			return null;
		}
	}

	public static byte[] toBytes(Msg m) {
		return toJson(m).getBytes(StandardCharsets.UTF_8);
	}

	public static Msg fromBytes(byte[] data, int offset, int length) {
		return fromJson(new String(data, offset, length, StandardCharsets.UTF_8));
	}

	public static String packForChat(Msg m) {
		byte[] raw = toJson(m.withoutUri()).getBytes(StandardCharsets.UTF_8);
		byte[] packed = gzip(raw);
		String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
		return CHAT_MAGIC + b64;
	}

	/** gzip 一下再发：JSON 里重复的键名压缩率很高，聊天行能短掉一大半。 */
	private static byte[] gzip(byte[] data) {
		try {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length / 2 + 32);
			try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
				gz.write(data);
			}
			return out.toByteArray();
		} catch (Exception e) {
			return data; // 压缩失败就发原文（解包那边两种都认）
		}
	}

	private static byte[] gunzip(byte[] data) {
		if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
			try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
				return gz.readAllBytes();
			} catch (Exception e) {
				return null;
			}
		}
		return data; // 不是 gzip，就按原文处理
	}

	/**
	 * 从聊天行里提取本 Mod 的数据。
	 *
	 * <p><b>关键</b>：不能用 {@code startsWith}。消息在<b>接收端会被加上发送者前缀</b>
	 * （形如 {@code <Steve> [CD1]eyJ2Ijox...}），所以要在任意位置找标记，
	 * 再往后取一段连续的 base64url 字符作为载荷。
	 */
	public static Msg unpackFromChat(String text) {
		if (text == null) {
			return null;
		}
		int i = text.indexOf(CHAT_MAGIC);
		if (i < 0) {
			return null;
		}
		int start = i + CHAT_MAGIC.length();
		int end = start;
		while (end < text.length() && isBase64UrlChar(text.charAt(end))) {
			end++;
		}
		if (end <= start) {
			return null;
		}
		try {
			byte[] raw = Base64.getUrlDecoder().decode(text.substring(start, end));
			byte[] plain = gunzip(raw);
			if (plain == null) {
				return null;
			}
			return fromJson(new String(plain, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isBase64UrlChar(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
	}

	public static String key(String dim, long pos) {
		return dim + "@" + pos;
	}
}
