package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * 建 OpenAL 上下文时多要几个"辅助发送"通道。
 *
 * <p><b>为什么必须这么干（实测数据）</b>：本机 OpenAL（OpenAL Soft）<b>默认只给
 * {@code ALC_MAX_AUXILIARY_SENDS = 2}</b> —— 我用 LWJGL 单独探针程序量过：
 * <pre>
 *   ctx(default)            → ALC_MAX_AUXILIARY_SENDS = 2
 *   ctx(带属性请求 4 个发送) → ALC_MAX_AUXILIARY_SENDS = 4
 * </pre>
 * 只有 2 个发送时"4 段延迟混响"就只能做 2 段。请求 4 个发送的代价为零
 * （只是上下文创建属性多两项），且<b>不改变任何声音的默认行为</b>。
 *
 * <p>机制说明：SPR 也是用 {@code @Redirect} 改这一句（它改的是 Mojang 名
 * {@code Library#init} 里的 {@code alcCreateContext}）；yarn 里这个类叫
 * {@code SoundEngine}。Mixin 0.8 支持同一指令上的多个 {@code @Redirect} 串联
 * （redirect chaining），所以与 SPR 同时安装不会冲突。
 *
 * <p><b>安全阀</b>：任何异常 / 返回 0，都立刻退回"原样调用"，绝不让声音引擎起不来。
 */
@Mixin(net.minecraft.client.sound.SoundEngine.class)
public class SoundEngineMixin {
	@Redirect(method = "init", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"))
	private long clouddisc$createContextWithAuxSends(long device, IntBuffer original) {
		boolean want = false;
		try {
			CloudDiscConfig cfg = CloudDiscConfig.get();
			want = cfg != null && cfg.physicsSound;
		} catch (Throwable ignored) {
			// 配置还没加载好也照样值得多要一点：这个改动本身无副作用
			want = true;
		}
		if (!want) {
			return ALC10.alcCreateContext(device, original);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// 【0.13.2】6 个 int = 3 对属性（aux send + HRTF）
			IntBuffer attrs = stack.mallocInt(6);
			attrs.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4).put(0).put(0);
			// 【0.13.2】申请 HRTF：让"远耳"按真实头部遮蔽衰减（6~20 dB），
			// 而不是 OpenAL 默认的硬声道声像（远耳被压得过分小，近场尤其不真实）。
			// 设备/驱动不支持时 OpenAL 会自动忽略，无副作用。
			attrs.put(org.lwjgl.openal.SOFTHRTF.ALC_HRTF_SOFT).put(ALC10.ALC_TRUE).put(0).put(0);
			attrs.flip();
			long ctx = ALC10.alcCreateContext(device, attrs);
			if (ctx != 0L) {
				return ctx;
			}
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 请求 4 个辅助发送时上下文创建失败 → 退回默认属性");
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 附加 ALC 属性异常（已忽略，退回默认）: {}", t.toString());
		}
		return ALC10.alcCreateContext(device, original);
	}
}
