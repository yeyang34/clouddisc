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
 * 【内置 · 解除播放时长限制】把原来独立的 jukeboxlib 合并进主 jar（26.2 版）。
 *
 * <h2>原版怎么限制的</h2>
 * 1.20.1：{@code JukeboxBlockEntity#isSongFinished(disc)} 比较 gameTime ✗
 * 26.2：{@code JukeboxSongPlayer#tick} 内部用 {@code ticksSinceSongStarted} 调
 * {@code JukeboxSong#hasFinished(...)} ✓ 到点就 {@code stop()} ✗（并发 1011 ✗）
 *
 * <h2>做法（本轮修正后的关键 ✓）</h2>
 * 只在 tick 的【末尾】把 {@code ticksSinceSongStarted} 按住（设回进入时的值）✓
 * 于是：原版永远认为"还没放完"✗ 不会 stop ✗ 也不会发 1011 ✗
 * 但 tick 里的【其它一切照常】✓ —— 特别是 {@code spawnMusicParticles}（音符粒子 ✓）
 * 与 {@code shouldEmitJukeboxPlayingEvent}（播放事件 ✓）。
 *
 * <p>上一版是把整个 tick 直接 cancel ✗ → 粒子/事件全没了 ✗（实测反馈："播放没有音符粒子"✗）
 * 这一版改成"只按住计时器"✓ 两者兼得 ✓
 *
 * <p>只对「云唱片」生效（唱片自定义名以 {@code @} 开头 ✓），原版唱片完全不受影响 ✓
 * 全程 try/catch ✓ 出错就让原版照常跑 ✓
 */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxLibMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void clouddisc$holdTicksHead(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			if (!clouddisc$isOurDisc(level)) {
				return;
			}
			// 记住进入时的计数，tick 末尾再写回去（原版这一段里加的那些都不保留）
			JukeboxSongPlayer self = (JukeboxSongPlayer) (Object) this;
			clouddisc$savedTicks = ((JukeboxSongPlayerAccessor) self).clouddisc$getTicksSinceSongStarted();
			clouddisc$holding = true;
		} catch (Throwable ignored) {
			clouddisc$holding = false;
		}
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void clouddisc$holdTicksReturn(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			if (!clouddisc$holding) {
				return;
			}
			clouddisc$holding = false;
			// 只有"原版认为已经放完"时才需要按住 ✗（否则让它正常计数，粒子节奏才自然 ✓）
			JukeboxSongPlayer self = (JukeboxSongPlayer) (Object) this;
			JukeboxSongPlayerAccessor acc = (JukeboxSongPlayerAccessor) self;
			// 直接写回进入时的计数 ⇒ 原版永远认为还没放完 ✗ → 不会 stop ✗ 也不发 1011 ✗
			acc.clouddisc$setTicksSinceSongStarted(clouddisc$savedTicks);
				acc.clouddisc$setTicksSinceSongStarted(clouddisc$savedTicks);
		} catch (Throwable ignored) {
			// 忽略：出错就让原版照常结束
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
			ItemStack stack = jb.getTheItem();
			if (stack == null || stack.isEmpty()) {
				return false;
			}
			String name = stack.getHoverName().getString();
			return name != null && name.startsWith("@");
		} catch (Throwable ignored) {
			return false;
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private long clouddisc$savedTicks;

	@org.spongepowered.asm.mixin.Unique
	private boolean clouddisc$holding;
}
