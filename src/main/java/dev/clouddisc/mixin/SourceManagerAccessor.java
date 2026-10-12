package dev.clouddisc.mixin;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Source;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 配合 {@link SoundSystemAccessor}：拿到某个保活声源真正持有的 {@link Source}。 */
@Mixin(Channel.SourceManager.class)
public interface SourceManagerAccessor {
	@Accessor("source")
	Source clouddisc$source();
}
