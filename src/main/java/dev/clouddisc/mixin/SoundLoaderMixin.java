package dev.clouddisc.mixin;

import dev.clouddisc.jukebox.StreamRegistry;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 【物理声效 · 取流接缝（26.2 版）】把我们自己的 PCM 流交给 Minecraft 的音频引擎。
 *
 * <p>1.20.1(Yarn) 里的挂点是 {@code SoundLoader#loadStreamed(Identifier, boolean)}；
 * 26.2 里 SoundLoader 这个类没了 ✗，等价入口变成了
 * {@code SoundBufferLibrary#getStream(Identifier, boolean)}（javap 实测 ✓）。
 *
 * <p>引擎按 Identifier 来要流：只要那份 id 在我们的 {@link StreamRegistry} 里，
 * 就把我们自己实现的 {@link AudioStream}（PcmAudioStream）交出去 ——
 * 于是声音依旧由原版引擎播放（定位衰减、音量滑块、暂停、停止逻辑全部沿用），
 * 只是喂进去的 PCM 来自网易云。
 */
@Mixin(SoundBufferLibrary.class)
public class SoundLoaderMixin {
	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void clouddisc$getStream(Identifier id, boolean repeatInstantly,
			CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		try {
			AudioStream stream = StreamRegistry.take(id);
			if (stream != null) {
				cir.setReturnValue(CompletableFuture.completedFuture(stream));
			}
		} catch (Throwable t) {
			// 出错就让原版走它自己的逻辑，绝不因为我们而让声音引擎炸掉
		}
	}
}