package dev.clouddisc.sync;

import net.minecraft.resources.Identifier;
import java.util.function.Consumer;

/** 【26.2 移植 · 哑实现】中继：网络 API 变了，先用聊天兜底；阶段4用新 payload API 重写本类。 */
public final class ServerRelayTransport implements Transport {

	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("clouddisc", "relay");

	@Override public boolean available() { return false; }
	@Override public void send(byte[] data) { }
	@Override public String name() { return "server-relay(26.2 待适配)"; }

	// —— 以下为 SyncService 会用到的接口，全部做空实现 ——
	public void start(Consumer<byte[]> onMessage) { }
	public void sayHello() { }
	public void resetAttempts() { }
	public void close() { }
	public boolean isReady() { return false; }
}
