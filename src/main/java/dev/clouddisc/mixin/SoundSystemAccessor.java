package dev.clouddisc.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 读 26.2 里 {@code SoundEngine} 的声音实例 → 通道 映射表。
 *
 * <p>1.20.1(Yarn) 里这张表是 {@code SoundSystem#sources}（{@code Map<SoundInstance, Channel.SourceManager>}）；
 * 26.2 官方名变成 {@code SoundEngine#instanceToChannel}
 * （javap 实测：{@code Map<SoundInstance, ChannelAccess$ChannelHandle>}）。
 * 物理声效要靠它按"声音实例"反查出真正在播放的那个 OpenAL 音源。
 */
@Mixin(SoundEngine.class)
public interface SoundSystemAccessor {
	@Accessor("instanceToChannel")
	Map<SoundInstance, ChannelAccess.ChannelHandle> clouddisc$instanceToChannel();
}