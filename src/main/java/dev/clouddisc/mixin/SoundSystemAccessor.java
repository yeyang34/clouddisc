package dev.clouddisc.mixin;

import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 只为物理声效服务：把 {@code SoundSystem#sources}（"声音实例 → 声源管理器"）暴露出来。
 *
 * <p><b>为什么需要它</b>：EFX 参数必须写进"我们自己那条声音对应的 OpenAL source"。
 * 只靠"最近一次 {@code Source#play()} 记下的 id"在多台唱片机同时播放、或 source id 被
 * 驱动回收重用时会写错对象（那就等于去动别人的声音，是本项目的硬红线）。
 * 有这张表就能精确地拿到 "<b>我们的</b> {@link SoundInstance} → {@link Channel.SourceManager}"。
 */
@Mixin(SoundSystem.class)
public interface SoundSystemAccessor {
	@Accessor("sources")
	Map<SoundInstance, Channel.SourceManager> clouddisc$sources();
}
