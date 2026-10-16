package dev.clouddisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.JukeboxSongPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 26.2 里 {@code JukeboxSongPlayer} 的私有状态（javap 实测 ✓）：
 * <ul>
 *   <li>{@code blockPos} —— 用来从客户端/服务端 Level 反查方块实体（判断这张碟是不是"云唱片" ✓）</li>
 *   <li>{@code ticksSinceSongStarted} —— 原版用它判断"放完了没有"（{@code JukeboxSong#hasFinished} ✗）</li>
 * </ul>
 * 时长解锁的做法（与原 jukeboxlib 同思路 ✓）：
 * 对云唱片【把这个计数按住不动】✓ 于是原版永远认为"还没放完"✗ 不会 stop ✗
 * 但 tick 里其它逻辑（音符粒子 ✓ 播放事件 ✓）全部照常跑 ✓✓
 */
@Mixin(JukeboxSongPlayer.class)
public interface JukeboxSongPlayerAccessor {
	@Accessor("blockPos")
	BlockPos clouddisc$blockPos();

	@Accessor("ticksSinceSongStarted")
	long clouddisc$getTicksSinceSongStarted();

	@Accessor("ticksSinceSongStarted")
	void clouddisc$setTicksSinceSongStarted(long ticks);
}