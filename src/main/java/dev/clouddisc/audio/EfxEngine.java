package dev.clouddisc.audio;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
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
	/**
	 * "变化不足就不写"的阈值。
	 * <p>0.12.7：0.008 → <b>0.02</b>（约 0.17 dB）。0.008 太小，等于每个 tick 都在写
	 * OpenAL 的滤波参数 —— 这种"每 50ms 改一次增益"的写法在参数连续变化时会引入
	 * zipper（拉链）噪声，听感就是轻微的沙沙/爆裂。0.02 仍在不可闻范围内。
	 */
	private static final float EPS = 0.02f;
	/** 0.12.7：参数写入限频（刻）。每 3 刻 = 150ms 一次，配合按时间平滑，听感不变但写入次数降 2/3。 */
	private static final int WRITE_INTERVAL_TICKS = 3;
	private static long lastWriteTick = Long.MIN_VALUE / 2;
	/** 0.12.7：参数阶跃诊断日志的限频（刻，20 = 1 秒）与阈值倍数。 */
	private static long lastJumpLogTick = Long.MIN_VALUE / 2;
	private static final float JUMP_FACTOR = 2.0f;
	/**
	 * 已经绑到槽位上的那组参数（只用来判断"要不要重新把 effect 绑回 slot"）。
	 * <p>正常路径<b>只有参数变化</b>（{@code alEffectf}），<b>不再每 tick 重设 effect 到 slot</b>：
	 * OpenAL Soft 会立刻把效果器参数变化应用到正在播放的声音上，反复
	 * {@code alAuxiliaryEffectSloti(..., AL_EFFECTSLOT_EFFECT, ...)} 反而会让混响尾音被重置。
	 * 只有变化特别大（换环境那种）才重绑一次做"重同步"。
	 */
	private static final Reverb[] slotBound = {new Reverb(), new Reverb(), new Reverb(), new Reverb()};
	private static final boolean[] slotBoundValid = new boolean[MAX_BANDS];
	/** 能量预算用的暂存（只在主线程用，避免每 tick 分配）。 */
	private static final float[] SEND_GAIN_SCRATCH = new float[MAX_BANDS];
	private static final float[] SEND_CUTOFF_SCRATCH = new float[MAX_BANDS];
	/** 0.12.9：预算计算结果的暂存（{@link #energyBudget} 写进这里）。 */
	private static final float[] BUDGET_SCRATCH = new float[4];
	/**
	 * 0.12.9 <b>Bug B</b>：能量预算对<b>直通</b>的最大压降（0.5 = 直通最多被压到一半）。
	 * <p>见 {@link #energyBudget}：发送过量时优先削发送（它才是超量的原因），
	 * 直通最多降到一半 —— 用户 0.12.9 的验收要求是"预算不把直通压到 0.5 倍以下"。
	 */
	private static final float BUDGET_MIN_DIRECT = 0.5f;
	/** 0.12.9 诊断：最近一次写入时的直通倍率（1.0 = 没削）与"有效发送能量" Σ(gain×cutoff)。 */
	private static volatile float lastBudget = 1.0f;
	private static volatile float lastSendEnergy = 0.0f;
	/** 0.12.9：「能量预算」诊断日志的限频时间戳（刻）。 */
	private static long lastBudgetLogTick = Long.MIN_VALUE / 2;
	/**
	 * 0.12.9：湿声（混响）的默认输出增益 —— 与 {@link Reverb#gain} 的初值一致
	 * （{@code Acoustics.traceReverb} 每段也把它写成 0.32）。
	 * <p>预算为什么还要乘它：发进 aux slot 的信号<b>还要经过 EAXReverb 自己的 gain</b> 才与直通相加，
	 * 所以"这一路发送真正贡献了多少能量"是
	 * {@code sendGain × sendCutoff × 混响输出增益}。
	 * 不乘它就会高估混响、把直通压得过多 —— 现场日志第 3 行（通畅、开阔地）里
	 * 直通增益被压到 0.428 倍，听感就是"整体小声"。
	 */
	private static final float DEFAULT_WET_GAIN = 0.32f;

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
				// 0.12.7：**在这里把效果器绑到槽位一次**（以前是每次改参数都重绑一遍，
				// 反复 alAuxiliaryEffectSloti 会把混响尾音重置，听起来像小爆音）。
				EXTEfx.alAuxiliaryEffectSloti(auxSlot[i], EXTEfx.AL_EFFECTSLOT_EFFECT, reverb[i]);
				slotBoundValid[i] = false; // 还没记任何参数 → 第一次 setReverb 会写
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

	/**
	 * <b>0.12.9 修 Bug B：能量预算（纯函数；生产路径 {@link #applyToSource} 与
	 * {@code tools/PhysicsParamsTest.java} 共用同一个实现）。</b>
	 *
	 * <h2>旧公式（0.12.7~0.12.8）错在哪</h2>
	 * <pre>
	 *   total  = 直通增益 + Σ sendGain[i]                 // ← 把"被发送滤波器掐死的发送"当成满能量
	 *   budget = min(1, 1/total)
	 *   直通增益 ×= budget；sendGain[i] ×= budget
	 * </pre>
	 * 现场日志：{@code 直通增益=0.153、sendGain=[1.000, 0.313, 0, 0]} ⇒ total = 1.466 ⇒ budget = 0.682。
	 * 可那时 4 路发送的 {@code sendCutoff = 0.001}（= 一点声音都没送出去），却照样把直通增益
	 * 从 0.153 压到 0.104（-3.3 dB）—— 用户听成"又闷又小声 / 整体全部效果都没了"。
	 *
	 * <h2>新公式（0.12.9）</h2>
	 * <pre>
	 *   E      = Σ (sendGain[i] × sendCutoff[i] × wetGain)   // 有效湿声能量：被低通/混响增益缩掉的都不占预算
	 *   total  = 直通增益 + E
	 *   sendBudget   = total &gt; 1 ? 1/total : 1               // 发送按真实预算缩（超量的是它）
	 *   directBudget = max(sendBudget, BUDGET_MIN_DIRECT)     // 直通最多降到一半
	 * </pre>
	 *
	 * @param wetGain    混响自身的输出增益（生产路径传当前段上写着的 {@code AL_EAXREVERB_GAIN}，
	 *                   默认 {@link #DEFAULT_WET_GAIN}）
	 * @param out 由调用方提供的输出数组（长度 ≥ 4），避免每 tick 分配：
	 *            {@code [0]=直通倍率}、{@code [1]=发送倍率}、{@code [2]=有效发送能量}、
	 *            {@code [3]=直通+有效发送（缩放前）}
	 */
	public static void energyBudget(float directGain, float[] sendGain, float[] sendCutoff, int sendBands, float wetGain,
			float[] out) {
		float wet = clamp01(wetGain);
		float sumSend = 0.0f;
		for (int i = 0; i < MAX_BANDS; i++) {
			if (i >= sendBands) {
				continue; // 只有真的接上的段才占预算（可用段数可能只有 1~2）
			}
			float g = clamp01(at(sendGain, i, 0.0f));
			float c = clamp01(at(sendCutoff, i, 1.0f));
			sumSend += g * c * wet; // ← 0.12.9 的关键：乘上滤波器高频增益与混响自身增益
		}
		float dg = clamp01(directGain);
		float total = dg + sumSend;
		float sendBudget = total > 1.0f ? 1.0f / total : 1.0f;
		out[0] = Math.max(sendBudget, BUDGET_MIN_DIRECT);
		out[1] = sendBudget;
		out[2] = sumSend;
		out[3] = total;
	}

	/** 第 0 段混响当前写着的输出增益（还没写过参数时用 {@link #DEFAULT_WET_GAIN}）。 */
	private static float currentWetGain() {
		if (slotBoundValid[0]) {
			return clamp01(slotBound[0].gain);
		}
		return DEFAULT_WET_GAIN;
	}

	private static float at(float[] a, int i, float fallback) {
		return a != null && i < a.length ? a[i] : fallback;
	}

	/** 0.12.9 诊断：最近一次写入用的直通倍率（1.0 = 没削）。日志字段 `能量预算(直通倍率)`。 */
	public static float lastBudget() {
		return lastBudget;
	}

	/** 0.12.9 诊断：最近一次写入时的"有效发送能量" Σ(sendGain×sendCutoff)。 */
	public static float lastSendEnergy() {
		return lastSendEnergy;
	}

	/**
	 * 把某个混响段的 EAXReverb 参数灌进效果器（只在调用方判定"变化够大"时才会调）。
	 *
	 * <p><b>0.12.7</b>：正常路径<b>只改参数</b>（{@code alEffectf}），不再每次都把 effect 重设到 slot
	 * （那一步会把混响的内部状态/尾音重置）。只有参数变化特别大时才重绑一次做"重同步"。
	 */
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
			// 参数变化特别大（换环境）才重绑一次；平时**不重绑**，避免重置混响尾音。
			if (!slotBoundValid[band] || boundDiffersALot(slotBound[band], p)) {
				EXTEfx.alAuxiliaryEffectSloti(auxSlot[band], EXTEfx.AL_EFFECTSLOT_EFFECT, fx);
				copyInto(p, slotBound[band]);
				slotBoundValid[band] = true;
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 设置混响参数失败（第 {} 段），本轮跳过: {}", band, t.toString());
		}
	}

	/** 只有"换环境"级别的变化才值得重绑 effect 到 slot（重绑会重置混响尾音）。 */
	private static boolean boundDiffersALot(Reverb a, Reverb b) {
		return Math.abs(a.gain - b.gain) > 0.15f
				|| Math.abs(a.decayTime - b.decayTime) > 0.80f
				|| Math.abs(a.decayHFRatio - b.decayHFRatio) > 0.20f
				|| Math.abs(a.lateReverbGain - b.lateReverbGain) > 0.20f;
	}

	/** 把一批参数拷进另一个对象（不分配；只在本文件内用）。 */
	private static void copyInto(Reverb from, Reverb to) {
		to.gain = from.gain;
		to.gainHF = from.gainHF;
		to.decayTime = from.decayTime;
		to.decayHFRatio = from.decayHFRatio;
		to.reflectionsGain = from.reflectionsGain;
		to.lateReverbGain = from.lateReverbGain;
		to.lateReverbDelay = from.lateReverbDelay;
		to.density = from.density;
		to.diffusion = from.diffusion;
		to.airAbsorptionGainHF = from.airAbsorptionGainHF;
	}

	/**
	 * 把参数写进我们自己的声源。
	 *
	 * <h2>0.12.7 的三处改动（都是为了"播放中途不要有台阶"）</h2>
	 * <ol>
	 *   <li><b>接线只做一次</b>：{@code AL_DIRECT_FILTER} 与 4 个
	 *       {@code AL_AUXILIARY_SEND_FILTER} 只在"换声源"那一次接上；之后播放期间
	 *       <b>只改滤波器数值</b>（{@code alFilterf}）。旧实现在数值变化时也重发
	 *       {@code alSource3i(..., AL_AUXILIARY_SEND_FILTER, ...)}，那是<b>改拓扑</b>，
	 *       播放中途做会有可闻的爆音/咔嗒声。</li>
	 *   <li><b>能量预算</b>：直通增益与所有发送增益一起按
	 *       {@code budget = min(1, 1/(直通+Σ发送))} 缩放，保证
	 *       {@code 直通 + Σ发送 ≤ 1.0}。EFX 的混响是在 OpenAL 混音器里与直通<b>相加</b>的，
	 *       0.12.6 又把发送基准提到 1.6、EAXReverb gain 提到 0.32 —— 合计很容易越过 0 dBFS
	 *       而被输出端削顶（听感就是"接触不良"的刺声）。有余量时不做任何衰减。
	 *       <p><b>0.12.9 修 Bug B</b>：上式用的是<b>没滤波的</b>发送增益，于是"被发送低通掐死、
	 *       一点声音都没送出去"的段照样占满预算，把直通增益压到 0.68 倍。现在改成
	 *       {@code E = Σ(sendGain×sendCutoff)}（有效发送能量），直通最多降到一半 —— 见
	 *       {@link #energyBudget}。</li>
	 *   <li><b>写入限频 + 更大阈值</b>：每 {@value #WRITE_INTERVAL_TICKS} 刻才写一次，
	 *       且单个参数变化 &lt; {@value #EPS} 就不写（旧值 0.008 太小，近似每 tick 都在写）。</li>
	 * </ol>
	 *
	 * @param sourceId     OpenAL source id（必须是我们自己那条声音的）
	 * @param directCutoff 直通高频增益 0..1（1 = 完全通透）
	 * @param directGain   直通总增益 0..1
	 * @param sendGain     每段混响发送增益 0..1（长度 ≥ {@link #bands()}）
	 * @param sendCutoff   每段混响发送的高频增益 0..1
	 * @param nowTick      当前游戏刻（写入限频用；不需要限频时可以传 0）
	 */
	public static void applyToSource(int sourceId, float directCutoff, float directGain, float[] sendGain,
			float[] sendCutoff, long nowTick) {
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
			} else if (nowTick != 0L && nowTick - lastWriteTick < WRITE_INTERVAL_TICKS) {
				return; // 限频：两次写入之间至少隔 WRITE_INTERVAL_TICKS 刻
			}
			lastWriteTick = nowTick;

			// ---- 能量预算（见 {@link #energyBudget} 与类注释 ②）----
			// 用预分配的暂存数组：applyToSource 只在客户端主线程调用（Acoustics.tick），
			// 不做每 tick 的堆分配。
			float[] sg = SEND_GAIN_SCRATCH;
			float[] sc = SEND_CUTOFF_SCRATCH;
			for (int i = 0; i < MAX_BANDS; i++) {
				sg[i] = clamp01(at(sendGain, i, 0.0f));
				sc[i] = clamp01(at(sendCutoff, i, 1.0f));
			}
			float dc = clamp01(directCutoff);
			float dg = clamp01(directGain);
			// 0.12.9 修 Bug B：预算按"有效湿声能量"Σ(gain×cutoff×混响增益) 算；
			// 发送按真实预算缩，直通最多降到一半（0.12.8 是"和无滤波的发送一起算总量"，
			// 于是 sendCutoff=0.001 的 4 路空发送照样把直通从 0.153 压到 0.104）。
			energyBudget(dg, sg, sc, bands, currentWetGain(), BUDGET_SCRATCH);
			float directBudget = BUDGET_SCRATCH[0];
			float sendBudget = BUDGET_SCRATCH[1];
			dg *= directBudget;
			for (int i = 0; i < MAX_BANDS; i++) {
				sg[i] *= sendBudget;
			}
			lastBudget = directBudget;
			lastSendEnergy = BUDGET_SCRATCH[2];
			logBudget(dg, lastSendEnergy, directBudget, sendBudget, nowTick);

			// ---- 直通滤波器：接线只在换声源时做一次，之后只改数值 ----
			if (force || Math.abs(dc - lastDirectCutoff) > EPS || Math.abs(dg - lastDirectGain) > EPS) {
				EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, dg);
				EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, dc);
				if (force) {
					AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, directFilter); // 拓扑：只此一次
				}
				logJump("直通", "GAINHF", lastDirectCutoff, dc, nowTick);
				logJump("直通", "GAIN", lastDirectGain, dg, nowTick);
				lastDirectCutoff = dc;
				lastDirectGain = dg;
			}

			// ---- 发送：同样是"接线一次、之后只改数值" ----
			for (int i = 0; i < bands; i++) {
				if (force || Math.abs(sg[i] - lastSendGain[i]) > EPS || Math.abs(sc[i] - lastSendCutoff[i]) > EPS) {
					EXTEfx.alFilterf(sendFilter[i], EXTEfx.AL_LOWPASS_GAIN, sg[i]);
					EXTEfx.alFilterf(sendFilter[i], EXTEfx.AL_LOWPASS_GAINHF, sc[i]);
					if (force) {
						// 段号 i ↔ 发送序号 i：槽位与序号固定绑定，播放期间不再改动
						AL11.alSource3i(sourceId, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot[i], i, sendFilter[i]);
					}
					logJump("发送" + i, "GAIN", lastSendGain[i], sg[i], nowTick);
					logJump("发送" + i, "GAINHF", lastSendCutoff[i], sc[i], nowTick);
					lastSendGain[i] = sg[i];
					lastSendCutoff[i] = sc[i];
				}
			}
			logAlError("写入声源参数");
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: 写 EFX 参数失败 → 本轮跳过（不影响播放）: {}", t.toString());
		}
	}

	/**
	 * <b>参数阶跃诊断</b>（{@code physicsSoundDebug=true} 时）：某个写进 OpenAL 的参数一次变化超过
	 * {@code 2 × EPS} 就打一行"旧值 / 新值 / 差值"，最多 1 秒一条。
	 * <p>用途：用户听到"bip / 刺声"时，对着时间点看这一行 —— 如果那一刻正好有一条大跳变，
	 * 就是我们的参数阶跃；如果没有任何跳变（或根本不是这段时间），那声音不是我们造成的。
	 */
	private static void logJump(String what, String param, float oldV, float newV, long nowTick) {
		if (oldV < -0.5f) {
			return; // 第一次写（旧值哨兵 -1）：不算"变化"
		}
		float diff = newV - oldV;
		if (Math.abs(diff) <= JUMP_FACTOR * EPS) {
			return;
		}
		if (!debugOn()) {
			return;
		}
		if (nowTick != 0L && nowTick - lastJumpLogTick < 20L) {
			return; // 限频：1 秒最多一条
		}
		lastJumpLogTick = nowTick;
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效·参数阶跃: {}.{} {} → {}（差 {}，阈值 {}）",
				what, param, fmt(oldV), fmt(newV), fmt(diff), fmt(JUMP_FACTOR * EPS));
	}

	private static boolean debugOn() {
		try {
			CloudDiscConfig cfg = CloudDiscConfig.get();
			return cfg != null && cfg.physicsSoundDebug;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * <b>0.12.9 新增：能量预算诊断</b>（{@code physicsSoundDebug=true} 时）。
	 * <p>只在"真的削了东西"时打，且 1 秒最多一条。现场核对方法：
	 * 日志里 {@code 直通增益} 是<b>预算之前</b>的值，乘上这一行的"直通倍率"才是真正写进声源的值。
	 */
	private static void logBudget(float directGainAfter, float sendEnergy, float directBudget, float sendBudget,
			long nowTick) {
		if (directBudget >= 1.0f - 1.0e-4f && sendBudget >= 1.0f - 1.0e-4f) {
			return; // 没削：不打（绝大多数情况）
		}
		if (!debugOn()) {
			return;
		}
		if (nowTick != 0L && nowTick - lastBudgetLogTick < 20L) {
			return;
		}
		lastBudgetLogTick = nowTick;
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效·能量预算: 有效发送能量={} 直通(削后)={} → 直通倍率={} 发送倍率={}",
				fmt(sendEnergy), fmt(directGainAfter), fmt(directBudget), fmt(sendBudget));
	}

	private static String fmt(float v) {
		return String.format(java.util.Locale.ROOT, "%.4f", v);
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
