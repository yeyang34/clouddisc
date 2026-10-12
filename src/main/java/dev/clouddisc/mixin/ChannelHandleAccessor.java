package dev.clouddisc.mixin;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 26.2 里 {@code ChannelAccess$ChannelHandle#channel}（javap 实测：private Channel channel）。
 * 拿到它就能进而读 {@code Channel#source}，实现"声音实例 → OpenAL 音源 id"的精确反查。
 */
@Mixin(ChannelAccess.ChannelHandle.class)
public interface ChannelHandleAccessor {
	@Accessor("channel")
	Channel clouddisc$channel();
}