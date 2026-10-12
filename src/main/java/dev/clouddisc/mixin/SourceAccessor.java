package dev.clouddisc.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 26.2 里 {@code com.mojang.blaze3d.audio.Channel} 的 OpenAL source id 读出来。
 *
 * <p>1.20.1(Yarn) 里这个字段在 {@code net.minecraft.client.sound.Source#pointer}；
 * 26.2 官方把音源收进了 {@code Channel}，字段名就叫 {@code source}
 * （javap 实测：{@code private final int source;}）——物理声效要靠它把 EFX 滤波器挂到正确的音源上。
 */
@Mixin(Channel.class)
public interface SourceAccessor {
	@Accessor("source")
	int clouddisc$source();
}