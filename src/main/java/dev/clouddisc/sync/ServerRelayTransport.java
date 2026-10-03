package dev.clouddisc.sync;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.net.CloudDiscNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 服务端中继通道（客户端侧）。
 *
 * <p>对应服务端那个可选组件 {@code CloudDiscServer}。特点：
 * <ul>
 *   <li><b>有则用、无则自动降级</b>：进服时发一个"报到"，只有服务器装了中继组件才会回一份，
 *       收到应答才算可用。所以装在没装组件的服务器上完全无副作用。</li>
 *   <li><b>不走聊天长度限制</b>，因此 {@link #carriesUri()} 为 true —— 跟随方可以直接用发起方给的地址，
 *       不必自己会解析音源（比聊天兜底通道更强）。</li>
 *   <li>协议透明：本通道只搬运 {@link Protocol.Msg} 的 JSON 字节，和服务端组件之间没有别的内容约定。</li>
 * </ul>
 */
public final class ServerRelayTransport implements Transport {
	/** 应答有效期：超过这么久没再收到应答，就认为中继不可用（例如换了服务器）。 */
	private static final long ACK_TTL_MILLIS = 60_000L;
	/** 报到最多重试这么多次（每次间隔 3 秒）——避免在没有中继的服务器上无限发包。 */
	private static final int MAX_HELLO_ATTEMPTS = 20;

	private volatile Consumer<Protocol.Msg> listener;
	private volatile long lastAckMillis;
	private volatile boolean announced;
	private volatile int helloAttempts;

	public ServerRelayTransport() {
		ClientPlayNetworking.registerGlobalReceiver(CloudDiscNet.RELAY, (client, handler, buf, sender) -> {
			if (buf.readableBytes() < 1) {
				return;
			}
			int first = buf.readByte() & 0xFF;
			if (first == CloudDiscNet.TYPE_HELLO) {
				// 第 2 个字节（可选）= 服务端的"约定版本"；没有这个字节的旧中继按 0 处理
				int serverVersion = buf.readableBytes() >= 1 ? (buf.readByte() & 0xFF) : 0;
				lastAckMillis = System.currentTimeMillis();
				if (!announced) {
					announced = true;
					CloudDiscClient.LOGGER.info("[CloudDisc] 服务器中继可用（对端约定版本 {}，本端 {}），"
							+ "跨公网同步将走它，且不再需要聊天兜底",
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
		});
	}

	@Override
	public String name() {
		return "relay";
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
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return;
		}
		helloAttempts++;
		client.execute(() -> {
			try {
				PacketByteBuf buf = PacketByteBufs.create();
				buf.writeByte(CloudDiscNet.TYPE_HELLO);
				buf.writeByte(CloudDiscNet.CONVENTION_VERSION);
				ClientPlayNetworking.send(CloudDiscNet.RELAY, buf);
			} catch (Throwable t) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 中继报到失败: {}", t.toString());
			}
		});
	}

	/** 每次进服重置重试计数。 */
	public void resetAttempts() {
		helloAttempts = 0;
	}

	@Override
	public void send(Protocol.Msg msg) {
		if (!isReady()) {
			return;
		}
		byte[] json = Protocol.toBytes(msg);
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			try {
				PacketByteBuf buf = PacketByteBufs.create();
				buf.writeByte(CloudDiscNet.TYPE_DATA);
				buf.writeBytes(json);
				ClientPlayNetworking.send(CloudDiscNet.RELAY, buf);
			} catch (Throwable t) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 中继发送失败: {}", t.toString());
			}
		});
	}

	@Override
	public int peerCount() {
		return isReady() ? 1 : 0;
	}

	/** 中继能带完整 URL（不受聊天 256 字符限制）。 */
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
