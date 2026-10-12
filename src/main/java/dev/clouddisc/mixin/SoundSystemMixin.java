package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.audio.Acoustics;
import dev.clouddisc.audio.EfxEngine;
import dev.clouddisc.jukebox.CloudDiscSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理声效的两个引擎侧挂点（26.2 版，目标从 SoundManager 改为 {@code SoundEngine}）。
 *
 * <ol>
 *   <li><b>标记下一个播放的是不是我们的实例</b>（{@code play(SoundInstance)} 头部，javap 实测存在 ✓）：
 *       引擎每次播放声音前告诉 {@link Acoustics}，配合 {@code SourceMixin} 就能抓到正确的 OpenAL source id。</li>
 *   <li><b>EFX 初始化</b>：在 {@code SoundEngine#loadLibrary}（private synchronized ✓）里
 *       {@code com.mojang.blaze3d.audio.Listener#reset()} 那一句上创建 EFX 资源
 *       （javap 实测：{@code Listener} 有 {@code reset()} ✓；这正是 Sound Physics Remastered 的挂点位置 ✓）。
 *       此时 ALC 上下文已经 current ✓。</li>
 * </ol>
 */
@Mixin(SoundEngine.class)
public class SoundSystemMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void clouddisc$mark(SoundInstance sound, CallbackInfo ci) {
		try {
			Acoustics.markNextOwnSource(sound instanceof CloudDiscSoundInstance);
		} catch (Throwable t) {
			// 绝不因为标记失败影响原版播放
		}
	}

	@Inject(method = "loadLibrary", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/audio/Listener;reset()V"))
	private void clouddisc$onEngineReady(CallbackInfo ci) {
		try {
			// 只做一件事：创建 EFX 资源。绝不抛异常，否则会把原版 loadLibrary 的 try/catch 打穿，
			// 导致整个声音引擎起不来。
			EfxEngine.init();
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: EFX 初始化挂点异常（已忽略）: {}", t.toString());
		}
	}
}