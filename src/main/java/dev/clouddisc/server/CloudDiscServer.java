package dev.clouddisc.server;

import dev.clouddisc.net.CloudDiscNet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 服务端可选组件：把装了本 Mod 的玩家之间的同步数据<b>原样转发</b>。
 *
 * <h2>⚠ 这里为什么【不能】加 {@code @Environment(EnvType.SERVER)}</h2>
 * {@code main} 入口点在<b>物理客户端上也会被执行</b>。而 {@code @Environment} 的语义是
 * "环境不匹配时让这个类<b>无法加载</b>"（Fabric 会改写字节码把标注元素摘掉），
 * 于是客户端一加载这个入口点就抛
 * {@code Cannot load class … in environment type CLIENT} → <b>启动直接崩溃</b>（实测踩过）。
 * <p>正确做法就是你现在看到的：类上不加注解，在 {@link #onInitialize()} 里做运行时判断。
 * （{@code client} 入口点不受此影响：专用服务端根本不会去调用它。）
 *
 * <h2>它做什么</h2>
 * <ul>
 *   <li>只认载荷第 1 个字节：{@link CloudDiscNet#TYPE_HELLO} → 回一个应答并把这个玩家登记为参与者；
 *       其它 → 逐字节原样转发给其它参与者。</li>
 *   <li><b>不解析、不存储、不执行任何内容</b>，也不注册命令、不要求权限。</li>
 *   <li>只发给"主动报到过"的玩家，所以<b>没装 Mod 的客户端永远不会收到我们的任何包</b>。</li>
 * </ul>
 *
 * <h2>为什么它一辈子不用改</h2>
 * 因为它是纯字节转发：客户端以后加字段、加消息类型、换协议版本，这里照转不误。
 * 唯一的约定是"频道名 {@code clouddisc:relay}"和"第 1 个字节的用途"，两者都已冻结。
 *
 * <h2>不装会怎样</h2>
 * 客户端会自动降级（继续用 UDP 直连 / 聊天兜底），一切照旧，只是跨公网时少了这条最省事的通道。
 */
public final class CloudDiscServer implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("CloudDisc-Server");

	/**
	 * 参与者集合。
	 * <p>用 {@link WeakHashMap} 做键集合：玩家对象被回收后条目自动消失，
	 * 因此<b>不需要任何断开连接的回调</b>（少一处 API 依赖，也少一处会失效的地方）。
	 */
	private static final Set<ServerPlayerEntity> PARTICIPANTS = Collections.newSetFromMap(new WeakHashMap<>());

	/** 只把前几次成功转发打进日志，既不刷屏、又能证明"中继真的在转发"。 */
	private static int forwardLogCount;

	@Override
	public void onInitialize() {
		// main 入口点在物理客户端上也会被执行，所以必须在这里做运行时判断。
		// 注意：**不能**靠类上的 @Environment(EnvType.SERVER) —— 那会让客户端连类都加载不了，
		// 直接启动崩溃（见类注释）。这里返回后，客户端侧什么都不做。
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}

		ServerPlayNetworking.registerGlobalReceiver(CloudDiscNet.RELAY, (server, player, handler, buf, sender) -> {
			if (buf.readableBytes() < 1) {
				return;
			}
			int first = buf.readByte() & 0xFF;
			byte[] payload = new byte[buf.readableBytes()];
			buf.readBytes(payload);

			if (first == CloudDiscNet.TYPE_HELLO) {
				// 第 2 个字节（可选）= 客户端的"约定版本"；旧客户端不发，读不到就按 0 处理
				int clientVersion = buf.readableBytes() >= 1 ? (buf.readByte() & 0xFF) : 0;
				boolean firstTime = PARTICIPANTS.add(player);
				sendHello(player);
				if (firstTime) {
					LOGGER.info("[CloudDisc] {} 接入同步中继（当前 {} 人；对端约定版本 {}，本端 {}）",
							player.getGameProfile().getName(), PARTICIPANTS.size(),
							clientVersion, CloudDiscNet.CONVENTION_VERSION);
				}
				return;
			}

			// 其余一律视为会话数据：原样转发（连第 1 个字节一起转发，服务端不解释它）
			//
			// 注意：这里**故意不用** ServerPlayNetworking.canSend() 做门禁。
			// canSend 依赖 Fabric 对"客户端声明过哪些频道"的记账（跨版本行为不一定一致），
			// 万一它在本版本恒为 false，就会出现最坏的情况：客户端显示"中继可用"（应答走得通），
			// 但数据一条都转不出去 —— 看起来正常、实际不同步，极难排查。
			// 改成只认"主动报到过的玩家"这张表：判据完全在我们自己手里，不依赖第三方记账。
			PARTICIPANTS.add(player);
			int sent = 0;
			for (ServerPlayerEntity other : PARTICIPANTS) {
				if (other == player || other.isRemoved()) {
					continue;
				}
				try {
					PacketByteBuf out = PacketByteBufs.create();
					out.writeByte(first);
					out.writeBytes(payload);
					ServerPlayNetworking.send(other, CloudDiscNet.RELAY, out);
					sent++;
				} catch (Throwable t) {
					LOGGER.debug("[CloudDisc] 转发给 {} 失败: {}", other.getGameProfile().getName(), t.toString());
				}
			}
			// 前几次转发各打一条日志：让"中继到底有没有在转发"这件事在服务端日志里看得见
			if (sent > 0 && forwardLogCount < 5) {
				forwardLogCount++;
				LOGGER.info("[CloudDisc] 已把 {} 的同步数据转发给 {} 人（参与者共 {} 人）",
						player.getGameProfile().getName(), sent, PARTICIPANTS.size());
			}
			if (sent == 0) {
				LOGGER.debug("[CloudDisc] 收到会话数据但当前没有其它参与者（只有发送者一人在用）");
			}
		});

		LOGGER.info("[CloudDisc] 服务端同步中继已就绪：频道 {}（纯字节转发，客户端协议升级无需更新本组件）",
				CloudDiscNet.RELAY);
	}

	private static void sendHello(ServerPlayerEntity player) {
		try {
			PacketByteBuf ack = PacketByteBufs.create();
			ack.writeByte(CloudDiscNet.TYPE_HELLO);
			ack.writeByte(CloudDiscNet.CONVENTION_VERSION);
			ServerPlayNetworking.send(player, CloudDiscNet.RELAY, ack);
		} catch (Throwable t) {
			LOGGER.debug("[CloudDisc] 应答 {} 失败: {}", player.getGameProfile().getName(), t.toString());
		}
	}
}
