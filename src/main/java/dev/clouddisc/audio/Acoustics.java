package dev.clouddisc.audio;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.mixin.SoundSystemAccessor;
import dev.clouddisc.mixin.SourceAccessor;
import dev.clouddisc.mixin.SourceManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.client.sound.Source;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.openal.AL10;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 唱片机的"物理声效"—— 思路参考 Sound Physics Remastered（只借鉴机制与公式形状，代码全部自写，本项目 MIT）。
 *
 * <h2>两层结构</h2>
 * <ol>
 *   <li><b>采集 + 参数层（主线程，每 tick）</b>：射线算出"遮挡 / 混响"，做按时间的平滑，
 *       然后<b>每个刻</b>把参数写进声源。</li>
 *   <li><b>应用层</b>：
 *     <ul>
 *       <li>首选 {@link EfxEngine}：OpenAL EFX 的直通低通 + 混响辅助发送。<b>延迟只跟 tick 有关</b>。</li>
 *       <li>EFX 不可用 → 自动回退到本类自研的 PCM DSP（{@link #filterMono}），
 *           出声永远优先于效果。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>为什么以前慢半拍</h2>
 * 旧实现只在 PCM 上做一阶低通，而每个 PCM 块约 4096 样本 ≈ 85ms，外加引擎预队列几秒
 * —— 参数"跟着音频块走"，所以拆了墙还闷好几秒。现在参数直接进 OpenAL 声源，
 * 引擎下一个混音块就生效。
 *
 * <h2>状态按声源分开</h2>
 * 多个唱片机同时播放时，各自的平滑状态/评估结果互相独立（{@link #STATES} 以 OpenAL source id 为键）。
 */
public final class Acoustics {
	// ---------------------------------------------------------------- 调参常量
	/** 遮挡射线评估的间隔（tick）：4 刻 = 5Hz。参数层仍然每 tick 平滑+下发。 */
	private static final int INTERVAL_TICKS = 4;
	/** 参数层的收敛时间（秒）——按时间算，不按调用次数。 */
	private static final float SMOOTH_SECONDS = 0.15f;
	// ---------------------------------------------------------------- 0.12.6 调参（"变闷要明显"）
	/**
	 * 遮挡累积值 → 截止的陡度 {@code cutoff = exp(-occ*k)}。
	 * <p>0.12.5 是固定 3.0；0.12.6 抽成配置 {@code physicsAbsorption}，默认 <b>4.5</b>。
	 * 只管"变闷"这一段（用户优先级最高的一条链路）。范围 {@value #ABSORPTION_MIN}~{@value #ABSORPTION_MAX}。
	 */
	private static final float ABSORPTION_DEFAULT = 4.5f;
	private static final float ABSORPTION_MIN = 2.0f;
	private static final float ABSORPTION_MAX = 9.0f;
	/**
	 * 直通<b>总增益</b>的指数：{@code gain = cutoff^0.2}（0.12.5 是 0.1）。
	 * <p>用户要求"不要只砍高频"：0.2 让一层石头墙后面的直通总增益也掉到 ≈0.40（约 -8 dB），
	 * 高频截止则掉到 ≈0.011（约 -39 dB）。
	 */
	private static final float DIRECT_GAIN_EXP = 0.2f;
	/**
	 * 完全被挡时的截止下限（越闷越小）。0.12.5 是 0.02 → 现在 <b>0.005</b>。
	 * <p>验收标准要求"完全被挡时 {@code AL_LOWPASS_GAINHF} ≤ 0.05"，0.005 留了足够余量。
	 */
	private static final float MIN_DIRECT_CUTOFF = 0.005f;
	/**
	 * 开阔度修正的系数（"同一片开阔空间里声音能绕过来"→ 直通不该被压死）。
	 * <p>形状仍是 SPR 的 {@code sqrt(averageSharedAirspace)*0.2}，但<b>加了门槛</b>：
	 * 见 {@link #OPENNESS_GATE_OCC}。
	 */
	private static final float OPENNESS_FLOOR_COEF = 0.2f;
	/**
	 * 开阔度修正的<b>门槛</b>：遮挡累积 ≥ 这个值时，开阔度完全不许把截止抬起来。
	 * <p><b>这是 0.12.5 "隔墙只稍微暗一点"的头号原因</b>：当时
	 * {@code cutoff = max(sqrt(shared)*0.2, exp(-occ*3))} 没有门槛，
	 * 站在开阔房间里隔一层石头墙时 {@code shared≈0.9} → 下限 0.194 →
	 * 一层石头墙算出来的 0.05 被直接顶到 <b>0.194</b>（正好是用户听到的"0.2~0.3"）。
	 * 现在 0.6：一层石头墙（occ=1.0）→ 下限 0，截止就是 0.011。
	 */
	private static final float OPENNESS_GATE_OCC = 0.6f;
	/** 偏移射线算作"一条通透通路"的遮挡阈值：≤ 它就认为这条线是通的。 */
	private static final double OPEN_PATH_OCC = 0.05;
	/** 偏移射线的总数（两端各 ±1 格的 2×2×2 = 8 个对角偏移）。 */
	private static final int OPEN_PATH_TOTAL = 8;
	/**
	 * 0.12.7：<b>放宽（漏音）的门槛与上限</b>。
	 * <p>0.12.6 的公式是 {@code 放宽 = 0.85 × min(1, 通路数/3)}，且直接 {@code occ = occMain × (1-放宽)}：
	 * <b>只要 3 条偏移通路通透，主射线算出来的遮挡就只剩 15%</b>。
	 * 问题在于"1 格厚的墙/一扇门"旁边本来就很容易凑出 3 条没被挡到的偏移线
	 * （偏移是整条线平移，擦着墙边、过门口、过墙角都会变成"通透"），
	 * 于是门关着（0.44）和门开着（0）在听感上都塌成"几乎不闷"。
	 * <p>现在两条约束：<b>①只有大多数偏移通路都通透（默认 ≥6/8）才放宽；②放宽幅度有上限（默认 40%）</b>。
	 * 达不到门槛 → <b>完全不放宽</b>。
	 */
	private static final int DEFAULT_OPEN_PATHS = 6;
	private static final int OPEN_PATHS_MIN = 1;
	private static final int OPEN_PATHS_MAX = OPEN_PATH_TOTAL;
	/** 默认的放宽幅度上限（可配 {@code physicsOcclusionRelax}）：最多把遮挡削到 60%。 */
	private static final double DEFAULT_RELAX_MAX = 0.40;
	private static final double RELAX_MAX_MIN = 0.0;
	private static final double RELAX_MAX_MAX = 0.60;

	private static final double AIR_START = 12.0;
	/** 沿连线最多穿过多少格（性能上限）。
	 * <p>注意这是"<b>格子数</b>"不是"格数"：斜射一条 20 格的线最多会穿过 3x20 个格子，
	 * 所以这里给得比最大听距更宽松，避免"远处的墙没算进来"这种静默错误。 */
	private static final int MAX_OCC_STEPS = 96;
	/** 遮挡累积值上限：再厚的墙也不会更闷（否则一个 10 格厚的地基会把增益压到听不见）。 */
	private static final double MAX_OCC = 3.0;
	/** 诊断日志间隔（tick）：200 刻 = 10 秒。 */
	private static final long LOG_INTERVAL_TICKS = 200L;
	/** 材质探针：每轮评估最多打几条。 */
	private static final int PROBE_MAX = 6;
	/** 材质探针的限频（刻）：20 刻 = 1 秒（评估是 5Hz，不限频会刷爆日志）。 */
	private static final long PROBE_INTERVAL_TICKS = 20L;

	/** DSP 回退路径用的截止频率映射区间（Hz）。 */
	private static final float MIN_CUTOFF_HZ = 450.0f;
	private static final float MAX_CUTOFF_HZ = 20000.0f;

	private static volatile boolean enabled = true;
	/** 上一次 tick 时总开关是不是开的（用来捕捉"开着 → 关掉"这个瞬间，好把 EFX 摘干净）。 */
	private static volatile boolean wasEnabled = false;

	// ---------------------------------------------------------------- 0.12.7：退场（关开关）
	/**
	 * 关掉总开关后，把参数平滑到"直通"需要多少刻（8 刻 = 0.4 秒）才真正摘掉 EFX。
	 * <p>为什么要这一步：{@code AL_DIRECT_FILTER = NULL} / 摘发送都是<b>拓扑突变</b>，
	 * 在"正闷着"的时候一刀摘掉，音色一步从闷跳回透亮 —— 这一下本身就是可闻的爆音。
	 * 现在先把截止/增益平滑推到 1/1、发送推到 0（时间常数仍是 {@link #SMOOTH_SECONDS}），
	 * 推完再摘，听感是"闷 → 慢慢变亮"，没有台阶。
	 */
	private static final int DISENGAGE_TICKS = 8;
	private static volatile int disengageSource = 0;
	private static volatile long disengageEndTick = Long.MIN_VALUE / 2;

	/** 参数写入的限频（刻）：0.12.7 起每 3 刻（150ms）才写一次 OpenAL，减少 zipper 噪声。 */
	private static final int WRITE_INTERVAL_TICKS = 3;

	// ---------------------------------------------------------------- 声源捕获
	private static volatile boolean nextIsOurs = false;
	private static volatile boolean pendingOurs = false;
	private static volatile int lastSourceId = 0;
	private static volatile long lastSourceTick = Long.MIN_VALUE / 2;
	private static volatile SoundSystem soundSystem;

	// ---------------------------------------------------------------- 每个声源的平滑状态
	private static final class State {
		float directCutoff = 1.0f;
		float directGain = 1.0f;
		final float[] sendGain = new float[EfxEngine.MAX_BANDS];
		final float[] sendCutoff = new float[EfxEngine.MAX_BANDS];
		// 目标值（射线评估的结果）
		float tDirectCutoff = 1.0f;
		float tDirectGain = 1.0f;
		final float[] tSendGain = new float[EfxEngine.MAX_BANDS];
		final float[] tSendCutoff = new float[EfxEngine.MAX_BANDS];
		long lastEvalTick = Long.MIN_VALUE / 2;
		long lastTick = Long.MIN_VALUE / 2;
		long lastLogTick = Long.MIN_VALUE / 2;
		/** 最近一次射线评估的耗时（纳秒），用于日志里的性能证据。 */
		long evalNanos;
		/** 沿连线累加出来的遮挡值（诊断用，也是日志里的关键数字）。 */
		float occlusionAcc;
		/** 0.12.6 诊断：主射线之外的 8 条偏移射线里，有几条"通透"（≤ {@link #OPEN_PATH_OCC}）。 */
		int lastOpenPaths;
		/** 0.12.7 诊断：本轮实际用掉的放宽幅度（0 = 完全没放宽；0.40 = 削掉四成遮挡）。 */
		float lastRelax;
		/** 0.12.6 诊断：本轮实际用的遮挡陡度 k（= 配置值 x 强度）。 */
		float lastK;
		/** 0.12.6 诊断：主射线（未放宽）的遮挡值，用来对比"放宽了多少"。 */
		float lastOccMain;
		/** 开阔度 0..1（M4 起由"共享空气空间"算出）。 */
		float openness = 1.0f;
		/** 想要写进 EAXReverb 的参数，以及"已经写进去的"（用来做变化阈值判定）。 */
		final EfxEngine.Reverb[] reverb = {new EfxEngine.Reverb(), new EfxEngine.Reverb(),
				new EfxEngine.Reverb(), new EfxEngine.Reverb()};
		final EfxEngine.Reverb[] appliedReverb = {new EfxEngine.Reverb(), new EfxEngine.Reverb(),
				new EfxEngine.Reverb(), new EfxEngine.Reverb()};
		boolean reverbDirty;
		/** 诊断用：最近一次评估测到的平均反射率 / 平均自由程 / 逐层反射率。 */
		float lastAvgReflectivity;
		float lastAvgFreePath;
		final float[] lastBandRefl = new float[REVERB_BOUNCES];
		/** M6：目标 / 平滑后的声源位置（未偏移时就是唱片机中心）。 */
		double tPosX;
		double tPosY;
		double tPosZ;
		double posX;
		double posY;
		double posZ;
		boolean posSeeded;
		/** 诊断：当前是否在水下。 */
		boolean underwater;
		boolean seeded = false;
		/** 0.12.7：这条声源上是否已经写过至少一次 EFX 参数（第一次先写"直通"，避免开局一步跳到满遮挡）。 */
		boolean wroteOnce = false;
		/** 0.12.7：上一次写 AL_POSITION 的刻（位置写入限频用）。 */
		long lastPosWriteTick = Long.MIN_VALUE / 2;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	/** DSP 回退路径当前跟随的状态（最后一个被评估的声源）。 */
	private static volatile State dspState = new State();
	/** 0.12.7：第一次给新声源接线时用的"直通"发送参数（见 {@link State#wroteOnce}）。 */
	private static final float[] NEUTRAL_SEND_GAIN = new float[EfxEngine.MAX_BANDS];
	private static final float[] NEUTRAL_SEND_CUTOFF = {1.0f, 1.0f, 1.0f, 1.0f};

	private Acoustics() {
	}

	/**
	 * DSP 兜底链路"当前是不是真的在压声音"（只给诊断/文档用）。
	 *
	 * <p><b>0.12.7</b>：<b>不再</b>用它决定"要不要接通 DSP 链路" —— 那样会在一开一关之间
	 * 把一阶低通的内部状态清掉，重新接通时从 0 开始 = 一个阶跃（就是那声 "bip"）。
	 * 链路现在是<b>全程接通</b>的，参数自己滑到"全通"（见 {@link #filterMono}）。
	 */
	public static boolean isActive() {
		if (!enabled()) {
			return false;
		}
		State s = dspState;
		return s != null && (s.directCutoff < 0.999f || s.sendGain[0] > 0.002f);
	}

	/**
	 * 总开关：<b>以配置文件为准</b>（{@code physicsSound}），这样"改了 JSON 直接生效"、
	 * 重启后也能记住界面上的勾选。配置还没加载好时才用 {@link #setEnabled} 设的静态值兜底。
	 */
	private static boolean enabled() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		return cfg != null ? cfg.physicsSound : enabled;
	}

	// ---- 物理声效 · 第 1 步：捕获我们自己声源的 OpenAL id ----

	/** 引擎每次 play 声音时告知"这是不是我们的实例"。 */
	public static void markNextOwnSource(boolean ours) {
		nextIsOurs = ours;
	}

	/** 把声音系统实例记下来，供"按声音实例精确反查 source id"用。 */
	public static void noteSoundSystem(SoundSystem ss) {
		soundSystem = ss;
	}

	/** 声源真正 play() 时来领：如果刚才标记的是我们的实例，就认领并捕获 id。 */
	public static boolean consumePendingOwnSource() {
		if (!nextIsOurs) {
			return false;
		}
		nextIsOurs = false;
		pendingOurs = true;
		return true;
	}

	/** 捕获到声源 id（兜底路径；首选路径是按声音实例反查，见 {@link #resolveSourceId}）。 */
	public static void attachSource(int sourceId) {
		lastSourceId = sourceId;
		lastSourceTick = currentTick();
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: 捕获到声源 id={}（EFX {}）", sourceId, EfxEngine.status());
	}

	public static int lastSourceId() {
		return lastSourceId;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
		CloudDiscConfig cfg = CloudDiscClient.config();
		if (cfg != null) {
			cfg.physicsSound = value; // 界面上的勾选直接落到配置对象（点"保存"就写盘）
		}
		if (!value) {
			wasEnabled = true; // 让下一次 tick 走"关掉 → 摘 EFX"这条路径
		}
	}

	public static void reset() {
		synchronized (STATES) {
			STATES.clear();
		}
		dspState = new State();
		smoothCutoffHz = -1.0f;
		lpState = 0.0f;
		probing = false;
		PROBE.clear();
	}

	/**
	 * 精确反查：我们的 {@link SoundInstance} → 引擎里那条声源的 OpenAL id。
	 *
	 * @return 0 表示暂时拿不到（声源还没建好 / 已经停掉），调用方应跳过本轮
	 */
	private static int resolveSourceId(SoundInstance instance) {
		SoundSystem ss = soundSystem;
		if (ss == null || instance == null) {
			return 0;
		}
		try {
			Map<SoundInstance, Channel.SourceManager> map = ((SoundSystemAccessor) ss).clouddisc$sources();
			if (map == null) {
				return 0;
			}
			Channel.SourceManager sm = map.get(instance);
			if (sm == null) {
				return 0;
			}
			Source src = ((SourceManagerAccessor) sm).clouddisc$source();
			if (src == null) {
				return 0;
			}
			return ((SourceAccessor) src).clouddisc$pointer();
		} catch (Throwable t) {
			return 0;
		}
	}

	private static long currentTick() {
		World w = MinecraftClient.getInstance().world;
		return w == null ? 0L : w.getTime();
	}

	// ---------------------------------------------------------------- 每 tick 主入口

	/**
	 * 每刻调用（每个正在播放的会话各一次）。
	 *
	 * @param jukebox  唱片机坐标
	 * @param instance 我们自己那条声音实例（用于精确反查 source id）
	 * @param nowTick  当前游戏刻
	 */
	public static void tick(BlockPos jukebox, SoundInstance instance, long nowTick) {
		if (jukebox == null) {
			return;
		}
		try {
			if (!enabled()) {
				// 播放途中被关掉：**不能一刀摘掉 EFX**（拓扑突变 = 一下可闻的爆音），
				// 0.12.7 改成"先把参数平滑到直通，推完了再摘"（见 DISENGAGE_TICKS）。
				if (wasEnabled) {
					wasEnabled = false;
					disengageSource = resolveSourceId(instance);
					disengageEndTick = nowTick + DISENGAGE_TICKS;
					CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: 已关闭 → 先把参数平滑到直通，约 {} 刻后摘掉 EFX",
							DISENGAGE_TICKS);
				}
				tickDisengage(instance, jukebox, nowTick);
				return;
			}
			wasEnabled = true;
			if (!EfxEngine.isAvailable()) {
				EfxEngine.ensureInit(nowTick);
			}
			MinecraftClient mc = MinecraftClient.getInstance();
			World world = mc.world;
			Entity self = mc.player;
			if (world == null || self == null) {
				return;
			}
			dumpMaterialTableOnce(world);

			int sourceId = resolveSourceId(instance);
			if (sourceId == 0) {
				// 兜底：声源刚 play 时捕获到的 id（只在捕获后 40 刻内有效，避免对已回收的 id 乱写）
				if (nowTick - lastSourceTick <= 40L) {
					sourceId = lastSourceId;
				}
			}
			if (sourceId == 0) {
				return; // 还没有真正的声源：什么都不做（绝不去猜一个 id）
			}

			State st = stateFor(sourceId);

			// ① 采集（限频）
			if (!st.seeded || nowTick - st.lastEvalTick >= INTERVAL_TICKS) {
				st.lastEvalTick = nowTick;
				long t0 = System.nanoTime();
				evaluate(world, self, jukebox, st, nowTick);
				st.evalNanos = System.nanoTime() - t0;
				st.seeded = true;
			}

			// ② 参数层：按时间平滑（这一步是"延迟只跟 tick 有关"的关键）
			smooth(st, nowTick);

			// ③ 应用层
			Vec3d center = centerOf(jukebox);
			if (directionEnabled()) {
				applyPosition(sourceId, st, center.x, center.y, center.z, nowTick);
			}
			if (EfxEngine.isAvailable()) {
				syncReverb(st);
				if (!st.wroteOnce) {
					// 0.12.7：第一次给这条声源接线时**先写成直通**（截止/增益 = 1、发送 = 0）。
					// 否则"引擎默认的全通 → 立刻套上当前遮挡值"就是一个阶跃，
					// 正好落在刚开始播放的前 0.3 秒里（用户最容易听出来的位置）。
					// 写成直通之后，smooth() 会按 0.15s 的时间常数把参数滑到目标值。
					st.wroteOnce = true;
					EfxEngine.applyToSource(sourceId, 1.0f, 1.0f, NEUTRAL_SEND_GAIN, NEUTRAL_SEND_CUTOFF, nowTick);
				} else {
					EfxEngine.applyToSource(sourceId, st.directCutoff, st.directGain, st.sendGain, st.sendCutoff, nowTick);
				}
				dspState = null; // EFX 生效时不碰 DSP
			} else {
				dspState = st;   // 回退：把参数交给 filterMono
			}

			// ④ 诊断日志
			logDiagnostics(st, sourceId, nowTick, world, self, jukebox);
		} catch (Throwable t) {
			// 声学出错绝不影响播放
			CloudDiscClient.LOGGER.warn("[CloudDisc] 物理声效: tick 异常（已忽略，播放不受影响）: {}", t.toString());
		}
	}

	private static State stateFor(int sourceId) {
		synchronized (STATES) {
			State s = STATES.get(sourceId);
			if (s == null) {
				s = new State();
				STATES.put(sourceId, s);
				// 简单清理：表太大时丢掉最老的（正常只会有 1~2 个）
				if (STATES.size() > 8) {
					STATES.clear();
					STATES.put(sourceId, s);
				}
			}
			return s;
		}
	}

	// ---------------------------------------------------------------- 采集层（射线）

	/**
	 * M3 采集：<b>沿"唱片机 → 听者耳朵"的连线逐格累加材质遮挡值</b>（不再数"挡没挡"）。
	 *
	 * <p>要点：
	 * <ul>
	 *   <li>遮挡值来自 {@link BlockAcoustics}（自己按方块声音组 + 完整方块 + 硬度 + 液体推导），
	 *       但<b>是否累加</b>由"射线有没有真的命中这一格的碰撞形状"决定
	 *       （{@link BlockAcoustics#blocksRay}）—— 关着的门算挡、开着的门算 0、楼梯的缝不算。</li>
	 *   <li>非严格模式：再把两个端点各偏移 ±1 格的 8 个对角点算一遍 —— <b>不取最小值</b>
	 *       （那样一条缝就把遮挡抹平成 0），而是数"<b>有几条通透通路</b>"。
	 *       <b>0.12.7</b>：{@code 放宽 = 上限 × 超出比例}，且<b>只有 ≥ {@link #DEFAULT_OPEN_PATHS}
	 *       条（默认 6/8）通透时才放宽，最多削掉 {@link #DEFAULT_RELAX_MAX}（40%）</b>；
	 *       达不到门槛一分不放宽（旧版 3/8 就砍 85%，门关着和开着听不出区别）。</li>
	 *   <li>{@code cutoff = exp(-遮挡累积 x k)}、{@code gain = cutoff^0.2}；k 来自配置（默认 4.5）。
	 *       开阔度修正 {@code max(sqrt(shared)*0.2, cutoff)} 加了门槛：遮挡 ≥ 0.6 时不允许抬截止。</li>
	 * </ul>
	 */
	private static void evaluate(World world, Entity self, BlockPos jukebox, State st, long nowTick) {
		Vec3d ear = earOf(self);
		Vec3d center = centerOf(jukebox);
		double dist = ear.distanceTo(center);

		// 材质探针：physicsSoundDebug=true 时，本轮把自己真正命中的方块收集起来（去重、最多 6 条）
		probing = debugEnabled();
		if (probing) {
			PROBE.clear();
		}

		float level = soundLevel();
		if (level <= 0.0f) {
			// 强度 = 0 → 等同关闭：不做射线、不挂任何效果，也不做位置偏移（干净的直通）
			st.occlusionAcc = 0.0f;
			st.lastOccMain = 0.0f;
			st.lastOpenPaths = 0;
			st.lastK = 0.0f;
			st.openness = 1.0f;
			st.tDirectCutoff = 1.0f;
			st.tDirectGain = 1.0f;
			for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
				st.tSendGain[i] = 0.0f;
				st.tSendCutoff[i] = 1.0f;
			}
			st.tPosX = center.x;
			st.tPosY = center.y;
			st.tPosZ = center.z;
			flushProbe(nowTick);
			return;
		}

		double occMain = occlusionAt(world, center, ear, jukebox);
		int openPaths = 0;
		double relax = 0.0;
		double occ = occMain;
		if (!strictOcclusion() && occMain > 0.0) {
			// 只要主射线被挡就试 8 个对角偏移；主射线本来就通透时遮挡必然是 0，不用白算。
			// 这里不取最小值，而是"数通路"——见下方门槛（openPathsRequired）与放宽上限（relaxCap）。
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sy = -1; sy <= 1; sy += 2) {
					for (int sz = -1; sz <= 1; sz += 2) {
						Vec3d off = new Vec3d(sx, sy, sz);
						double o = occlusionAt(world, center.add(off), ear.add(off), jukebox);
						if (o <= OPEN_PATH_OCC) {
							openPaths++;
						}
					}
				}
			}
			int need = openPathsRequired();
			// 0.12.7 门槛：**达不到"大多数通路都通透"就一分不放宽**（旧版 3/8 就砍掉 85%）。
			if (openPaths >= need) {
				// 刚过门槛给最小的一档，全部 8 条通透才给到配置的上限（默认 40%）。
				double excess = clampD((openPaths - need + 1) / (double) (OPEN_PATH_TOTAL - need + 1), 0.0, 1.0);
				relax = relaxCap() * excess;
			}
			occ = occMain * (1.0 - relax);
		}
		st.lastOccMain = (float) occMain;
		st.lastOpenPaths = openPaths;
		st.lastRelax = (float) relax;

		// 强度旋钮同时缩放"遮挡强度"：k_eff = k(配置) x 强度
		// 0 = 关闭（上面已提前返回）、1.0 = 默认、2.0 = 非常激进
		float k = absorption() * level;
		st.lastK = k;
		float cutoffNoAir = (float) Math.exp(-occ * k);
		st.occlusionAcc = (float) occ;

		// ---- 混响射线（M4/M5）：从唱片机按黄金角球面均匀发射，每条最多 4 次反弹 ----
		ReverbResult rr = traceReverb(world, center, ear, jukebox, occ, k);
		float avgShared = rr.sharedAirspaceWeight;
		st.openness = occ <= 0.0 ? 1.0f : avgShared;

		// 开阔度修正：同一片开阔空间里，声音能从别处绕过来 → 直通不该被压得太死
		// （对应 SPR 的 directCutoff = max(sqrt(averageSharedAirspace)*0.2, directCutoff)，
		//  但加了门槛：遮挡达到 OPENNESS_GATE_OCC 之后，开阔度一律不许抬截止 ——
		//  否则"隔一层石头墙"会被开阔度顶回 0.19 左右，听感只剩"稍微暗一点"。）
		float gate = (float) clampD(occ / OPENNESS_GATE_OCC, 0.0, 1.0);
		float opennessFloor = (float) (OPENNESS_FLOOR_COEF * Math.sqrt(Math.max(0.0f, avgShared)))
				* (1.0f - gate);
		float cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		float gain = (float) Math.pow(cutoffWithShared, DIRECT_GAIN_EXP);
		// 空气吸收：按距离衰减高频（"远处高频先没"）。只压高频，不压总增益
		// —— 总增益本来就有 OpenAL 的距离衰减在管。
		float air = (float) Math.pow(0.9, Math.max(0.0, (dist - AIR_START) / 3.0));
		float cutoff = Math.max(MIN_DIRECT_CUTOFF, cutoffWithShared * air);

		// ---- M6 水下：直通再乘 0.1，混响发送也一起变闷 ----
		boolean underwater = false;
		try {
			underwater = self.isSubmergedInWater();
		} catch (Throwable ignored) {
			// 取不到就当不在水下
		}
		st.underwater = underwater;
		if (underwater) {
			gain *= 0.1f;
			cutoff *= 0.3f;
		}

		st.tDirectCutoff = clamp01(cutoff);
		st.tDirectGain = clamp01(gain);

		// ---- M6 方向性：把声源位置沿"反射来向"偏移（到听者的距离不变，所以音量不变） ----
		st.tPosX = center.x;
		st.tPosY = center.y;
		st.tPosZ = center.z;
		if (directionEnabled() && occ > 0.0 && rr.hasDirection) {
			double len = Math.sqrt(rr.dirX * rr.dirX + rr.dirY * rr.dirY + rr.dirZ * rr.dirZ);
			if (len >= 0.5) {
				double ux = rr.dirX / len;
				double uy = rr.dirY / len;
				double uz = rr.dirZ / len;
				double d = center.distanceTo(ear);
				st.tPosX = ear.x + ux * d;
				st.tPosY = ear.y + uy * d;
				st.tPosZ = ear.z + uz * d;
			}
		}

		// ---- 混响发送：M5 起 4 个延迟带各走一个 aux slot ----
		fillSends(st, rr, soundLevel());
		if (underwater) {
			for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
				st.tSendCutoff[i] = clamp01(st.tSendCutoff[i] * 0.4f);
			}
		}
		for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
			copyReverb(rr.reverb[i], st.reverb[i]);
		}
		st.reverbDirty = true;
		st.lastAvgReflectivity = (float) rr.avgReflectivity;
		st.lastAvgFreePath = (float) rr.avgFreePath;
		System.arraycopy(rr.bandRefl, 0, st.lastBandRefl, 0, REVERB_BOUNCES);

		flushProbe(nowTick);
	}

	/**
	 * 把 4 个延迟带的能量变成 4 条"发送增益 + 发送截止"。
	 *
	 * <p>按计划做三件事：
	 * <ol>
	 *   <li><b>逐层反射率幂次修正</b>：第 2 层 × 反射率、第 3 层 × 反射率³、第 4 层 × 反射率⁴
	 *       —— 越晚的带经过的反射越多，所以对"墙面吸不吸声"越敏感。吸声的房间里尾巴就短。</li>
	 *   <li><b>距离衰减</b>：离得越远混响越少（见 {@link #REVERB_FADE_DISTANCE}）。</li>
	 *   <li><b>发送基准提升 ×{@value #SEND_BOOST}</b>（0.12.6：用户反馈"余响也偏含蓄"）。</li>
	 *   <li><b>强度系数</b>（{@code physicsSoundLevel}）+ 末端 {@code clamp(0,1)}。</li>
	 * </ol>
	 * <p>晚带再加一个很小的死区（低于 3% 直接归零），避免一直挂着一层听不清但费运算的嘶声。
	 */
	private static void fillSends(State st, ReverbResult rr, float level) {
		float[] g = new float[EfxEngine.MAX_BANDS];
		for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
			g[i] = rr.bandGain[i];
		}
		if (rr.bandRefl.length > 1) {
			g[1] *= rr.bandRefl[1];
		}
		if (rr.bandRefl.length > 2) {
			g[2] *= (float) Math.pow(rr.bandRefl[2], 3.0);
		}
		if (rr.bandRefl.length > 3) {
			g[3] *= (float) Math.pow(rr.bandRefl[3], 4.0);
		}
		for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
			float v = clamp01(g[i] * rr.distanceFactor * level * SEND_BOOST);
			if (i >= 2) {
				v = clamp01((v - 0.03f) / 0.97f);
			}
			st.tSendGain[i] = v;
			st.tSendCutoff[i] = clamp01(rr.sendCutoff[i]);
		}
		// 可用段数少于 4（ALC_MAX_AUXILIARY_SENDS=2，或根本没 EFX）时，
		// 把第 3/4 段折回前面的段，别把能量丢掉。DSP 兜底只有 1 条混响链路，所以 b=1。
		int b = EfxEngine.isAvailable() ? Math.max(1, Math.min(EfxEngine.bands(), EfxEngine.MAX_BANDS)) : 1;
		if (b < EfxEngine.MAX_BANDS) {
			for (int i = b; i < EfxEngine.MAX_BANDS; i++) {
				st.tSendGain[i % b] += st.tSendGain[i];
				st.tSendGain[i] = 0.0f;
			}
			for (int i = 0; i < b; i++) {
				st.tSendGain[i] = clamp01(st.tSendGain[i]);
			}
		}
	}

	private static void copyReverb(EfxEngine.Reverb from, EfxEngine.Reverb to) {
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
	 * 混响参数是否"变到值得重写"。
	 * <p>0.12.7：阈值整体加大（增益 0.02 → 0.04、衰减时间 0.05 → 0.15、
	 * 晚混响延迟 0.005 → 0.01）—— 0.12.6 的阈值下基本上每轮评估都在重写 EAXReverb 的十个参数，
	 * 而每次写入都可能带出一点小爆音。加大后只在"真的换了个环境"时才写。
	 */
	private static boolean reverbDiffers(EfxEngine.Reverb a, EfxEngine.Reverb b) {
		final float e = 0.04f;
		return Math.abs(a.gain - b.gain) > e
				|| Math.abs(a.gainHF - b.gainHF) > e
				|| Math.abs(a.decayTime - b.decayTime) > 0.15f
				|| Math.abs(a.decayHFRatio - b.decayHFRatio) > e
				|| Math.abs(a.reflectionsGain - b.reflectionsGain) > e
				|| Math.abs(a.lateReverbGain - b.lateReverbGain) > e
				|| Math.abs(a.lateReverbDelay - b.lateReverbDelay) > 0.01f
				|| Math.abs(a.density - b.density) > e
				|| Math.abs(a.diffusion - b.diffusion) > e
				|| Math.abs(a.airAbsorptionGainHF - b.airAbsorptionGainHF) > 0.01f;
	}

	/** 把 EAXReverb 参数按需灌进效果器（只有变化超过阈值才写，避免每 tick 重设造成杂音/开销）。 */
	private static void syncReverb(State st) {
		if (!EfxEngine.isAvailable() || !st.reverbDirty) {
			return;
		}
		st.reverbDirty = false;
		int n = Math.min(EfxEngine.bands(), EfxEngine.MAX_BANDS);
		for (int i = 0; i < n; i++) {
			if (reverbDiffers(st.reverb[i], st.appliedReverb[i])) {
				EfxEngine.setReverb(i, st.reverb[i]);
				copyReverb(st.reverb[i], st.appliedReverb[i]);
			}
		}
	}
	// ------------------------------------------------------------ 混响射线（M4/M5）

	private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));
	private static final int REVERB_BOUNCES = 4;
	private static final double REVERB_MAX_DISTANCE = 48.0;
	/** 每条反射射线最多穿过多少格（斜射要按 √3 倍放宽：48 格的斜线约 83 格）。 */
	private static final int MAX_REVERB_STEPS = 96;
	private static final int MAX_CLEAR_LINE_STEPS = 64;
	/** 计划里的 0.12：反射路径长度 → 感知延迟的换算系数。 */
	private static final float REVERB_DELAY_FACTOR = 0.12f;
	/** 4 个延迟带的能量权重。 */
	private static final float[] BAND_WEIGHT = {6.4f, 12.8f, 12.8f, 12.8f};
	/** 距离衰减：混响发送在这么远之外完全消失。 */
	private static final float REVERB_FADE_DISTANCE = 48.0f;
	/** 0.12.6：发送基准整体提升（用户反馈"余响偏含蓄"，但优先级低于"变闷"）。 */
	private static final float SEND_BOOST = 1.6f;
	/** 每次评估最多做多少次"命中点 → 耳朵通不通"的测试（只在被挡时做）。 */
	private static final int MAX_CLEAR_LINE_TESTS = 48;

	private static final class ReverbResult {
		final float[] bandGain = new float[4];
		final float[] sendCutoff = new float[4];
		/** 逐层平均反射率（第 i 次反弹的平均反射率）。 */
		final float[] bandRefl = new float[REVERB_BOUNCES];
		float sharedAirspaceWeight;
		float distanceFactor = 1.0f;
		/** 0.12.6：兜底反射率 0.40 → 0.60（反射率越高，混响越亮/越明显）。 */
		double avgReflectivity = 0.6;
		double avgFreePath = 4.0;
		/** M6 方向性：反射来向的加权和（未归一化）。 */
		double dirX;
		double dirY;
		double dirZ;
		boolean hasDirection;
		final EfxEngine.Reverb[] reverb = {new EfxEngine.Reverb(), new EfxEngine.Reverb(),
				new EfxEngine.Reverb(), new EfxEngine.Reverb()};
	}

	private static ReverbResult traceReverb(World world, Vec3d center, Vec3d ear, BlockPos jukebox, double occ,
			float k) {
		ReverbResult out = new ReverbResult();
		int numRays = rays();
		float rcpTotalRays = 1.0f / (numRays * (float) REVERB_BOUNCES);
		float[] bandRefl = new float[REVERB_BOUNCES];
		double reflSum = 0.0;
		int reflCount = 0;
		double freePathSum = 0.0;
		int freePathCount = 0;
		int sharedAirspaces = 0;
		int clearLineTests = 0;

		// 同一坐标每轮都用同一组方向（确定性），但按坐标旋转一下，避免所有唱片机方向图案一模一样
		double theta0 = ((jukebox.getX() * 31L + jukebox.getY() * 17L + jukebox.getZ() * 13L) & 0xFFFF) / 65535.0 * (Math.PI * 2.0);

		for (int i = 0; i < numRays; i++) {
			// 黄金角球面均匀分布（Fibonacci sphere）：不需要随机数，覆盖面均匀
			double yy = 1.0 - 2.0 * (i + 0.5) / numRays;
			double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
			double th = theta0 + GOLDEN_ANGLE * i;
			Vec3d dir = new Vec3d(rr * Math.cos(th), yy, rr * Math.sin(th)).normalize();
			Vec3d origin = center;
			double totalDist = 0.0;

			for (int b = 0; b < REVERB_BOUNCES; b++) {
				Vec3d end = origin.add(dir.multiply(REVERB_MAX_DISTANCE));
				RayWalk.Hit hit = RayWalk.cast(world, origin, end, MAX_REVERB_STEPS);
				if (hit == null) {
					// 这条射线跑到头了（等于跑进了开阔空间）：把"到听者的剩余距离"也算进路程
					totalDist += origin.distanceTo(ear);
					break;
				}
				double seg = hit.t * REVERB_MAX_DISTANCE;
				totalDist += seg;
				BlockState bs = safeState(world, hit.pos);
				float refl = bs == null ? 0.6f : BlockAcoustics.reflectivityOf(bs);
				bandRefl[b] += refl;
				reflSum += refl;
				reflCount++;
				freePathSum += seg;
				freePathCount++;

				// 反射延迟 → 三角权重落进 4 个延迟带
				float reflectionDelay = (float) (totalDist * REVERB_DELAY_FACTOR * refl);
				float energy = 0.25f * (refl * 0.75f + 0.25f);
				float c0 = 1.0f - clamp(Math.abs(reflectionDelay), 0.0f, 1.0f);
				float c1 = 1.0f - clamp(Math.abs(reflectionDelay - 1.0f), 0.0f, 1.0f);
				float c2 = 1.0f - clamp(Math.abs(reflectionDelay - 2.0f), 0.0f, 1.0f);
				float c3 = clamp(reflectionDelay - 2.0f, 0.0f, 1.0f);
				out.bandGain[0] += c0 * energy * BAND_WEIGHT[0] * rcpTotalRays;
				out.bandGain[1] += c1 * energy * BAND_WEIGHT[1] * rcpTotalRays;
				out.bandGain[2] += c2 * energy * BAND_WEIGHT[2] * rcpTotalRays;
				out.bandGain[3] += c3 * energy * BAND_WEIGHT[3] * rcpTotalRays;

				// 共享空气空间：从这个命中点能不能"直线看到"听者。
				// 只在直通被挡时才算 —— 直通通透时 openness 本来就是 1，没必要花这个钱。
				if (occ > 0.0 && clearLineTests < MAX_CLEAR_LINE_TESTS) {
					clearLineTests++;
					Vec3d hp = hit.point().add(hit.normal().multiply(0.002));
					if (RayWalk.cast(world, hp, ear, MAX_CLEAR_LINE_STEPS) == null) {
						sharedAirspaces++;
						Vec3d d = ear.subtract(hp);
						double len = d.length();
						if (len > 0.5) {
							double w = 1.0 / (len * len);
							out.dirX += d.x / len * w;
							out.dirY += d.y / len * w;
							out.dirZ += d.z / len * w;
							out.hasDirection = true;
						}
					}
				}

				dir = reflect(dir, hit.normal());
				origin = hit.point().add(hit.normal().multiply(0.002));
			}
		}

		// 逐层反射率（M5 用它做幂次修正：越晚的反弹对反射率越敏感）
		for (int i = 0; i < REVERB_BOUNCES; i++) {
			out.bandRefl[i] = bandRefl[i] / (float) numRays;
		}
		out.avgReflectivity = reflCount == 0 ? 0.6 : reflSum / reflCount;
		out.avgFreePath = freePathCount == 0 ? 4.0 : freePathSum / freePathCount;

		// 共享空气空间 → 4 个延迟带各自的权重（越晚的带越容易被"绕过来的声音"填满）
		float sharedAirspace = sharedAirspaces * 64.0f * rcpTotalRays;
		float w0 = clamp(sharedAirspace / 20.0f, 0.0f, 1.0f);
		float w1 = clamp(sharedAirspace / 15.0f, 0.0f, 1.0f);
		float w2 = clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);
		float w3 = clamp(sharedAirspace / 10.0f, 0.0f, 1.0f);
		out.sharedAirspaceWeight = (w0 + w1 + w2 + w3) * 0.25f;

		float occCut = (float) Math.exp(-occ * k);
		out.sendCutoff[0] = occCut * (1.0f - w0) + w0;
		out.sendCutoff[1] = occCut * (1.0f - w1) + w1;
		out.sendCutoff[2] = occCut * (1.0f - w2) + w2;
		out.sendCutoff[3] = occCut * (1.0f - w3) + w3;

		// 距离衰减：离得越远，混响越少（否则整个地图都在响同一份余响）
		double dist = ear.distanceTo(center);
		out.distanceFactor = (float) Math.max(0.0, 1.0 - Math.min(dist / REVERB_FADE_DISTANCE, 1.0));

		// 由"平均自由程 / 反射率"推每个延迟带的 EAXReverb 参数（数值自己定，不抄 SPR 的预设表）
		// 0.12.6：衰减时间系数 0.25→0.40、下限 0.25→0.45、上限 4.0→6.0；
		//         reflectionsGain/lateReverbGain 的基数与斜率都上调；整体 gain 0.24→0.32。
		double baseDecay = clampD(0.40 * out.avgFreePath, 0.45, 6.0);
		for (int i = 0; i < 4; i++) {
			EfxEngine.Reverb p = out.reverb[i];
			p.decayTime = (float) clampD(baseDecay * (0.7 + 0.5 * i), 0.30, 12.0);
			p.gainHF = (float) clampD(0.35 + 0.65 * out.avgReflectivity, 0.15, 1.0);
			p.reflectionsGain = (float) clampD(0.25 + 0.55 * out.avgReflectivity, 0.05, 0.95);
			p.lateReverbGain = (float) clampD(0.40 + 0.55 * out.avgReflectivity, 0.10, 1.0);
			p.lateReverbDelay = 0.012f + 0.012f * i;
			p.decayHFRatio = (float) clampD(0.45 + 0.35 * out.avgReflectivity, 0.2, 0.95);
			p.gain = 0.32f;
		}
		return out;
	}

	private static BlockState safeState(World world, BlockPos p) {
		try {
			return world.getBlockState(p);
		} catch (Throwable t) {
			return null;
		}
	}

	/** 镜面反射：{@code dir - 2 (dir·n) n}。 */
	private static Vec3d reflect(Vec3d dir, Vec3d normal) {
		double dot = dir.dotProduct(normal) * 2.0;
		return new Vec3d(dir.x - dot * normal.x, dir.y - dot * normal.y, dir.z - dot * normal.z);
	}

	private static int rays() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		int n = cfg == null ? 32 : cfg.physicsRays;
		return Math.max(8, Math.min(128, n));
	}

	private static float soundLevel() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		float v = cfg == null ? 1.0f : cfg.physicsSoundLevel;
		return Math.max(0.0f, Math.min(2.0f, v));
	}

	/** 遮挡陡度 k（配置 {@code physicsAbsorption}，默认 4.5）。 */
	private static float absorption() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		float v = cfg == null ? ABSORPTION_DEFAULT : cfg.physicsAbsorption;
		if (!(v > 0.0f) || Float.isNaN(v)) {
			return ABSORPTION_DEFAULT; // 配置被手改成 0/负数/NaN 时按默认，不把效果变成"无遮挡"
		}
		return Math.max(ABSORPTION_MIN, Math.min(ABSORPTION_MAX, v));
	}

	/**
	 * 需要几条通透通路才算"真的漏音"（配置 {@code physicsOcclusionPaths}）。
	 * <p>0.12.7：默认从 3 提到 <b>6</b>（共 8 条偏移射线）—— 旧值 3/8 会在一格厚的墙、
	 * 一扇门的旁边就触发"砍掉 85% 遮挡"，正是"门关着和开着一样"的根因之一。
	 */
	private static int openPathsRequired() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		int v = cfg == null ? DEFAULT_OPEN_PATHS : cfg.physicsOcclusionPaths;
		return Math.max(OPEN_PATHS_MIN, Math.min(OPEN_PATHS_MAX, v));
	}

	/** 放宽幅度上限（配置 {@code physicsOcclusionRelax}，默认 0.40 = 最多削掉 40% 的遮挡）。 */
	private static double relaxCap() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		double v = cfg == null ? DEFAULT_RELAX_MAX : cfg.physicsOcclusionRelax;
		if (Double.isNaN(v)) {
			return DEFAULT_RELAX_MAX;
		}
		return Math.max(RELAX_MAX_MIN, Math.min(RELAX_MAX_MAX, v));
	}

	/** 调试日志总开关（{@code physicsSoundDebug}）。 */
	private static boolean debugEnabled() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		return cfg != null && cfg.physicsSoundDebug;
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static double clampD(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// ---------------------------------------------------------------- 材质探针（诊断，①）

	/**
	 * 本轮命中的方块 → 一行"材质探针"文本（按方块注册名去重，保留第一条）。
	 * <p>只在主线程用（{@link #tick} 是客户端 tick 调用的），所以这里不做同步。
	 */
	private static final java.util.LinkedHashMap<String, String> PROBE = new java.util.LinkedHashMap<>();
	private static volatile boolean probing = false;
	private static volatile long lastProbeTick = Long.MIN_VALUE / 2;
	private static volatile boolean tableDumped = false;
	/** 收集上限：一次评估最多记这么多条（打出去的另有 {@link #PROBE_MAX} 上限）。 */
	private static final int PROBE_COLLECT_MAX = 24;

	/**
	 * 采集一个"真正参与了遮挡累加"的方块（空气/无碰撞体积的东西不会走到这里）。
	 * <p>{@code physicsSoundDebug=false} 时这是个空操作（一个分支判断，开销可忽略）。
	 */
	private static void noteProbe(BlockState bs, World world, BlockPos p) {
		if (!probing || PROBE.size() >= PROBE_COLLECT_MAX) {
			return;
		}
		try {
			String id = BlockAcoustics.blockId(bs);
			if (!PROBE.containsKey(id)) {
				PROBE.put(id, BlockAcoustics.probeLine(bs, world, p));
			}
		} catch (Throwable ignored) {
			// 探针出错绝不影响声学
		}
	}

	/**
	 * 打完本轮收集到的材质探针。
	 * <p>去重 + 每轮最多 {@link #PROBE_MAX} 条 + 最多 {@link #PROBE_INTERVAL_TICKS} 刻一次
	 * （评估本身是 5Hz，不限频会一秒刷 30 行，反而看不清）。
	 */
	private static void flushProbe(long nowTick) {
		if (!probing) {
			return;
		}
		probing = false;
		if (PROBE.isEmpty()) {
			return;
		}
		boolean emit = nowTick - lastProbeTick >= PROBE_INTERVAL_TICKS;
		if (emit) {
			lastProbeTick = nowTick;
			int n = 0;
			for (String line : PROBE.values()) {
				CloudDiscClient.LOGGER.info("[CloudDisc] 材质探针: {}", line);
				if (++n >= PROBE_MAX) {
					break;
				}
			}
			if (PROBE.size() > PROBE_MAX) {
				CloudDiscClient.LOGGER.info("[CloudDisc] 材质探针: （本轮还命中 {} 个方块，已省略）", PROBE.size() - PROBE_MAX);
			}
		}
		PROBE.clear();
	}

	/**
	 * 开一次调试就整表打一遍（<b>这是"材质表到底生效没有"最快的一条证据</b>）：
	 * 不依赖任何射线命中，开局就在日志里看到每个样本方块命中了哪一组。
	 */
	private static void dumpMaterialTableOnce(World world) {
		if (tableDumped || !debugEnabled()) {
			return;
		}
		tableDumped = true;
		CloudDiscClient.LOGGER.info("[CloudDisc] 材质表自检（physicsSoundDebug=true 时开局打一次，与射线无关）: {}",
				String.join(" ", BlockAcoustics.sampleTable(world)));
	}

	/** 沿 from → to 逐格累加材质遮挡值。 */
	private static double occlusionAt(World world, Vec3d from, Vec3d to, BlockPos skip) {
		final double[] acc = {0.0};
		RayWalk.walk(from.x, from.y, from.z, to.x, to.y, to.z, MAX_OCC_STEPS, (x, y, z, t, nx, ny, nz) -> {
			BlockPos p = new BlockPos(x, y, z);
			if (skip != null && p.equals(skip)) {
				return true;
			}
			BlockState bs;
			try {
				bs = world.getBlockState(p);
			} catch (Throwable e) {
				return false;
			}
			if (bs.isAir()) {
				return true;
			}
			boolean fluid = false;
			try {
				fluid = !bs.getFluidState().isEmpty();
			} catch (Throwable ignored) {
				// 取不到就当不是流体
			}
			if (!fluid) {
				// 0.12.7：判据从"这一格有没有碰撞体积"改成"**这条射线有没有真的命中它的碰撞形状**"。
				// 理由（Bug 1 的第二半根因）：关着的门有碰撞体积，但老实现只把它当成
				// "非完整方块"再打对折 → 0.55×0.8×0.5 = 0.22，截止 0.37，"几乎不闷"。
				// 现在：命中门板 → 按材质值 0.44 累加；门开着（形状为空）→ 0；
				//       楼梯/栅栏的缝（射线从空隙过）→ 0，不再整格当成实心白算一笔。
				try {
					if (!BlockAcoustics.blocksRay(bs, world, p, from, to)) {
						return true;
					}
				} catch (Throwable e) {
					return true; // 判定失败：保守地不累加（宁可轻一点，也不要凭形状猜）
				}
			}
			noteProbe(bs, world, p); // 诊断：这次真的算它了（① 材质探针）
			acc[0] += BlockAcoustics.occlusionOf(bs);
			return acc[0] < MAX_OCC;
		});
		return Math.min(acc[0], MAX_OCC);
	}

	private static boolean strictOcclusion() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		return cfg != null && cfg.physicsStrictOcclusion;
	}

	private static boolean directionEnabled() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		return cfg != null && cfg.physicsSoundDirection;
	}

	private static Vec3d earOf(Entity self) {
		return new Vec3d(self.getX(), self.getEyeY(), self.getZ());
	}

	private static Vec3d centerOf(BlockPos jukebox) {
		return new Vec3d(jukebox.getX() + 0.5, jukebox.getY() + 0.6, jukebox.getZ() + 0.5);
	}

	// ---------------------------------------------------------------- 参数层（按时间平滑）

	private static void smooth(State st, long nowTick) {
		long dt = nowTick - st.lastTick;
		// 【教训 1】带时间戳的限频代码，初值必须是 Long.MIN_VALUE/2，否则减法溢出 → 永远 return。
		// 这里再加一道保险：dt 不合理（<=0 或过大）时按 1 刻处理。
		if (dt <= 0L || dt > 100L) {
			dt = 1L;
		}
		st.lastTick = nowTick;
		// 【教训 2】平滑系数必须按时间算（1 - exp(-dt/tau)），不能按调用次数。
		float k = (float) (1.0 - Math.exp(-(dt / 20.0) / SMOOTH_SECONDS));
		st.directCutoff += (st.tDirectCutoff - st.directCutoff) * k;
		st.directGain += (st.tDirectGain - st.directGain) * k;
		for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
			st.sendGain[i] += (st.tSendGain[i] - st.sendGain[i]) * k;
			st.sendCutoff[i] += (st.tSendCutoff[i] - st.sendCutoff[i]) * k;
		}
		if (!st.posSeeded) {
			st.posSeeded = true;
			st.posX = st.tPosX;
			st.posY = st.tPosY;
			st.posZ = st.tPosZ;
		} else {
			st.posX += (st.tPosX - st.posX) * k;
			st.posY += (st.tPosY - st.posY) * k;
			st.posZ += (st.tPosZ - st.posZ) * k;
		}
	}

	/**
	 * M6 方向性：把平滑后的声源位置写回 OpenAL（纯 AL 调用，与 EFX 是否可用无关）。
	 * <p>只在"真的偏移了"的时候写，避免每 tick 覆盖原版设置。
	 * <p><b>0.12.7</b>：位置同样<b>限频</b>（{@link #WRITE_INTERVAL_TICKS} 刻一次）——
	 * 位置本来就已经按时间平滑（时间常数 {@link #SMOOTH_SECONDS}），再降一点写入频率
	 * 只减少"每 tick 一次 AL 调用"带来的抖动，听感不变。
	 */
	private static void applyPosition(int sourceId, State st, double centerX, double centerY, double centerZ, long nowTick) {
		if (!st.posSeeded) {
			return;
		}
		double dx = st.posX - centerX;
		double dy = st.posY - centerY;
		double dz = st.posZ - centerZ;
		if (dx * dx + dy * dy + dz * dz < 0.01) {
			return; // 没偏移：保持原版给的位置
		}
		if (nowTick - st.lastPosWriteTick < WRITE_INTERVAL_TICKS) {
			return; // 限频（0.12.7）
		}
		st.lastPosWriteTick = nowTick;
		try {
			AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) st.posX, (float) st.posY, (float) st.posZ);
		} catch (Throwable t) {
			// 位置偏移失败不影响播放
		}
	}

	/**
	 * 关闭总开关后的"退场"：把参数按时间平滑到直通（截止/增益 → 1，发送 → 0），
	 * 推完（{@link #DISENGAGE_TICKS} 刻）才真正摘掉 EFX。
	 *
	 * <p>只有"播放途中被关掉"才会走到这里；冷启动时开关就是关的 → {@link #disengageSource} 为 0，直接返回。
	 * <p>声源已经回收（反查返回 0）时<b>绝不</b>往旧 id 上写 —— 那可能写到别人的声源上。
	 */
	private static void tickDisengage(SoundInstance instance, BlockPos jukebox, long nowTick) {
		if (disengageSource == 0) {
			return;
		}
		int cur = resolveSourceId(instance);
		if (cur != 0) {
			disengageSource = cur;
		}
		try {
			if (cur != 0) {
				State st = stateFor(cur);
				st.tDirectCutoff = 1.0f;
				st.tDirectGain = 1.0f;
				for (int i = 0; i < EfxEngine.MAX_BANDS; i++) {
					st.tSendGain[i] = 0.0f;
					st.tSendCutoff[i] = 1.0f;
				}
				smooth(st, nowTick);
				if (directionEnabled() && jukebox != null) {
					Vec3d c = centerOf(jukebox);
					st.tPosX = c.x;
					st.tPosY = c.y;
					st.tPosZ = c.z;
					applyPosition(cur, st, c.x, c.y, c.z, nowTick);
				}
				if (EfxEngine.isAvailable()) {
					EfxEngine.applyToSource(cur, st.directCutoff, st.directGain, st.sendGain, st.sendCutoff, nowTick);
				}
			}
		} catch (Throwable ignored) {
			// 退场平滑出错不影响播放；下面照样按时间摘掉
		}
		if (nowTick >= disengageEndTick) {
			if (cur != 0) {
				EfxEngine.bypassSource(cur);
				synchronized (STATES) {
					STATES.remove(cur);
				}
				CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: 参数已平滑到直通 → 摘掉 EFX / 恢复干净直通");
			}
			disengageSource = 0;
		}
	}

	// ---------------------------------------------------------------- 诊断

	private static void logDiagnostics(State st, int sourceId, long nowTick, World world, Entity self, BlockPos jukebox) {
		CloudDiscConfig cfg = CloudDiscClient.config();
		boolean debug = cfg != null && cfg.physicsSoundDebug;
		// 平时每 10 秒一行；调试模式下每 1 秒一行（方便边拆墙边看数值变化）
		long interval = debug ? 20L : LOG_INTERVAL_TICKS;
		if (nowTick - st.lastLogTick < interval) {
			return;
		}
		st.lastLogTick = nowTick;
		Vec3d ear = earOf(self);
		double dist = ear.distanceTo(centerOf(jukebox));
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效[M7]: source={} EFX={} 遮挡累积={} 主射线遮挡={} 通透通路={}/{} 放宽={} 吸收k={} 直通截止(GAINHF)={} 直通增益={} 开阔度={} 距离={}格 水下={} 位置偏移={}格 评估耗时={}ms"
						+ "｜sendGain={} sendCutoff={} 逐层反射率={} 平均反射率={} 自由程={}格",
				sourceId,
				EfxEngine.isAvailable() ? "可用(" + EfxEngine.bands() + "段)" : "不可用→DSP",
				fmt(st.occlusionAcc), fmt(st.lastOccMain), st.lastOpenPaths, OPEN_PATH_TOTAL, fmt(st.lastRelax),
				fmt(st.lastK),
				fmt(st.directCutoff), fmt(st.directGain), fmt(st.openness),
				fmt1(dist), st.underwater, fmt1(offsetOf(st, jukebox)), fmt3(st.evalNanos / 1.0e6),
				arr(st.sendGain), arr(st.sendCutoff), arr(st.lastBandRefl), fmt(st.lastAvgReflectivity), fmt1(st.lastAvgFreePath));
	}

	private static double offsetOf(State st, BlockPos jukebox) {
		if (!st.posSeeded) {
			return 0.0;
		}
		Vec3d c = centerOf(jukebox);
		return Math.sqrt((st.posX - c.x) * (st.posX - c.x) + (st.posY - c.y) * (st.posY - c.y) + (st.posZ - c.z) * (st.posZ - c.z));
	}

	private static String fmt(float v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static String fmt1(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static String fmt3(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static String arr(float[] a) {
		StringBuilder sb = new StringBuilder("[");
		int n = Math.min(a.length, Math.max(1, EfxEngine.bands() == 0 ? a.length : EfxEngine.bands()));
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(fmt(a[i]));
		}
		return sb.append(']').toString();
	}

	private static float clamp01(float v) {
		return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
	}

	// ================================================================ DSP 回退路径
	// 下面是 0.10~0.12 那套自研 PCM DSP（一阶低通 + 玩具 Schroeder 混响）。
	// 它<b>只在 EFX 不可用时</b>才工作；不删，保证"任何设备上都有声效可用"。

	private static float smoothCutoffHz = -1.0f;
	private static float lpState = 0.0f;

	private static final float[] COMB_SECONDS = {0.0297f, 0.0371f, 0.0411f, 0.0437f};
	private static final float[] ALLPASS_SECONDS = {0.0050f, 0.0017f};
	private static float[][] combBuf;
	private static int[] combIdx;
	private static float[] combOut;
	private static float[][] apBuf;
	private static int[] apIdx;
	private static float preparedRate = 0.0f;

	/**
	 * 对一段单声道、归一化样本做处理：直通低通 + 增益 + 混响。
	 *
	 * <p><b>EFX 可用时一个样本都不碰</b>（第一行就返回；EFX 负责效果，这里再滤一次会双重滤波）。
	 *
	 * <p><b>0.12.7 修的一处爆音来源</b>：以前入口是
	 * {@code if (EfxEngine.isAvailable() || !isActive() || ...)}，并且一旦"不活跃"就把
	 * {@code lpState}（一阶低通的状态）和 {@code smoothCutoffHz} 清掉。问题是
	 * {@code isActive()} 在"参数回到全通"（例如走进开阔地）时会变假、之后又变真 ——
	 * 每次重新接通，滤波器状态都从 0 开始，而输入信号不是 0 → <b>一个阶跃</b>，听起来就是"bip"。
	 * 现在改成：<b>只要在走 DSP 兜底，链路就一直接通</b>（参数自己会平滑到"全通"，
	 * 全通时对样本几乎无影响），再也不清状态。
	 */
	public static void filterMono(float[] buf, int offset, int frames, float sampleRate) {
		if (EfxEngine.isAvailable() || sampleRate <= 0.0f) {
			return; // EFX 路径：完全不碰样本
		}
		State st = dspState;
		if (st == null) {
			return; // 没有正在评估的声源（没有我们自己的音频在放）：同样不碰
		}
		prepareReverb(sampleRate);

		float directCutoff = st.directCutoff;
		float targetHz = (float) (MAX_CUTOFF_HZ * Math.pow(MIN_CUTOFF_HZ / MAX_CUTOFF_HZ, 1.0f - directCutoff));
		// 平滑按时间收敛（1 - exp(-块时长/tau)），不按块数。
		float k = (float) (1.0 - Math.exp(-(double) frames / (SMOOTH_SECONDS * sampleRate)));
		if (smoothCutoffHz < 0.0f) {
			// 第一次：直接对齐目标（冷启动，"全通"起步不存在记忆问题）
			smoothCutoffHz = targetHz;
		} else {
			smoothCutoffHz += (targetHz - smoothCutoffHz) * k;
		}
		float a = (float) (1.0 - Math.exp(-2.0 * Math.PI * smoothCutoffHz / sampleRate));
		float gain = st.directGain;
		float wet = st.sendGain[0];
		float room = 0.6f + 0.9f * st.openness;
		float decay = 0.35f + 0.55f * room;
		float feedback = (float) Math.pow(0.001, 1.0 / Math.max(1.0, decay * sampleRate / 1000.0 * 1.5));

		float y = lpState;
		for (int i = 0; i < frames; i++) {
			float dry = buf[offset + i] * gain;
			y += a * (dry - y);
			float out = y;
			if (wet > 0.001f) {
				out += reverbSample(y, feedback) * wet;
			}
			buf[offset + i] = out;
		}
		lpState = y;
	}

	/** Schroeder：4 个并联梳状（带反馈）+ 2 个串联全通。 */
	private static float reverbSample(float x, float feedback) {
		float sum = 0.0f;
		for (int c = 0; c < COMB_SECONDS.length; c++) {
			float[] b = combBuf[c];
			int idx = combIdx[c];
			float delayed = b[idx];
			combOut[c] = delayed;
			b[idx] = x + delayed * feedback;
			combIdx[c] = (idx + 1) % b.length;
			sum += delayed;
		}
		float v = sum * 0.25f;
		for (int p = 0; p < ALLPASS_SECONDS.length; p++) {
			float[] b = apBuf[p];
			int idx = apIdx[p];
			float delayed = b[idx];
			float out = -v + delayed;
			b[idx] = v + delayed * 0.5f;
			apIdx[p] = (idx + 1) % b.length;
			v = out;
		}
		return v;
	}

	private static void prepareReverb(float sampleRate) {
		if (preparedRate == sampleRate && combBuf != null) {
			return;
		}
		preparedRate = sampleRate;
		combBuf = new float[COMB_SECONDS.length][];
		combIdx = new int[COMB_SECONDS.length];
		combOut = new float[COMB_SECONDS.length];
		for (int i = 0; i < COMB_SECONDS.length; i++) {
			combBuf[i] = new float[Math.max(16, (int) (COMB_SECONDS[i] * sampleRate))];
		}
		apBuf = new float[ALLPASS_SECONDS.length][];
		apIdx = new int[ALLPASS_SECONDS.length];
		for (int i = 0; i < ALLPASS_SECONDS.length; i++) {
			apBuf[i] = new float[Math.max(8, (int) (ALLPASS_SECONDS[i] * sampleRate))];
		}
	}
}
