package dev.clouddisc.net;

import net.minecraft.util.Identifier;

/**
 * 客户端与"服务端中继组件"共用的网络常量。
 *
 * <p>刻意放在**公共类**里：两端都引用它，但都不会因此把对方的专属类加载进来。
 *
 * <p>协议只有两种负载，服务端<b>只认第 1 个字节</b>，其余字节原样转发、绝不解析：
 * <pre>
 *   第 1 字节 = TYPE_HELLO(1)  → 客户端报到 / 服务端应答（用来探测"这台服务器有没有中继"）
 *   第 1 字节 = TYPE_DATA(0)   → 后面是会话消息（JSON），原样转发给其它参与者
 * </pre>
 * 这样设计的意义：<b>以后客户端协议怎么升级，服务端那个 jar 都不用改</b>。
 */
public final class CloudDiscNet {
	/**
	 * 频道名。<b>冻结接口</b>：一旦发布就不再改动
	 * （它是客户端与中继之间唯一的约定，改了等于让所有已部署的中继失效）。
	 */
	public static final Identifier RELAY = new Identifier("clouddisc", "relay");

	/**
	 * 载荷第 1 字节：会话数据。<b>冻结接口</b>。
	 */
	public static final int TYPE_DATA = 0;

	/**
	 * 载荷第 1 字节：报到 / 应答。<b>冻结接口</b>。
	 */
	public static final int TYPE_HELLO = 1;

	/**
	 * "约定版本"（冻结接口的版本号）。
	 *
	 * <p>服务端不需要理解数据内容，但"频道名 + 第 1 字节含义"是它必须知道的约定。
	 * 报到包里会带上这个号，服务端用自己的应答回来一份，双方日志里都会打印。
	 *
	 * <p>这样设计的意义：将来<b>万一</b>必须改动约定，新客户端能通过应答里的版本号
	 * 判断对端是老中继还是新中继，从而选择兼容行为 —— 而不是"装了老中继就静默失效"。
	 * 顺带它也兼容没有这个字节的旧版中继（读不到就按 0 处理）。
	 */
	public static final int CONVENTION_VERSION = 1;

	private CloudDiscNet() {
	}
}
