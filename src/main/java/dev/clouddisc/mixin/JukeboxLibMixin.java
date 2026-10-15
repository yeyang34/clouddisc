package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
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
 * 【内置 · 解除播放时长限制】（26.2 版 · 第 3 次迭代 ✓）
 *
 * <h2>迭代过程（每一步都是被实测逼出来的 ✓）</h2>
 * <ol>
 *   <li>v1：cancel 整个 {@code tick} ✗ → 音符粒子 / 播放事件全没了 ✗（"没有音符粒子"✗）</li>
 *   <li>v2：tick 末尾把 {@code ticksSinceSongStarted} 写回（冻住计数）✗
 *       → 原版粒子是按"计数取模"触发的 ✗ 计数不动 ⇒ 条件恒成立 ⇒ 每刻都喷 ✗（"粒子像喷泉"✗）</li>
 *   <li><b>v3（本版 ✓）</b>：<b>完全不碰 tick，也不碰任何计数</b> ✗
 *       —— 只拦 {@code stop(LevelAccessor, BlockState)}：对云唱片直接取消这次 stop ✓
 *       ⇒ tick 照常跑（计数自然增长 ✓ 粒子按正常节奏喷 ✓）
 *       ⇒ 只是"放完了要停"那一步被挡掉 ✓ → 不会停 ✗ 不会发 1011 ✗ → 歌能一直放 ✓</li>
 * </ol>
 *
 * <p>只对「云唱片」生效（唱片自定义名以 {@code @} 开头 ✓）✓ 原版唱片完全不受影响 ✓
 * 全程 try/catch ✓ 出错就让原版照常停 ✓
 */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxLibMixin {

	/**
	 * 【二分定位】证明这个 mixin 到底有没有被应用：
	 *   有这行日志 ⇒ mixin 生效 ✓（那 stop 没日志说明停止走了别的路径 ✗）
	 *   没这行日志 ⇒ mixin 压根没应用 ✗（要查 mixin 配置/环境 ✗）
	 * 每 5 秒最多打一条，避免刷屏 ✓
	 */
	@org.spongepowered.asm.mixin.Unique
	private static long clouddisc$lastProbe = 0L;

	@Inject(method = "tick", at = @At("HEAD"))
	@org.spongepowered.asm.mixin.Unique
	private void clouddisc$probeTick(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			long now = System.currentTimeMillis();
			if (now - clouddisc$lastProbe > 5000L) {
				clouddisc$lastProbe = now;
				CloudDiscClient.LOGGER.info("[CloudDisc] 时长解锁·探针: JukeboxSongPlayer.tick 正在跑（mixin 已生效 ✓）");
			}
		} catch (Throwable ignored) {
		}
	}
	@Inject(method = "stop", at = @At("HEAD"), cancellable = true)
	private void clouddisc$holdStop(LevelAccessor level, BlockState state, CallbackInfo ci) {
		// ★ 这里必须是第一行：任何异常都要先留下痕迹（之前日志放在 try 里，抛异常就被吞掉，什么都看不到 ✗）
		boolean ours = false;
		try {
			ours = clouddisc$isOurDisc(level);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 时长解锁判断异常（照常放行原版 stop）: {}", t.toString());
		}
		try {
			CloudDiscClient.LOGGER.info("[CloudDisc] 时长解锁: stop 被调用 是否云唱片={}", ours);
			if (ours) {
				ci.cancel();
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 时长解锁拦截失败（照常放行）: {}", t.toString());
		}
	}

	/** 这张碟是不是我们的云唱片（名字以 @ 开头 ✓）。 */
	private boolean clouddisc$isOurDisc(LevelAccessor level) {
		try {
			if (!(level instanceof Level lvl)) {
				return false;
			}
			BlockPos pos = ((JukeboxSongPlayerAccessor) this).clouddisc$blockPos();
			if (pos == null) {
				return false;
			}
			BlockEntity be = lvl.getBlockEntity(pos);
			if (!(be instanceof JukeboxBlockEntity jb)) {
				return false;
			}
			// 【26.2 修正】getTheItem() 很可能是"要弹出的那件"（播放中为空）✗
			// 这里改用容器槽位 0（唱片机里那张碟 ✓）
			ItemStack stack = jb.getItem(0);
			if (stack == null || stack.isEmpty()) {
				stack = jb.getTheItem();
			}
			CloudDiscClient.LOGGER.info("[CloudDisc] 时长解锁判断: 唱片名={} 是否云唱片={}",
					(stack == null ? "空" : stack.getHoverName().getString()),
					(stack != null && !stack.isEmpty() && stack.getHoverName().getString().startsWith("@")));
			if (stack == null || stack.isEmpty()) {
				return false;
			}
			String name = stack.getHoverName().getString();
			return name != null && name.startsWith("@");
		} catch (Throwable ignored) {
			return false;
		}
	}
}
