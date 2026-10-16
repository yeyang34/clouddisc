package dev.clouddisc.sync;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.jukebox.JukeboxSession;
import dev.clouddisc.jukebox.PlaybackController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 同步服务：把 {@link PlaybackController}（本地播放）和 {@link Transport}（网络）接起来。
 *
 * <p>也是 Mixin / Fabric 事件的落点。
 */
public final class SyncService implements PlaybackController.Announcer {
	private final CloudDiscConfig cfg;
	private final PlaybackController playback;
	private final java.util.List<Transport> transports = new ArrayList<>();
	/** UDP 主通道的引用：用来判断"要不要动用聊天兜底"。 */
	private Transport udpTransport;
	/** 服务端中继通道的引用（可选组件，未安装时为 null）。 */
	private ServerRelayTransport relayTransport;
	private final String selfId = UUID.randomUUID().toString();
	private final Deque<Integer> recentOrder = new ArrayDeque<>();
	private final Set<Integer> recentSet = new HashSet<>();

	public SyncService(CloudDiscConfig cfg) {
		this.cfg = cfg;
		this.playback = new PlaybackController(cfg, this);
	}

	public void init() {
		java.util.List<String> manual = cfg.udpPeers == null
				? java.util.List.of()
				: java.util.Arrays.asList(cfg.udpPeers);
		UdpTransport udp = new UdpTransport(cfg.udpPort, cfg.enableLanDiscovery, manual);
		udp.start(this::onMessage);
		transports.add(udp);
		udpTransport = udp;
		if (cfg.enableServerRelay) {
			// 服务端中继：装了 CloudDisc 服务端组件的服务器会自动应答，没装则本通道静默不可用。
			ServerRelayTransport relay = new ServerRelayTransport();
			relay.start(this::onMessage);
			transports.add(relay);
			relayTransport = relay;
		}
		if (cfg.enableChatRelay) {
			ChatTransport chat = new ChatTransport();
			chat.start(this::onMessage);
			transports.add(chat);
		}
		CloudDiscClient.LOGGER.info("[CloudDisc] 同步层已就绪，通道: {}", transports.stream().map(Transport::name).toList());
	}

	public PlaybackController playback() {
		return playback;
	}

	// ------------------------------------------------------------ 事件入口

	/** 来自 {@code ClientWorldMixin}：原版世界事件 1010/1011。 */
	public PlaybackController.Decision onWorldEvent(ClientWorld world, BlockPos pos, int eventId, int data) {
		// 注意：Yarn 的 World#getTime() == Mojang 的 getGameTime()（"不可修改/冻结"的单调刻）；
		// 千万不要用 getTimeOfDay()（会被 /time 改、会被 doDaylightCycle 冻住）。
		return playback.onWorldEvent(world.getRegistryKey().getValue(), pos, eventId, data, world.getTime());
	}

