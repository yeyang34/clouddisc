package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.jukebox.PlaybackController;
import dev.clouddisc.sync.SyncService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 唱片机声音的替换点。
 *
 * <p>Yarn 1.20.1 里世界事件叫 {@code syncWorldEvent}（Mojang 名 {@code levelEvent}），
 * 服务端用 1010 通知"唱片机开始播放"、1011 通知"停止播放"，
 * data 是{@code Item.getRawId(唱片物品)}（<b>不含 NBT/改名</b>）。
 *
 * <p>所以我们在这里决定：这张唱片归我们管（CANCEL，原版不出声），还是交给原版（PASS）。
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Inject(method = "syncWorldEvent", at = @At("HEAD"), cancellable = true)
	private void clouddisc$syncWorldEvent(PlayerEntity player, int eventId, BlockPos pos, int data, CallbackInfo ci) {
		if (eventId != 1010 && eventId != 1011) {
			return;
		}
		SyncService service = CloudDiscClient.sync();
		if (service == null) {
			return;
		}
		ClientWorld self = (ClientWorld) (Object) this;
		if (service.onWorldEvent(self, pos, eventId, data) == PlaybackController.Decision.CANCEL) {
			ci.cancel();
		}
	}
}
