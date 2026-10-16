package dev.clouddisc.mixin;

// 协议前缀见 ChatTransport：我们的中继行一律以 [CD1] 开头
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天兜底通道的入站接缝（26.2 版）。
 *
 * <p>26.2 里 {@code ChatComponent#addMessage} 只剩一个 4 参重载
 * （javap 实测：Component, MessageSignature, GuiMessageSource, GuiMessageTag），
 * 玩家聊天、disguised chat、system chat 三条分支最终都会走到它，一个点全覆盖。
 *
 * <p>在显示层拦掉自己的协议行；不碰网络层的校验/签名链，避免被服务器踢下线。
 * 代价：没装本 Mod 的玩家照旧会看到 [CD1]... 这样的乱码行，所以聊天通道只当兜底。
 */
@Environment(EnvType.CLIENT)
@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), cancellable = true)
	private void clouddisc$addMessage(Component message, MessageSignature signature, GuiMessageSource source,
			GuiMessageTag tag, CallbackInfo ci) {
		if (message == null) {
			return;
		}
		String s = message.getString();
		if (s != null && s.startsWith("[CD1]")) {
			ci.cancel();
		}
	}
}