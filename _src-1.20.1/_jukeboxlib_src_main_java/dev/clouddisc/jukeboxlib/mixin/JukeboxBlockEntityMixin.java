package dev.clouddisc.jukeboxlib.mixin;

import dev.clouddisc.jukeboxlib.JukeboxLibConfig;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MusicDiscItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【服务端侧】让 CloudDisc 的唱片不受原版"唱片自带时长"限制。
 *
 * <h2>为什么挂在这里</h2>
 * 原版 1.20.1 判断"这张碟放完了没有"就是这一处（反编译 {@code JukeboxBlockEntity} 确认）：
 * <pre>
 *   return world.getGameTime() >= recordStartTick + musicDisc.getSongLengthInTicks() + 20;
 * </pre>
 * 所以只要对**我们的碟**让这个方法始终返回 {@code false}，原版就不会提前发 1011
 * → 歌能完整播完 ✓，而"玩家拔碟/换碟"仍然走原版逻辑（那些路径不受这里影响）✓
 *
 * <h2>只对 "云唱片" 生效</h2>
 * 通过唱片的自定义名判断（默认前缀 {@code @}，见 {@link JukeboxLibConfig}）。
 * 服务器上其他人的**原版唱片完全不受影响** ✓（它们照常按原时长结束）。
 *
 * <h2>为什么需要一个"上限"</h2>
 * 如果不设上限，碟没被取出时方块会永远显示"在播放"（音符粒子、GameEvent 一直发 ✗）。
 * 所以这里记录开始刻，超过 {@code maxExtendMinutes} 后就不再干预，交回原版结束。
 *
 * <p>风险提示：服务端 Mixin 注入失败会导致**服务器起不来**，所以这里只做一件最简的事：
 * 在 HEAD 处拦截一个 private 方法并改返回值，不捕获局部变量、不改写指令。
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {

	/** 本机记录的"这张碟从哪一刻开始放"（原版那个私有字段名不该被我们依赖）。 */
	@Unique
	private long clouddisc$startedTick = Long.MIN_VALUE;

	@Inject(method = "startPlaying", at = @At("HEAD"))
	private void clouddisc$onStartPlaying(CallbackInfo ci) {
		// 用 "现在" 近似开始刻：startPlaying 就是原版记录 RecordStartTick 的地方
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		if (self.getWorld() != null) {
			this.clouddisc$startedTick = self.getWorld().getTime();
		}
	}

	@Inject(method = "isSongFinished", at = @At("HEAD"), cancellable = true)
	private void clouddisc$keepPlaying(MusicDiscItem disc, CallbackInfoReturnable<Boolean> cir) {
		if (!JukeboxLibConfig.ENABLED) {
			return;
		}
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		if (self.getWorld() == null) {
			return;
		}

		// 只认"云唱片"：自定义名以配置的前缀开头
		ItemStack stack = self.getStack(0);
		if (stack == null || stack.isEmpty() || !stack.hasCustomName()) {
			return;
		}
		String name = stack.getName().getString();
		if (name == null || !name.startsWith(JukeboxLibConfig.DISC_PREFIX)) {
			return;
		}

		// 超过上限就不再干预，交回原版（避免方块永远停在"播放中"）
		long started = this.clouddisc$startedTick;
		if (started != Long.MIN_VALUE) {
			long elapsed = self.getWorld().getTime() - started;
			if (elapsed > JukeboxLibConfig.maxExtendTicks()) {
				return;
			}
		}
		cir.setReturnValue(Boolean.FALSE);
	}
}
