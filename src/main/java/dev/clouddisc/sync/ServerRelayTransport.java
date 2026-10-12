package dev.clouddisc.sync;

import java.util.function.Consumer;

/**
 * 【26.2 移植 · 哑实现】自建中继通道。
 * 26.2 的网络自定义包 API 换成了 CustomPacketPayload 体系，本类先提供完整的 Transport 接口但不可用，
 * 同步自动退回聊天兜底（ChatTransport）；阶段 4 用新 API 重写本类。
 */
public final class ServerRelayTransport implements Transport {

	@Override
	public String name() {
		return "server-relay(26.2 待适配)";
	}

	@Override
	public void start(Consumer<Protocol.Msg> listener) {
	}

	@Override
	public void send(Protocol.Msg msg) {
	}

	@Override
	public int peerCount() {
		return 0;
	}

	@Override
	public boolean carriesUri() {
		return true;
	}

	@Override
	public boolean isReady() {
		return false;
	}

	@Override
	public void close() {
	}

	// SyncService 另外会用到（阶段 4 恢复真实实现）
	public void sayHello() {
	}

	public void resetAttempts() {
	}
}
