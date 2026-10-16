package dev.clouddisc.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 【26.2 中继通道的载荷】把 CloudDisc 的协议字节（{@code Protocol.Msg} 编码后的结果）
 * 塞进一个自定义网络包，供"自建中继"通道使用。
 *
 * <p>1.20.1 时代直接发 {@code PacketByteBuf}；26.2 换成了
 * {@code CustomPacketPayload} 体系（javap 实测 ✓）：
 * <ul>
 *   <li>每个包必须有唯一 {@link Type}（由 {@link CustomPacketPayload#createType} 建 ✓）</li>
 *   <li>类型要在 {@code PayloadTypeRegistry.playC2S()/playS2C()} 注册 ✓</li>
 *   <li>收发走 {@code ClientPlayNetworking/ServerPlayNetworking} 的新签名 ✓</li>
 * </ul>
 *
 * <p>载荷就是一段字节：中继是"纯字节转发"，客户端协议升级不需要同步更新服务端组件 ✓
 * （与原 0.13.1 的设计一致 ✓ 分片逻辑仍在 ServerRelayTransport 里 ✓）
 */
public record CloudDiscRelayPayload(byte[] data) implements CustomPacketPayload {

	/** 通道 id：与原版保持一致（clouddisc:relay）✓ */
	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("clouddisc", "relay");

	public static final CustomPacketPayload.Type<CloudDiscRelayPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("clouddisc", "relay"));

	/** 编解码：直接读写一个 byte[]（MC 的 FriendlyByteBuf 自带 writeByteArray/readByteArray ✓） */
	public static final StreamCodec<FriendlyByteBuf, CloudDiscRelayPayload> CODEC =
			CustomPacketPayload.codec(CloudDiscRelayPayload::write, CloudDiscRelayPayload::new);

	public CloudDiscRelayPayload(FriendlyByteBuf buf) {
		this(buf.readByteArray());
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeByteArray(data == null ? new byte[0] : data);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}