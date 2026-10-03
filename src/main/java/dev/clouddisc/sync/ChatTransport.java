package dev.clouddisc.sync;

import dev.clouddisc.CloudDiscClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.util.function.Consumer;

/**
 * 兜底通道：把消息藏进聊天消息里。
 *
 * <p>为什么需要：UDP 直连在"跨公网 + 双方都在 NAT 后"时打不通；此时只要服务器转发聊天，
 * 就能保证最小限度的同步（会话开始/结束/进度）。
 *
 * <p>代价（务必在设计文档里向玩家说明）：
 * <ul>
 *   <li>原版客户端会看到 {@code [CD1]...} 这样的乱码行；装 Mod 的客户端应当把它隐藏掉。</li>
 *   <li>服务端有聊天刷屏限流（1.20.1 约 20 条 / 200 tick），所以只能低频用。</li>
 *   <li>1.19+ 聊天签名：本 Mod 走未签名消息，服务器 {@code enforce-secure-profile=true} 时可能被拒。</li>
 *   <li>聊天长度上限 256，所以不携带 URI（对端需自己能解析同一个 key）。</li>
 * </ul>
 *
 * <p><b>TODO（需要实测确认）</b>：入站 hook 还没有接。1.20.1 的入站聊天有三个分支
 * （{@code ClientPlayNetworkHandler} 的 player chat / disguised chat / system chat），
 * 或者用 fabric-message-api-v1 的 {@code ClientReceiveMessageEvents.ALLOW_CHAT/ALLOW_GAME}
 * 统一拦截并取消渲染。选好之后调用 {@link #ingest(String)} 即可，其余逻辑不用改。
 */
public final class ChatTransport implements Transport {
	private static volatile ChatTransport INSTANCE;

	private volatile Consumer<Protocol.Msg> listener;
	/**
	 * 最近一次收到 CloudDisc 消息的<b>毫秒时间戳</b>；0 表示从未收到。
	 * <p>用毫秒 + 0 哨兵（而不是 nanoTime + Long.MIN_VALUE/2）是因为后者在相减时会溢出，
	 * 变成一个"永远小于 TTL"的负值 → 明明没有任何同伴却报告 peerCount=1。
	 */
	private static volatile long lastPeerMillis = 0L;
	private static final long PEER_TTL_MILLIS = 60_000L; // 60 秒

	// ---- 限流：1.20.1 服务端每条广播聊天让 chatSpamTickCount += 20、每刻 −1，
	//      超过 200 且非 OP 就 disconnect("disconnect.spam")，即同一窗口第 11 条被踢。
	//      这里按 0.75 条/秒 + 突发 3 条限速（10 秒窗口最多 ~7.5 条 = 150 计数），留足余量。
	private static final double RATE_PER_SEC = 0.75;
	private static final double BURST = 3.0;
	private final Object rateLock = new Object();
	private double tokens = BURST;
	private long lastRefillNanos = System.nanoTime();

	private boolean allowSend() {
		synchronized (rateLock) {
			long now = System.nanoTime();
			double refill = (now - lastRefillNanos) / 1_000_000_000.0 * RATE_PER_SEC;
			if (refill > 0.0) {
				tokens = Math.min(BURST, tokens + refill);
				lastRefillNanos = now;
			}
			if (tokens >= 1.0) {
				tokens -= 1.0;
				return true;
			}
			return false;
		}
	}

	@Override
	public String name() {
		return "chat";
	}

	@Override
	public void start(Consumer<Protocol.Msg> listener) {
		this.listener = listener;
		INSTANCE = this;
	}

	@Override
	public void send(Protocol.Msg msg) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientPlayNetworkHandler handler = mc.getNetworkHandler();
		if (handler == null || mc.player == null) {
			return;
		}
		String line = Protocol.packForChat(msg);
		if (line.length() > 250) {
			CloudDiscClient.LOGGER.debug("[CloudDisc] 聊天消息过长({})，跳过", line.length());
			return;
		}
		if (!allowSend()) {
			CloudDiscClient.LOGGER.debug("[CloudDisc] 聊天通道限流，丢弃一条 {}", msg.t);
			return;
		}
		// 走原版客户端的发送路径：它会用当前聊天签名会话签名（因此不是"未签名消息"）。
		// 例外：服务器 enforce-secure-profile=true（1.20.1 默认）且本机没有 profile key 时，
		// 客户端聊天会被服务端丢弃并提示 chat.disabled.missingProfileKey —— 需要实测确认。
		mc.execute(() -> {
			ClientPlayNetworkHandler h = mc.getNetworkHandler();
			if (h != null) {
				h.sendChatMessage(line);
			}
		});
	}

	@Override
	public int peerCount() {
		// 关键语义：必须"真的收到过装了 Mod 的同伴的消息"才算有对端。
		// 绝不能按"连着服务器"来算 —— 单人游戏里 getNetworkHandler() 也非空，
		// 那样会让每一张普通唱片都被误判成"可能有同伴"，白白静音等 300ms。
		long last = lastPeerMillis;
		return (last != 0L && System.currentTimeMillis() - last < PEER_TTL_MILLIS) ? 1 : 0;
	}

	@Override
	public boolean carriesUri() {
		return false;
	}

	@Override
	public boolean isReady() {
		return MinecraftClient.getInstance().player != null;
	}

	/**
	 * 入站 hook 调用点。
	 *
	 * @return true 表示这行是本 Mod 的数据，调用方应把它从聊天界面隐藏掉
	 */
	public static boolean ingest(String text) {
		ChatTransport t = INSTANCE;
		Consumer<Protocol.Msg> l = t == null ? null : t.listener;
		if (l == null) {
			return false;
		}
		Protocol.Msg msg = Protocol.unpackFromChat(text);
		if (msg == null) {
			return false;
		}
		lastPeerMillis = System.currentTimeMillis();
		l.accept(msg);
		return true;
	}

	@Override
	public void close() {
		INSTANCE = null;
	}
}
