package dev.clouddisc.mixin;

import dev.clouddisc.audio.Acoustics;
import net.minecraft.client.sound.Source;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【物理声效 · 第 1 步】捕获"我们自己那个声音源"的 OpenAL source id。
 *
 * <p>为什么要这一步：想要达到 Sound Physics Remastered 那种效果，必须像它一样
 * **直接给引擎里的声源挂 OpenAL EFX**（低通滤波 + 混响辅助发送）——
 * 那需要 source id。它也是用 mixin 进 {@code Channel}/{@code Source} 拿到的。
 *
 * <p>声源自己不知道它属于哪个 SoundInstance，所以配合 {@link SoundSystemMixin}
 * 先标记"下一个被播放的是不是我们的实例"。
 *
 * <p>本步**不改任何听感**，只把 id 记下来并打日志。
 */
@Mixin(Source.class)
public abstract class SourceMixin {
	@Shadow
	private int pointer;

	@Inject(method = "play", at = @At("HEAD"))
	private void clouddisc$onPlay(CallbackInfo ci) {
		if (Acoustics.consumePendingOwnSource()) {
			Acoustics.attachSource(this.pointer);
		}
	}
}