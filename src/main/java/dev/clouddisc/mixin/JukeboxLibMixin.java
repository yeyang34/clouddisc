package dev.clouddisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【内置 · 解除播放时长限制】把原来独立的 jukeboxlib 合并进主 jar。
 *
 * <h2>原版怎么限制的（1.20.1 → 26.2 的变化）</h2>
 * 1.20.1：{@code JukeboxBlockEntity#isSongFinished(disc)} 比较
 * {@code gameTime >= recordStartTick + songLength + 20} ✗ 到点就停 ✗
 * 26.2：点唱机重构成 {@code JukeboxBlockEntity} + {@link JukeboxSongPlayer} ✓
 * 结束判定在 {@code JukeboxSongPlayer#tick} 里（它内部调 {@code JukeboxSong#hasFinished}）✓
 *
 * <h2>做法</h2>
 * 只对「云唱片」生效（唱片自定义名以 {@code @} 开头 ✓ 与原版一致）：
 * 在 {@code tick} 的 HEAD 处直接 cancel ✗ 于是原版永远不会因为"歌放完了"而 stop ✗
 * → 原版不会发 1011 → 我们的流不会被掐断 ✓ 可以播任意长 ✓
 * 原版唱片完全不受影响 ✓（名字不以 @ 开头就放行 ✓）
 *
 * <p>安全：只在 HEAD 拦一个 public 方法 ✓ 不收局部变量 ✓ 不改写指令 ✓ 全程 try/catch ✓
 */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxLibMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void clouddisc$keepPlaying(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			if (!(level instanceof Level lvl)) {
				return;
			}
			BlockPos pos = ((JukeboxSongPlayerAccessor) this).clouddisc$blockPos();
			if (pos == null) {
				return;
			}
			BlockEntity be = lvl.getBlockEntity(pos);
			if (!(be instanceof JukeboxBlockEntity jb)) {
				return;
			}
			ItemStack stack = jb.getTheItem();
			if (stack == null || stack.isEmpty()) {
				return;
			}
			String name = stack.getHoverName().getString();
			if (name == null || !name.startsWith("@")) {
				return; // 不是云唱片 → 原版逻辑照旧 ✓
			}
			// 云唱片：不让原版判定"放完了"（tick 整个跳过 ⇒ 不会 stop、不会发 1011）
			ci.cancel();
		} catch (Throwable ignored) {
			// 出错就让原版照常跑，绝不因为我们把点唱机卡死
		}
	}
}