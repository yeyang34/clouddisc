package dev.clouddisc.server;

import dev.clouddisc.net.CloudDiscNet;
import dev.clouddisc.net.CloudDiscRelayPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 服务端可选组件：把装了本 Mod 的玩家之间的同步数据<b>原样转发</b>（26.2 版）。
 *
 * <h2>⚠ 这里为什么【不能】加 {@code @Environment(EnvType.SERVER)}</h2>
 * {@code main} 入口点在<b>物理客户端上也会被执行</b>，而 {@code @Environment} 的语义是
 * 「环境不匹配就让这个类无法加载」→ 客户端一加载这个入口点就崩（实测踩过）。
 * 正确做法：类上不加注解，在 {@link #onInitialize()} 里做运行时判断 ✓
 *
 * <h2>1.20.1 → 26.2 的变化（只在这一处）</h2>
 * <ul>
 *   <li>旧：{@code ServerPlayNetworking.registerGlobalReceiver(Identifier, (server, player, handler, buf, sender) -> …)}
 *       ＋ {@code ServerPlayNetworking.send(player, Identifier, buf)}</li>
 *   <li>新：{@code registerGlobalReceiver(CustomPacketPayload.Type, (payload, context) -> …)}
 *       ＋ {@code send(player, CustomPacketPayload)} ✓（javap 实测 ✓）</li>
 *   <li>协议不变：第 1 字节 = {@link CloudDiscNet#TYPE_HELLO}（报到/应答）或
 *       {@link CloudDiscNet#TYPE_DATA}（会话 JSON，原样转发）✓</li>
 * </ul>
 *
 * <h2>它做什么</h2>
 * <ul>
 *   <li>只认载荷第 1 个字节：HELLO → 回一个应答并登记该玩家为参与者；其它 → 原样转发给其它参与者 ✓</li>
 *   <li><b>不解析、不存储、不执行任何内容</b> ✓ 不注册命令 ✓ 不要求权限 ✓</li>
 *   <li>只发给「主动报到过」的玩家 ⇒ 没装 Mod 的客户端永远收不到我们的任何包 ✓</li>
 * </ul>
 *
 * <h2>不装会怎样</h2>
 * 客户端自动降级（UDP 直连 / 聊天兜底）✓ 一切照旧 ✓ 只是跨公网时少了这条最省事的通道 ✓
 */
public final class CloudDiscServer implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("CloudDisc-Server");

	/** 参与者集合：用 WeakHashMap 做键集合，玩家对象被回收后条目自动消失（不需要断开回调 ✓）。 */
	private static final Set<ServerPlayer> PARTICIPANTS = Collections.newSetFromMap(new WeakHashMap<>());

	/** 只把前几次成功转发打进日志（不刷屏，又能证明中继真的在转发 ✓）。 */
	private static int forwardLogCount;

	@Override
	public void onInitialize() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return; // 物理客户端上什么都不做 ✓
		}

		// 【26.2】注册载荷类型：服务端要【收】C2S ✓ 也要【发】S2C ✓（专用服务端没有客户端初始化 ✓ 所以两个都在这注册 ✓）
		try {
			PayloadTypeRegistry.serverboundPlay().register(CloudDiscRelayPayload.TYPE, CloudDiscRelayPayload.CODEC);
		} catch (Throwable t) {
			LOGGER.debug("[CloudDisc] serverboundPlay 注册跳过（可能已注册）: {}", t.toString());
		}
		PayloadTypeRegistry.clientboundPlay().register(CloudDiscRelayPayload.TYPE, CloudDiscRelayPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(CloudDiscRelayPayload.TYPE, (payload, context) -> {
			try {
				ServerPlayer player = context.player();
				byte[] raw = payload.data();
				if (raw == null || raw.length < 1) {
					return;
				}
				int first = raw[0] & 0xFF;

				if (first == CloudDiscNet.TYPE_HELLO) {
					// 第 2 个字节（可选）= 客户端的"约定版本"；旧客户端不发，读不到就按 0 处理
					int clientVersion = raw.length >= 2 ? (raw[1] & 0xFF) : 0;
					boolean firstTime = PARTICIPANTS.add(player);
					sendHello(player);
					if (firstTime) {
						LOGGER.info("[CloudDisc] {} 接入同步中继（当前 {} 人；对端约定版本 {}，本端 {}）",
								player.getName().getString(), onlineParticipants(context.player().getServer()).size(),
								clientVersion, CloudDiscNet.CONVENTION_VERSION);
					}
					return;
				}

				// 其余一律视为会话数据：原样转发（连第 1 个字节一起转发，服务端不解释它 ✓）
				PARTICIPANTS.add(player);
				int sent = 0;
				for (ServerPlayer other : onlineParticipants(context.player().getServer())) {
					if (other == player) {
						continue;
					}
					try {
						ServerPlayNetworking.send(other, new CloudDiscRelayPayload(raw));
						sent++;
					} catch (Throwable t) {
						LOGGER.debug("[CloudDisc] 转发给 {} 失败: {}", other.getName().getString(), t.toString());
					}
				}
				if (sent > 0 && forwardLogCount < 5) {
					forwardLogCount++;
					LOGGER.info("[CloudDisc] 已把 {} 的同步数据转发给 {} 人（参与者共 {} 人）",
							player.getName().getString(), sent, onlineParticipants(context.player().getServer()).size());
				}
			} catch (Throwable t) {
				LOGGER.debug("[CloudDisc] 中继接收处理异常（已忽略）: {}", t.toString());
			}
		});

		// 【双保险】玩家断开时立刻从参与者里移除 ✓（配合在线名单校验 ✓ 人数绝对实时 ✓）
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.getPlayer();
			if (p != null && PARTICIPANTS.remove(p)) {
				LOGGER.info("[CloudDisc] {} 断开，已从中继参与者移除（在线 {} 人）", p.getName().getString(), onlineParticipants(server).size());
			}
		});

		LOGGER.info("[CloudDisc] 服务端同步中继已就绪：频道 {}（纯字节转发，客户端协议升级无需更新本组件）",
				CloudDiscNet.RELAY);
	}

	/**
	 * 【修复 · 中继人数不是实时的问题】
	 * 原来只用 WeakHashMap 的键集合 ✗ 但服务端会长期持有 ServerPlayer 对象 ✗
	 * → 玩家退出后条目仍留着 ✗ → 人数只增不减 ✗（实测：3 人在线显示 8 ✗）
	 *
	 * <p>现在以【服务端在线名单】为唯一权威 ✓：
	 * 每次计数/转发前把"已经不在在线名单里的"清掉 ✓ 人数永远等于真实在线且装了本 Mod 的人数 ✓
	 */
	private static java.util.List<ServerPlayer> onlineParticipants(net.minecraft.server.MinecraftServer server) {
		if (server == null) {
			return java.util.List.of();
		}
		java.util.List<ServerPlayer> online = server.getPlayerList().getPlayers();
		// 把已经不在线的踢出参与者集合（WeakHashMap 键集合支持 removeIf ✓）
		PARTICIPANTS.removeIf(p -> p == null || p.isRemoved() || !online.contains(p));
		java.util.List<ServerPlayer> live = new java.util.ArrayList<>();
		for (ServerPlayer p : online) {
			if (PARTICIPANTS.contains(p)) {
				live.add(p);
			}
		}
		return live;
	}
	private static void sendHello(ServerPlayer player) {
		try {
			ServerPlayNetworking.send(player, new CloudDiscRelayPayload(
					new byte[] { (byte) CloudDiscNet.TYPE_HELLO, (byte) CloudDiscNet.CONVENTION_VERSION }));
		} catch (Throwable t) {
			LOGGER.debug("[CloudDisc] 应答 {} 失败: {}", player.getName().getString(), t.toString());
		}
	}
}