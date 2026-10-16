package dev.clouddisc.mixin;

import com.mojang.blaze3d.audio.Channel;
import dev.clouddisc.audio.Acoustics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【物理声效 · 第 1 步】捕获"我们自己那个声音源"的 OpenAL source id（26.2 版）。
 *
 * <p>1.20.1(Yarn) 里这个类是 {@code net.minecraft.client.sound.Source}、字段叫 {@code pointer}；
 * 26.2 官方把音源收进 {@code com.mojang.blaze3d.audio.Channel}，字段名 {@code source}
 * （javap 实测：{@code private final int source;}）→ 用 {@link SourceAccessor} 读出来。
 *
 * <p>声源自己不知道它属于哪个 SoundInstance，所以配合 {@code SoundSystemMixin}
 * 先标记"下一个被播放的是不是我们的实例"（{@link Acoustics#consumePendingOwnSource()}）。
 *
 * <p>本步**不改任何听感**，只把 id 记下来并打日志。
 */
@Mixin(Channel.class)
public abstract class SourceMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void clouddisc$onPlay(CallbackInfo ci) {
		if (Acoustics.consumePendingOwnSource()) {
			Acoustics.attachSource(((SourceAccessor) this).clouddisc$source());
		}
	}
}