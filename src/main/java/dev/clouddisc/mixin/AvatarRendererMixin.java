package dev.clouddisc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.clouddisc.easteregg.JellyEffect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【彩蛋 · 果冻】玩家专用渲染挂接（26.2 ✓）。
 *
 * <p>为什么必须单独挂 ✗：26.2 里玩家不走 {@code LivingEntityRenderer.scale} ✗
 * 而是 {@code AvatarRenderer} 自己重写了一版 {@code scale(AvatarRenderState, PoseStack)} ✓
 * 所以只在父类挂注入点 ⇒ 【玩家完全没反应】✓（实测如此 ✓）
 *
 * <p>{@code AvatarRenderState} 继承 {@code LivingEntityRenderState} ✓ 所以效果逻辑完全复用 ✓
 */
@Environment(EnvType.CLIENT)
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

	@Inject(method = "scale", at = @At("TAIL"))
	private void clouddisc$jellyPlayer(AvatarRenderState state, PoseStack pose, CallbackInfo ci) {
		try {
			if (!JellyEffect.isActive() || state == null) {
				return;
			}
			float halfW = Math.max(0.1f, state.boundingBoxWidth * 0.5f);
			float h = Math.max(0.1f, state.boundingBoxHeight);
			if (!JellyEffect.inRange(state.x - halfW, state.y, state.z - halfW,
					state.x + halfW, state.y + h, state.z + halfW)) {
				return;
			}
			JellyEffect.apply(pose, 0, h, state.ageInTicks);
		} catch (Throwable ignored) {
			// 渲染里出错绝不影响别的 ✓
		}
	}
}