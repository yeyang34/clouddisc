package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.audio.Acoustics;
import dev.clouddisc.audio.EfxEngine;
import dev.clouddisc.jukebox.CloudDiscSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理声效的两个引擎侧挂点（都在 {@code net.minecraft.client.sound.SoundSystem} 上）：
 *
 * <ol>
 *   <li><b>每个刻的标记</b>（{@link #clouddisc$mark}，挂在 {@code play} 头部）：
 *       引擎每次播放声音前，告诉 {@link Acoustics}"下一个被播放的是不是我们的实例"，
 *       并顺手把 {@link SoundSystem} 实例记下来（供按实例精确反查 source id）。</li>
 *   <li><b>EFX 初始化</b>（{@link #clouddisc$onEngineReady}）：声音引擎刚把 OpenAL
 *       上下文建好、正要 {@code SoundListener#init()} 的那一刻创建 EFX 资源。
 *       <p>挂点说明：SPR 用的是 Mojang 名 {@code SoundEngine#loadLibrary} 里
 *       {@code Listener#reset()} 那一句；在 yarn 1.20.1 里
 *       {@code loadLibrary} = {@code SoundSystem#start}，
 *       {@code Listener} = {@code SoundListener}，{@code reset} = {@code init}
 *       —— 所以这里的 target 是 {@code Lnet/minecraft/client/sound/SoundListener;init()V}。
 *       该方法体已用 {@code javap} 核对过：{@code SoundEngine.init(...)}（建上下文）
 *       之后紧接着就是这一句，因此注入时 ALC 上下文已经是 current 的。</li>
 * </ol>
 */
@Mixin(SoundSystem.class)
public class SoundSystemMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void clouddisc$mark(SoundInstance sound, CallbackInfo ci) {
		Acoustics.markNextOwnSource(sound instanceof CloudDiscSoundInstance);
		Acoustics.noteSoundSystem((SoundSystem) (Object) this);
	}

	@Inject(method = "start", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/sound/SoundListener;init()V"))
	private void clouddisc$onEngineReady(CallbackInfo ci) {
		try {
			// 这里只做一件事：创建 EFX 资源。绝不抛异常（EfxEngine.init 内部全包了），
			// 否则会把原版 start() 的 try/catch 打穿，导致整个声音引擎起不来。
			EfxEngine.init();
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: EFX 初始化挂点异常（已忽略）: {}", t.toString());
		}
	}
}
