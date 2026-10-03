package dev.clouddisc.mixin;

import dev.clouddisc.jukebox.StreamRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 音频注入点。
 *
 * <p>1.20.1 的 {@code SoundInstance} 里<b>没有</b> {@code getStream/resolve}，
 * 真正取流的是 {@code SoundLoader#loadStreamed(Identifier, boolean)}
 * （Yarn 名；Mojang 名 {@code SoundBufferLibrary#getStream}）。
 * 我们在这里把"自己注册过的 stream id"换成自己的 PCM 流，
 * 于是 MC 的整套音频链路（OpenAL source、定位衰减、RECORDS 音量、停止/暂停）原封不动地为我们工作。
 */
@Environment(EnvType.CLIENT)
@Mixin(SoundLoader.class)
public abstract class SoundLoaderMixin {
	@Inject(method = "loadStreamed", at = @At("HEAD"), cancellable = true)
	private void clouddisc$loadStreamed(Identifier id, boolean repeatInstantly,
			CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		AudioStream stream = StreamRegistry.take(id);
		if (stream != null) {
			cir.setReturnValue(CompletableFuture.completedFuture(stream));
		}
	}
}
