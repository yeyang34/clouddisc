package dev.clouddisc.mixin;

import dev.clouddisc.sync.ChatTransport;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天兜底通道的<b>入站</b>接缝（v0.1 里刻意留空的那一处，现在补上）。
 *
 * <p>为什么挂在 {@code ChatHud#addMessage} 而不是 {@code ClientPlayNetworkHandler#onChatMessage}：
 * <ul>
 *   <li>这里是所有"要显示出来的消息"的唯一汇合点：玩家聊天、disguised chat、system chat
 *       三条分支最终都会走到它，一个点全覆盖；</li>
 *   <li><b>不碰网络层的处理逻辑</b> —— 如果在校验/确认消息那里 cancel，会打断 1.19+ 的
 *       聊天签名链（lastSeen 更新），有被服务器踢下线的风险。在显示层拦掉则完全无害。</li>
 * </ul>
 *
 * <p>代价：<b>没装本 Mod 的玩家照旧会看到</b> {@code [CD1]...} 这样的乱码行（我们只能管到自己的客户端）；
 * 服务器日志与聊天管理插件同样看得到。所以聊天通道只该当兜底用。
 */
@Environment(EnvType.CLIENT)
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
	/** 单参数版本：系统消息等走这里。 */
	@Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
	private void clouddisc$addMessage(Text message, CallbackInfo ci) {
		hideIfOurs(message, ci);
	}

	/** 三参数版本：玩家聊天（带签名）走这里。addMessage 有多个重载，所以必须写完整描述符。 */
	@Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), cancellable = true)
	private void clouddisc$addMessageSigned(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
		hideIfOurs(message, ci);
	}

	private static void hideIfOurs(Text message, CallbackInfo ci) {
		if (message == null) {
			return;
		}
		// ingest 只对本 Mod 的消息返回 true：顺便完成解析 + 投递给同步层
		if (ChatTransport.ingest(message.getString())) {
			ci.cancel(); // 不显示这行乱码，但数据已经拿到了
		}
	}
}
