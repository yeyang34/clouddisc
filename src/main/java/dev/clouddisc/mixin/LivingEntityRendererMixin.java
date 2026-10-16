package dev.clouddisc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.clouddisc.easteregg.JellyEffect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【彩蛋 · 果冻】渲染挂接（26.2 ✓）。
 *
 * <p>为什么挂在 {@code LivingEntityRenderer#scale(S, PoseStack)} 的【尾部】✓：
 * <ul>
 *   <li>它由 {@code submit} 在【实体姿态已经建立】之后调用 ✓ 此时 pose 已 translate 到实体脚底 ✓</li>
 *   <li>它是所有生物渲染的公共路径 ✓（不要求渲染器继承某个特定实现 ✗）</li>
 *   <li>纯客户端 ✓ 只改变换矩阵 ✗ 不动实体坐标/碰撞箱/存档 ✓</li>
 * </ul>
 *
 * <p>范围判定用渲染状态里的 {@code x/y/z} 与碰撞箱尺寸 ✓（按最近点算 ⇒ 大体型也纳入 ✓）
 *
 * <p>相位：以位置哈希代替实体 id（渲染状态里没有 id ✓）⇒ 每只生物错开 ✓ 不会整齐划一 ✓
 */
@Environment(EnvType.CLIENT)
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

	@Inject(method = "scale", at = @At("TAIL"))
	private void clouddisc$jelly(LivingEntityRenderState state, PoseStack pose, CallbackInfo ci) {
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
			int phaseKey = (int) (state.x * 31.0 + state.z * 17.0);
			JellyEffect.apply(pose, phaseKey, h, state.ageInTicks);
		} catch (Throwable ignored) {
			// 渲染里出任何错都不影响画面以外的东西 ✓
		}
	}
}