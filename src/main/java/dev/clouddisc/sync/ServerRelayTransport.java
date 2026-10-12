package dev.clouddisc.sync;

import net.minecraft.resources.Identifier;

/** 【26.2 移植 · 哑实现】中继通道：26.2 网络自定义包 API 变了，先用聊天兜底，阶段4恢复。 */
public final class ServerRelayTransport implements Transport {

	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("clouddisc", "relay");

	@Override
	public boolean available() {
		return false;
	}

	@Override
	public void send(byte[] data) {
	}

	@Override
	public String name() {
		return "server-relay(26.2 待适配)";
	}
}
