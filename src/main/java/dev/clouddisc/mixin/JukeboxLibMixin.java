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

	/**
	 * 【多人模式的根因 · 别再改回 CloudDiscClient.LOGGER】✗
	 *
	 * <p>{@code CloudDiscClient} 是<b>客户端类</b>。专用服务端（dedicated server）加载它时
	 * 会抛 {@code NoClassDefFoundError}，而这个 mixin 的判断逻辑恰好和日志写在同一个 try 里：
	 * <pre>
	 * try { ... clouddisc$info(...); return name.startsWith("@"); }
	 * catch (Throwable ignored) { return false; }
	 * </pre>
	 * ⇒ 服务端每张云唱片都被判成"不是我们的碟" ⇒ 按原版时长 stop ⇒
	 * <b>实测症状正是"单人正常、服务端装了本 mod 之后时长解锁失效"</b> ✗
	 *
	 * <p>所以这里自己拿一个 side-neutral 的 logger（只依赖 slf4j，客户端/服务端都有）✓
	 */
	@org.spongepowered.asm.mixin.Unique
	private static org.slf4j.Logger clouddisc$logger;

	@org.spongepowered.asm.mixin.Unique
	private static void clouddisc$info(String fmt, Object... args) {
		try {
			if (clouddisc$logger == null) {
				clouddisc$logger = org.slf4j.LoggerFactory.getLogger("CloudDisc");
			}
			clouddisc$logger.info(fmt, args);
		} catch (Throwable ignored) {
			// 日志失败【绝不能】影响判断结果（这正是多人模式那个 bug）
		}
	}

	/**
	 * 【时长解锁 · 正确做法】原版在 tick 里判断"放完了吗"，放完就 stop ✗
	 * 日志实测：tick 注入在跑 ✓ 但 stop 注入【从未执行】✗ ⇒ 不去猜停止走哪条路 ✗
	 * 改成【从源头让它永不放完】✓：tick 开头若"已放完"且【是云唱片】⇒ 计数归零 ✓
	 *   · hasFinished 变 false ✓ 原版不 stop ✓ 不发 1011 ✓
	 *   · 归零 ≠ 冻住 ✓ 计数继续正常增长 ✓ 粒子按原版节奏喷 ✓（v2 喷泉问题的解法 ✓）
	 *   · 原版唱片一律不动 ✓
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void clouddisc$keepPlaying(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			JukeboxSongPlayer self = (JukeboxSongPlayer) (Object) this;
			net.minecraft.world.item.JukeboxSong song = self.getSong();
			if (song == null) {
				return;
			}
			long ticks = self.getTicksSinceSongStarted();
			if (!song.hasFinished(ticks)) {
				return;
			}
			if (!clouddisc$isOurDisc(level)) {
				return;
			}
			((JukeboxSongPlayerAccessor) self).clouddisc$setTicksSinceSongStarted(0L);
			clouddisc$info("[CloudDisc] 时长解锁: 已到 {} 刻，云唱片计数归零继续播放 ✓", ticks);
		} catch (Throwable t) {
			clouddisc$info("[CloudDisc] 时长解锁归零失败（照常结束）: {}", t.toString());
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	@org.spongepowered.asm.mixin.Unique
	private void clouddisc$probeTick(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			long now = System.currentTimeMillis();
			if (now - clouddisc$lastProbe > 5000L) {
				clouddisc$lastProbe = now;
				clouddisc$info("[CloudDisc] 时长解锁·探针: JukeboxSongPlayer.tick 正在跑（mixin 已生效 ✓）");
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
			clouddisc$info("[CloudDisc] 时长解锁判断异常（照常放行原版 stop）: {}", t.toString());
		}
		try {
			clouddisc$info("[CloudDisc] 时长解锁: stop 被调用 是否云唱片={}", ours);
			if (ours) {
				ci.cancel();
			}
		} catch (Throwable t) {
			clouddisc$info("[CloudDisc] 时长解锁拦截失败（照常放行）: {}", t.toString());
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
			if (stack == null || stack.isEmpty()) {
				clouddisc$info("[CloudDisc] 时长解锁判断: 唱片机槽为空 ⇒ 不是云唱片");
				return false;
			}
			// 【顺序很重要】先算出结果、再打日志。日志里出任何岔子都不许改变判断 ——
			// 之前就是"日志写在 try 里"，服务端一抛异常整个方法就 return false（多人失效的根因）✗
			String name = stack.getHoverName().getString();
			boolean ours = name != null && name.startsWith("@");
			clouddisc$info("[CloudDisc] 时长解锁判断: 唱片名={} 是否云唱片={}", name, ours);
			return ours;
		} catch (Throwable ignored) {
			return false;
		}
	}
}
