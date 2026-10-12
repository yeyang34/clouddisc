package dev.clouddisc.mixin;

import net.minecraft.client.sound.Source;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 {@link Source} 里那个 OpenAL source id（yarn 名 {@code pointer}）读出来。
 *
 * <p>与 {@link SourceMixin} 里的 {@code @Shadow} 读的是同一个字段：那边用于"进入 play 时捕获"，
 * 这边用于"每 tick 从声源管理器反查"，两条路都能拿到 id，互为兜底。
 */
@Mixin(Source.class)
public interface SourceAccessor {
	@Accessor("pointer")
	int clouddisc$pointer();
}
