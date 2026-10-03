package dev.clouddisc.sync;

import java.util.function.Consumer;

/**
 * 通信通道抽象：把"怎么把一条消息送到别的玩家那里"和"同步逻辑"解耦。
 *
 * <p>三个实现方向（见设计文档"通信层"）：
 * <ol>
 *   <li>{@link UdpTransport}：局域网广播发现 + UDP 直发（主通道，低延迟、不刷聊天）</li>
 *   <li>{@link ChatTransport}：把消息藏进聊天（兜底，任何原版服可用，但受限流与长度限制）</li>
 *   <li>自建中继（可选，最容易用但在服务器上跑）</li>
 * </ol>
 */
public interface Transport extends AutoCloseable {
	String name();

	void start(Consumer<Protocol.Msg> listener);

	void send(Protocol.Msg msg);

	/** 当前可见的对端数量；0 表示只有自己。 */
	int peerCount();

	/** 是否能把完整 URI 传过去（聊天通道不行）。 */
	boolean carriesUri();

	boolean isReady();

	@Override
	void close();
}
