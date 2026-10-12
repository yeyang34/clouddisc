package dev.clouddisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.JukeboxSongPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读 26.2 里 {@code JukeboxSongPlayer#blockPos}（javap 实测 private final BlockPos blockPos ✓）。 */
@Mixin(JukeboxSongPlayer.class)
public interface JukeboxSongPlayerAccessor {
	@Accessor("blockPos")
	BlockPos clouddisc$blockPos();
}