	/** 来自 {@code UseBlockCallback}：本地玩家右键方块。 */
	public ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!world.isClient() || hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (!world.getBlockState(hit.getBlockPos()).isOf(Blocks.JUKEBOX)) {
			return ActionResult.PASS;
		}
		ItemStack stack = player.getStackInHand(hand);
		if (stack.isEmpty()) {
			return ActionResult.PASS;
		}
		playback.noteLocalUse(world.getRegistryKey().getValue(), hit.getBlockPos(), stack.getName());
		return ActionResult.PASS;
	}

	public void tick(MinecraftClient mc) {
		if (mc.world == null) {
			return;
		}
		long now = mc.world.getTime();
		// 中途加入时的主动询问：**问到有结果为止**（每 2 秒一次，最多 30 秒）
		//
		// 改成这样的原因（实测偶现："退出世界再回来有时不播放，时好时坏"）：
		// 老的写法是进服后固定问 3 次（1/3/10 秒）。如果这 3 次恰好都撞上
		// "此刻还没发现同伴"（消息被丢弃）或应答丢失，就**再也不会重试** → 静音。
		// 现在只要还没有会话就一直问下去，直到 30 秒为止。
		if (!queryDone && mc.world != null && now >= nextQueryTick) {
			if (playback.sessionCount() > 0) {
				queryDone = true;
				CloudDiscClient.LOGGER.info("[CloudDisc] 询问已有结果（本机已建立会话）");
			} else if (now - joinTick > 600L) {
				queryDone = true;
				CloudDiscClient.LOGGER.info("[CloudDisc] 询问 30 秒仍无人应答：场上可能没人持有会话（或对端不可达）");
			} else {
				nextQueryTick = now + 40L;
				sendQueryAll(now);
			}
		}
		// 中继报到与询问一起重试（服务器刚启动/玩家刚进来时，第一次报到可能过早）
		// 周期性向服务端中继续报 —— **无论当前是否"就绪"**。
		// 实测根因：客户端过去只在"未就绪"时才补报，而"就绪"取决于"最近收到过应答"；
		// 应答一过期就会出现一段"没有任何通道"的窗口（日志里 relay=0, chat=0），
		// 恰好在这段窗口里放碟 → PLAY 发不出去 → 跟随方回落原版（表现为"时好时坏"）。
		// 现在每 20 秒续一次（远小于应答有效期），通道基本恒为可用。
		if (relayTransport != null && mc.world != null) {
			relayHelloCooldown--;
			if (relayHelloCooldown <= 0) {
				relayHelloCooldown = 400;   // 20 秒
				relayTransport.sayHello();
			}
		}
		playback.tick(now);
	}

	private int relayHelloCooldown = 60;

	public void onJoin() {
		// 中途加入：原版不会给新来的玩家重发 1010 事件，所以必须自己主动问一轮。
		// 第一次问可能还没有发现同伴（组播发现要约 2 秒），所以在 +1s / +3s / +10s 各问一次。
		MinecraftClient mc = MinecraftClient.getInstance();
		long now = mc.world == null ? 0L : mc.world.getTime();
		joinTick = now;
		queryDone = false;
		nextQueryTick = now + 20L;
		// 顺便向服务端中继报到一次（没装组件的服务器不会有任何反应）
		if (relayTransport != null) {
			relayTransport.resetAttempts();
			relayTransport.sayHello();
		}
		CloudDiscClient.LOGGER.info("[CloudDisc] 已加入世界，将主动询问一轮『谁在放什么』");
	}

	private long joinTick;
	private boolean queryDone;
	private long nextQueryTick;

	/** 广播"谁在放什么？"（全量询问）。 */
	private void sendQueryAll(long nowTick) {
		Protocol.Msg m = Protocol.Msg.of(Protocol.T_QUERY_ALL);
		m.s = selfId;
		m.sentTick = nowTick;
		send(m);
	}

	public void onDisconnect() {
		playback.shutdown();
		if (relayTransport != null) {
			relayTransport.close();
		}
	}

	// --------------------------------------------------------- Announcer 实现

	@Override
	public void announceClaim(JukeboxSession s, long nowTick) {
		send(base(Protocol.T_CLAIM, s, nowTick));
	}

	@Override
	public void announcePlay(JukeboxSession s, long nowTick) {
		send(full(base(Protocol.T_PLAY, s, nowTick), s));
	}

	@Override
	public void announceStop(JukeboxSession s, long nowTick) {
		send(base(Protocol.T_STOP, s, nowTick));
	}

	@Override
	public void announceQuery(String dim, BlockPos pos, long nowTick) {
		Protocol.Msg m = Protocol.Msg.of(Protocol.T_QUERY);
		m.s = selfId;
		m.sentTick = nowTick;
		m.dim = dim;
		m.pos = pos.asLong();
		send(m);
	}

	@Override
	public void announceAnswer(JukeboxSession s, long nowTick) {
		// 关键：**还没排定开始刻就别回答**。
		// 实测踩过的坑：A 收到放碟事件后会先"解析+预取"，这段时间里会话已经在表里、
		// 但 localStartTick 还是 0；此时若回答了别人的询问，对方会收到 startTick=0，
		// 算出 -28440 刻这种离谱偏移，最后按"立刻开始"处理 → 比发起方早了近 2 秒。
		if (s.localStartTick <= 0L) {
			CloudDiscClient.LOGGER.info("[CloudDisc] 本会话还没排定开始刻（仍在预取），先不应答询问");
			return;
		}
		Protocol.Msg m = full(base(Protocol.T_ANSWER, s, nowTick), s);
		// 把"此刻的内容位置"一起带上。中途加入的人靠它定位，
		// 从而不必做跨客户端的游戏刻换算（刚进服时两边 gameTime 可能还没对齐）。
		if (s.pipeline != null && s.startedTick > 0L) {
			m.posMs = s.msAtStart + (nowTick - s.startedTick) * 50L;
		}
		send(m);
	}

	@Override
	public void announceHeartbeat(JukeboxSession s, long posMs, long nowTick) {
		Protocol.Msg m = base(Protocol.T_HEARTBEAT, s, nowTick);
		m.posMs = posMs;
		send(m);
	}

	@Override
	public boolean hasPeers() {
		for (Transport t : transports) {
			if (t.peerCount() > 0) {
				return true;
			}
		}
		return false;
	}

	/** 诊断用：各通道当前的对端数（排查"单人游戏里却显示可见同伴=true"这类幽灵对端）。 */
	@Override
	public String peerSummary() {
		StringBuilder sb = new StringBuilder();
		for (Transport t : transports) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(t.name()).append('=').append(t.peerCount());
		}
		return sb.toString();
	}

	@Override
	public boolean peersCarryUri() {
		for (Transport t : transports) {
			if (t.peerCount() > 0 && t.carriesUri()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 心跳间隔。
	 * <p>UDP 通道随便发（20 tick）；只有聊天通道时**必须拉长**：1.20.1 服务端的刷屏判定是
	 * 每条广播聊天 {@code chatSpamTickCount += 20}、每刻 −1、超过 200 且非 OP 就
	 * {@code disconnect("disconnect.spam")} —— 也就是同一窗口第 11 条就被踢。
	 */
	@Override
	public int heartbeatIntervalTicks() {
		return peersCarryUri() ? 20 : 100;
	}

	// ------------------------------------------------------------- 内部

	private Protocol.Msg base(String type, JukeboxSession s, long nowTick) {
		Protocol.Msg m = Protocol.Msg.of(type);
		m.s = selfId;
		m.sentTick = nowTick;
		m.dim = s.dim;
		m.pos = s.packedPos;
		m.epoch = s.epoch;
		m.prov = s.provider;
		m.track = s.trackId;
		return m;
	}

	private Protocol.Msg full(Protocol.Msg m, JukeboxSession s) {
		m.title = s.title;
		m.uri = s.uri;
		m.durMs = s.durationMs;
		m.startTick = s.localStartTick;
		return m;
	}

	private void send(Protocol.Msg m) {
		boolean udpHasPeers = udpTransport != null && udpTransport.peerCount() > 0;
		boolean relayReady = relayTransport != null && relayTransport.isReady();
		boolean otherChannelOk = udpHasPeers || relayReady;
		if (!otherChannelOk && cfg.enableChatRelay && !chatOnlyWarned) {
			chatOnlyWarned = true;
			CloudDiscClient.LOGGER.warn("[CloudDisc] UDP 看不到对端、服务器也没有中继组件 → 改用【聊天兜底通道】。"
					+ "这种模式依赖服务器转发聊天，因此在别人的服务器上：若聊天被插件改写/禁用、"
					+ "或 enforce-secure-profile 且本机无 profile key，就会同步失败。"
					+ "三个更稳的选择：① 让服主装本 Mod 的服务端组件（同一个 jar 放进服务端 mods/ 即可）；"
					+ "② 和朋友连同一个 VPN（ZeroTier/Tailscale 等）走 UDP 直连；③ 在配置里手写对端地址");
		}
		for (Transport t : transports) {
			// UDP 或中继能通就不用聊天通道：它会让没装 Mod 的玩家看到乱码行，还会进服务器日志。
			if (cfg.chatRelayOnlyWhenNoUdp && otherChannelOk && "chat".equals(t.name())) {
				continue;
			}
			try {
				t.send(m);
			} catch (Throwable e) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 通道 {} 发送失败: {}", t.name(), e.toString());
			}
		}
	}

	/** 只提示一次，避免刷日志。 */
	private boolean chatOnlyWarned;

	private void onMessage(Protocol.Msg m) {
		if (m == null || m.s == null || m.s.equals(selfId)) {
			return;
		}
		// 多通道/重传去重
		int hash = Objects.hash(m.s, m.t, m.epoch, m.pos, m.posMs, m.sentTick);
		synchronized (recentOrder) {
			if (!recentSet.add(hash)) {
				return;
			}
			recentOrder.addLast(hash);
			if (recentOrder.size() > 512) {
				recentSet.remove(recentOrder.removeFirst());
			}
		}
		// 通道线程 → 客户端线程
		MinecraftClient.getInstance().execute(() -> {
			ClientWorld world = MinecraftClient.getInstance().world;
			if (world == null) {
				return;
			}
			playback.onMessage(m, world.getTime());
		});
	}
}
