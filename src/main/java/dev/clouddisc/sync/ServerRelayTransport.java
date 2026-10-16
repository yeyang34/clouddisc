package dev.clouddisc.sync;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.net.CloudDiscNet;
import dev.clouddisc.net.CloudDiscRelayPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 服务端中继通道（客户端侧，26.2 版）。
 *
 * <p>1.20.1 → 26.2 的唯一变化是【网络包形态】：
 * <ul>
 *   <li>旧：{@code ClientPlayNetworking.send(Identifier, FriendlyByteBuf)}
 *       ＋ {@code registerGlobalReceiver(Identifier, (client, handler, buf, sender) -> …)}</li>
 *   <li>新：{@code send(CustomPacketPayload)} ＋ {@code registerGlobalReceiver(Type, (payload, context) -> …)} ✓</li>
 * </ul>
 * 载荷就是原来的那串字节（见 {@link CloudDiscRelayPayload} ✓），
 * 所以协议本身（第 1 字节 TYPE_HELLO/TYPE_DATA ✓ 报到/应答 ✓ JSON 转发 ✓）与原版完全一致 ✓
 *
 * <p>特点（与原 0.13.1 相同 ✓）：
 * <ul>
 *   <li>有则用、无则自动降级：进服发报到，只有装了中继组件的服务器才应答 ✓</li>
 *   <li>不受聊天长度限制 ⇒ {@link #carriesUri()} 为 true ✓ 跟随方可以直接用发起方的地址 ✓</li>
 * </ul>
 */
public final class ServerRelayTransport implements Transport {
	private static final long ACK_TTL_MILLIS = 600_000L;
	private static final int MAX_HELLO_ATTEMPTS = 20;

	private volatile Consumer<Protocol.Msg> listener;
	private volatile long lastAckMillis;
	private volatile boolean announced;
	private volatile int helloAttempts;

	public ServerRelayTransport() {
		ClientPlayNetworking.registerGlobalReceiver(CloudDiscRelayPayload.TYPE, (payload, context) -> {
			try {
				byte[] raw = payload.data();
				if (raw == null || raw.length < 1) {
					return;
				}
				FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
				int first = buf.readByte() & 0xFF;
				if (first == CloudDiscNet.TYPE_HELLO) {
					int serverVersion = buf.readableBytes() >= 1 ? (buf.readByte() & 0xFF) : 0;
					lastAckMillis = System.currentTimeMillis();
					if (!announced) {
						announced = true;
						CloudDiscClient.LOGGER.info("[CloudDisc] 服务器中继可用（对端约定版本 {}，本端 {}），跨公网同步将走它",
								serverVersion, CloudDiscNet.CONVENTION_VERSION);
					}
					return;
				}
				byte[] data = new byte[buf.readableBytes()];
				buf.readBytes(data);
				Protocol.Msg msg = Protocol.fromJson(new String(data, StandardCharsets.UTF_8));
				Consumer<Protocol.Msg> l = listener;
				if (msg != null && l != null) {
					l.accept(msg);
				}
			} catch (Throwable t) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 中继接收失败: {}", t.toString());
			}
		});
	}

	@Override
	public String name() {
		return "server-relay";
	}

	@Override
	public void start(Consumer<Protocol.Msg> listener) {
		this.listener = listener;
	}

	/** 进服后报到一次：既登记为参与者，也用来探测中继是否可用。 */
	public void sayHello() {
		if (isReady() || helloAttempts >= MAX_HELLO_ATTEMPTS) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return;
		}
		helloAttempts++;
		client.execute(() -> {
			try {
				if (!ClientPlayNetworking.canSend(CloudDiscRelayPayload.TYPE)) {
					return; // 服务器没装中继组件 → 静默降级 ✓
				}
				ClientPlayNetworking.send(new CloudDiscRelayPayload(
						new byte[] { (byte) CloudDiscNet.TYPE_HELLO, (byte) CloudDiscNet.CONVENTION_VERSION }));
			} catch (Throwable t) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 中继报到失败: {}", t.toString());
			}
		});
	}

	public void resetAttempts() {
		helloAttempts = 0;
	}

	@Override
	public void send(Protocol.Msg msg) {
		if (!isReady()) {
			return;
		}
		byte[] json = Protocol.toBytes(msg);
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			try {
				byte[] out = new byte[json.length + 1];
				out[0] = (byte) CloudDiscNet.TYPE_DATA;
				System.arraycopy(json, 0, out, 1, json.length);
				ClientPlayNetworking.send(new CloudDiscRelayPayload(out));
			} catch (Throwable t) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 中继发送失败: {}", t.toString());
			}
		});
	}

	@Override
	public int peerCount() {
		return isReady() ? 1 : 0;
	}

	@Override
	public boolean carriesUri() {
		return true;
	}

	@Override
	public boolean isReady() {
		return System.currentTimeMillis() - lastAckMillis < ACK_TTL_MILLIS;
	}

	@Override
	public void close() {
		lastAckMillis = 0L;
		announced = false;
	}
}