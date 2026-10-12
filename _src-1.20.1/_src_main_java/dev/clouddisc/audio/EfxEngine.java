package dev.clouddisc.audio;

import dev.clouddisc.CloudDiscClient;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

/**
 * OpenAL EFX 资源池 —— 把"物理声效"直接挂到<b>我们自己那个声源</b>上。
 *
 * <p><b>为什么要走 EFX（而不是在 PCM 上做 DSP）</b>：PCM 路径上做效果，参数一定跟着音频块走
 * （我们每块约 4096 样本 ≈ 85ms，再加引擎预队列 4 秒），所以"隔墙变闷"永远慢半拍。
 * EFX 是引擎/驱动在<b>混音时</b>直接读声源参数做的，参数写进去<b>下一个混音块就生效</b>
 * —— 这就是"延迟降到 1 tick"的根本原因。
 *
 * <p><b>思路参考 Sound Physics Remastered（GPL），但代码完全自己写（本项目 MIT）</b>：
 * 只借鉴"给声源挂 AL_DIRECT_FILTER + AL_AUXILIARY_SEND_FILTER 到带 EAXReverb 的 aux slot"
 * 这个机制与参数形状；资源枚举、错误处理、状态缓存、回退策略都是本项目的实现。
 *
 * <p><b>只动我们自己的声源</b>：{@link #applyToSource} 的调用方只会传
 * {@code CloudDiscSoundInstance} 对应的 source id，其余声音一个字节都不碰。
 *
 * <p>线程：{@link #init()} 在声音引擎线程（SoundSystem#start 的注入点）执行；
 * {@link #applyToSource} 在主线程（客户端 tick）执行。OpenAL 自己是线程安全的，
 * 且原版也在主线程调 {@code alListener3f}（{@code SoundSystem#updateListenerPosition}），
 * 所以这两条路径都是原版已经在用的用法。
 *
 * <p><b>任何一步失败 → 永久回退 DSP</b>（{@link Acoustics#filterMono}），出声永远优先于效果。
 */
public final class EfxEngine {
	/** 最多 4 段混响发送（与 SPR 的 4 个 aux slot 同量级）。 */
	public static final int MAX_BANDS = 4;

	private enum State {
		/** 还没试过（等声音引擎加载完） */
		IDLE,
		OK,
		/** 试过了、失败，永久回退 DSP */
		FAILED
	}

	private static volatile State state = State.IDLE;
	private static volatile String status = "尚未初始化（等声音引擎加载完成）";
	private static volatile int maxAuxSends = 0;
	/** 实际可用的发送段数 = min(4, ALC_MAX_AUXILIARY_SENDS)。可能是 0~4。 */
	private static volatile int bands = 0;

	private static final int[] auxSlot = new int[MAX_BANDS];
	private static final int[] reverb = new int[MAX_BANDS];
	private static final int[] sendFilter = new int[MAX_BANDS];
	private static int directFilter = 0;

	/** 已写入声源的参数缓存：只有在"变化超过阈值"或换了声源时才重新下发。 */
	private static int lastSourceId = 0;
	private static float lastDirectCutoff = -1.0f;
	private static float lastDirectGain = -1.0f;
	private static final float[] lastSendGain = new float[MAX_BANDS];
	private static final float[] lastSendCutoff = new float[MAX_BANDS];
	/** 强制重下发的时间戳（AL 侧可能因为设备/上下文重建而丢失设置）。 */
	private static long lastForceTick = Long.MIN_VALUE / 2;
	private static final float EPS = 0.008f;

	/** 每个混响段的 EAXReverb 参数。数值自己推，不抄 SPR 的配置表。
	 * <p>0.12.6：默认值整体上调（gain 0.28→0.32、decayTime 1.2→1.6、
	 * reflectionsGain 0.3→0.4、lateReverbGain 0.5→0.65）——
	 * 这几个值只在"第一轮评估写进去之前"生效，实际参数由 {@code Acoustics.traceReverb} 每轮推。 */
	public static final class Reverb {
		public float gain = 0.32f;
		public float gainHF = 0.85f;
		public float decayTime = 1.6f;
		public float decayHFRatio = 0.6f;
		public float reflectionsGain = 0.4f;
		public float lateReverbGain = 0.65f;
		public float lateReverbDelay = 0.02f;
		public float density = 1.0f;
		public float diffusion = 1.0f;
		public float airAbsorptionGainHF = 0.994f;
	}

	private EfxEngine() {
	}

	public static boolean isAvailable() {
		return state == State.OK && bands > 0;
	}

	/** 实际可用的混响段数（0~4）。参数层用它决定要用几段。 */
	public static int bands() {
		return bands;
	}

	public static String status() {
		return status;
	}

	public static int maxAuxSends() {
		return maxAuxSends;
	}

