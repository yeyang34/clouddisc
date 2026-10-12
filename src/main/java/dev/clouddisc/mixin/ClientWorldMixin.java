package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.jukebox.PlaybackController;
import dev.clouddisc.sync.SyncService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 唱片机声音的替换点（26.2 版）。
 *
 * <p>1.20.1(Yarn) 的 {@code syncWorldEvent} 在 26.2 官方名里是
 * {@code ClientLevel#levelEvent(Entity, int, BlockPos, int)}（javap 实测 ✓）。
 * 服务端用 1010 通知"唱片机开始播放"、1011 通知"停止播放"，
 * data 是 {@code Item.getId(唱片物品)}（不含 NBT/改名）。
 *
 * <p>我们在这里决定：这张唱片归我们管（CANCEL，原版不出声），还是交给原版（PASS）。
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin {
	@Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
			at = @At("HEAD"), cancellable = true)
	private void clouddisc$levelEvent(Entity player, int eventId, BlockPos pos, int data, CallbackInfo ci) {
		if (eventId != 1010 && eventId != 1011) {
			return;
		}
		SyncService service = CloudDiscClient.sync();
		if (service == null) {
			return;
		}
		ClientLevel self = (ClientLevel) (Object) this;
		if (service.onWorldEvent(self, pos, eventId, data) == PlaybackController.Decision.CANCEL) {
			ci.cancel();
		}
	}
}