	/**
	 * 在"声音引擎刚建好 OpenAL 上下文"的那一刻创建 EFX 资源。
	 * <p>调用点见 {@code dev.clouddisc.mixin.SoundSystemMixin}（挂在 {@code SoundSystem#start}
	 * 里 {@code SoundListener#init()} 之前 —— 这正是 SPR 用的挂点，此时 ALC 上下文已 current）。
	 * <p><b>绝不抛异常</b>：任何失败都只记日志 + 永久回退。
	 */
	public static void init() {
		if (state == State.OK) {
			return; // 设备切换会重新走这里；已有资源就复用
		}
		state = State.FAILED; // 先悲观，成功再改回来 —— 避免并发重复初始化
		try {
			long ctx = ALC10.alcGetCurrentContext();
			if (ctx == 0L) {
				fail("当前线程没有 current 的 OpenAL 上下文");
				return;
			}
			long device = ALC10.alcGetContextsDevice(ctx);
			if (device == 0L) {
				fail("取不到 OpenAL 设备句柄");
				return;
			}
			if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
				fail("设备不支持 ALC_EXT_EFX（缺少 EFX 扩展）");
				return;
			}
			maxAuxSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
			bands = Math.max(0, Math.min(MAX_BANDS, maxAuxSends));
			if (bands == 0) {
				fail("ALC_MAX_AUXILIARY_SENDS = 0，无法建立混响辅助发送");
				return;
			}

			// ① 辅助效果槽 + EAXReverb 效果器
			for (int i = 0; i < bands; i++) {
				auxSlot[i] = EXTEfx.alGenAuxiliaryEffectSlots();
				if (auxSlot[i] == 0) {
					fail("alGenAuxiliaryEffectSlots 失败（第 " + i + " 个）");
					return;
				}
				// 允许驱动在该槽位对"发送增益/发送高频"做自动处理
				EXTEfx.alAuxiliaryEffectSloti(auxSlot[i], EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, 1);
				reverb[i] = EXTEfx.alGenEffects();
				if (reverb[i] == 0) {
					fail("alGenEffects 失败（第 " + i + " 个）");
					return;
				}
				EXTEfx.alEffecti(reverb[i], EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
			}

			// ② 直通低通 + 每段一个发送滤波器
			directFilter = EXTEfx.alGenFilters();
			if (directFilter == 0) {
				fail("alGenFilters 失败（直通滤波器）");
				return;
			}
			EXTEfx.alFilteri(directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			for (int i = 0; i < bands; i++) {
				sendFilter[i] = EXTEfx.alGenFilters();
				if (sendFilter[i] == 0) {
					fail("alGenFilters 失败（发送滤波器 " + i + "）");
					return;
				}
				EXTEfx.alFilteri(sendFilter[i], EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			}

			int err = AL10.alGetError();
			if (err != AL10.AL_NO_ERROR) {
				fail("创建 EFX 资源时 OpenAL 报错 code=" + err);
				return;
			}

			state = State.OK;
			status = "EFX 可用（" + bands + " 段发送，ALC_MAX_AUXILIARY_SENDS=" + maxAuxSends + "）";
			CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: EFX 就绪 —— {}｜auxSlot={} reverb={} directFilter={} sendFilter={}",
					status, java.util.Arrays.toString(java.util.Arrays.copyOf(auxSlot, bands)),
					java.util.Arrays.toString(java.util.Arrays.copyOf(reverb, bands)),
					directFilter, java.util.Arrays.toString(java.util.Arrays.copyOf(sendFilter, bands)));
		} catch (Throwable t) {
			fail("初始化 EFX 时抛异常: " + t);
		}
	}

	/** 万一 {@link #init()} 的挂点没被触发（例如引擎启动路径变了），在主线程补一次。 */
	private static volatile long lastRetryTick = Long.MIN_VALUE / 2;

	public static void ensureInit(long nowTick) {
		if (state != State.IDLE) {
			return;
		}
		if (nowTick - lastRetryTick < 40L) {
			return;
		}
		lastRetryTick = nowTick;
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: 声音引擎的 EFX 挂点没触发，改在主线程补一次初始化");
		init();
	}

	private static void fail(String why) {
		state = State.FAILED;
		bands = 0;
		status = why;
		CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: EFX 不可用（{}）→ 永久回退到自研 DSP 低通/混响；声音播放不受影响", why);
	}

	/** 标记为"上次标记 IDLE"以便重试（设备切换时用）。 */
	public static void invalidate() {
		state = State.IDLE;
		bands = 0;
		lastSourceId = 0;
	}

	/** 把某个混响段的 EAXReverb 参数灌进效果器并绑到 aux slot（只在调用方判定"变化够大"时才会调）。 */
	public static void setReverb(int band, Reverb p) {
		if (!isAvailable() || band < 0 || band >= bands) {
			return;
		}
		try {
			int fx = reverb[band];
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_DENSITY, p.density);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_DIFFUSION, p.diffusion);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_GAIN, p.gain);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_GAINHF, p.gainHF);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_DECAY_TIME, p.decayTime);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, p.decayHFRatio);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, p.reflectionsGain);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, p.lateReverbGain);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, p.lateReverbDelay);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, p.airAbsorptionGainHF);
			EXTEfx.alEffectf(fx, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0.0f);
			// 把效果器绑到槽位（这一步之后槽位才真的有混响）
			EXTEfx.alAuxiliaryEffectSloti(auxSlot[band], EXTEfx.AL_EFFECTSLOT_EFFECT, fx);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 设置混响参数失败（第 {} 段），本轮跳过: {}", band, t.toString());
		}
	}

	/**
	 * 把参数写进我们自己的声源。
	 *
	 * @param sourceId     OpenAL source id（必须是我们自己那条声音的）
	 * @param directCutoff 直通高频增益 0..1（1 = 完全通透）
	 * @param directGain   直通总增益 0..1
	 * @param sendGain     每段混响发送增益 0..1（长度 ≥ {@link #bands()}）
	 * @param sendCutoff   每段混响发送的高频增益 0..1
	 */
	public static void applyToSource(int sourceId, float directCutoff, float directGain, float[] sendGain, float[] sendCutoff) {
		if (!isAvailable() || sourceId == 0) {
			return;
		}
		try {
			boolean newSource = sourceId != lastSourceId;
			boolean force = newSource;
			if (force) {
				// 换声源：把"自动增益"关掉，让写进去的值就是最终值（否则驱动会按距离再改一遍）
				AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
				AL10.alSourcei(sourceId, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO, AL10.AL_FALSE);
				AL10.alSourcei(sourceId, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
				lastSourceId = sourceId;
				lastDirectCutoff = -1.0f;
				lastDirectGain = -1.0f;
				for (int i = 0; i < MAX_BANDS; i++) {
					lastSendGain[i] = -1.0f;
					lastSendCutoff[i] = -1.0f;
				}
			}

			float dc = clamp01(directCutoff);
			float dg = clamp01(directGain);
			if (force || Math.abs(dc - lastDirectCutoff) > EPS || Math.abs(dg - lastDirectGain) > EPS) {
				EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, dg);
				EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, dc);
				AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, directFilter);
				lastDirectCutoff = dc;
				lastDirectGain = dg;
			}

			for (int i = 0; i < bands; i++) {
				float g = clamp01(sendGain != null && i < sendGain.length ? sendGain[i] : 0.0f);
				float c = clamp01(sendCutoff != null && i < sendCutoff.length ? sendCutoff[i] : 1.0f);
				if (force || Math.abs(g - lastSendGain[i]) > EPS || Math.abs(c - lastSendCutoff[i]) > EPS) {
					EXTEfx.alFilterf(sendFilter[i], EXTEfx.AL_LOWPASS_GAIN, g);
					EXTEfx.alFilterf(sendFilter[i], EXTEfx.AL_LOWPASS_GAINHF, c);
					// 段号 i ↔ 发送序号 i：槽位与序号固定绑定，避免每 tick 改动发送拓扑
					AL11.alSource3i(sourceId, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot[i], i, sendFilter[i]);
					lastSendGain[i] = g;
					lastSendCutoff[i] = c;
				}
			}
			logAlError("写入声源参数");
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 写 EFX 参数失败 → 本轮跳过（不影响播放）: {}", t.toString());
		}
	}

	/**
	 * 把这条声源上的 EFX 全部摘掉，恢复"干净直通"。
	 *
	 * <p><b>为什么必须有它</b>：参数是一旦写上去就一直留在声源上的。
	 * 如果玩家在歌曲播放途中把"物理声效"总开关关掉，我们只是不再往下写，
	 * 声源上那个低通滤波<b>还会继续生效</b> —— 表现就是"关了还是闷"。
	 */
	public static void bypassSource(int sourceId) {
		if (!isAvailable() || sourceId == 0) {
			return;
		}
		try {
			AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
			for (int i = 0; i < bands; i++) {
				AL11.alSource3i(sourceId, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, i, EXTEfx.AL_FILTER_NULL);
			}
			if (sourceId == lastSourceId) {
				lastSourceId = 0;
			}
			logAlError("摘掉 EFX");
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 摘掉 EFX 失败（不影响播放）: {}", t.toString());
		}
	}

	/** 打印（并清掉）OpenAL 错误队列。只在 debug 打开时刷屏。 */
	private static void logAlError(String what) {
		int err = AL10.alGetError();
		if (err != AL10.AL_NO_ERROR) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: OpenAL 报错 code={} @ {}", err, what);
		}
	}

	private static float clamp01(float v) {
		return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
	}
}
