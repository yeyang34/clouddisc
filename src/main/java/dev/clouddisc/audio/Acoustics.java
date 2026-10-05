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
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
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
	private static final double OPEN_PATH_OCC = 0.80;
	/**
	 * 放宽幅度上限：最多把遮挡降到 {@code 1 - MAX_RELAX} = 15%。
	 * <p>0.12.5 是"8 个偏移点取最小值"—— 只要有一条缝，遮挡直接归零、效果全没了。
	 */
	private static final double MAX_RELAX = 0.40;
	/** 默认需要多少条通透通路才算"真的漏音"（可配 {@code physicsOcclusionPaths}）。 */
	private static final int DEFAULT_OPEN_PATHS = 6;
	private static final int OPEN_PATHS_MIN = 1;
	private static final int OPEN_PATHS_MAX = 9;

	private static final double AIR_START = 12.0;
	/** 沿连线最多穿过多少格（性能上限）。
	 * <p>注意这是"<b>格子数</b>"不是"格数"：斜射一条 20 格的线最多会穿过 3x20 个格子，
	 * 所以这里给得比最大听距更宽松，避免"远处的墙没算进来"这种静默错误。 */
	private static final int MAX_OCC_STEPS = 96;
	/** 遮挡累积值上限：再厚的墙也不会更闷（否则一个 10 格厚的地基会把增益压到听不见）。 */
	private static final double MAX_OCC = 3.0;
	/** 【0.12.56】接收球半径（格）：射线从听者这个距离内掠过，就算"能听到"。 */
	private static final double EAR_REACH = 2.0;
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
	// 【0.12.46】最终遮挡的时间平滑（8 条偏移射线是整数台阶，跨墙角会 0→1 阶跃）
	float smoothOcc = -1.0f;
	long smoothOccTick = Long.MIN_VALUE / 2;
		/** 最近一次射线评估的耗时（纳秒），用于日志里的性能证据。 */
		long evalNanos;
		/** 沿连线累加出来的遮挡值（诊断用，也是日志里的关键数字）。 */
		float occlusionAcc;
		/** 0.12.6 诊断：主射线之外的 8 条偏移射线里，有几条"通透"（≤ {@link #OPEN_PATH_OCC}）。 */
		int lastOpenPaths;
		/** 0.12.6 诊断：本轮实际用的遮挡陡度 k（= 配置值 x 强度）。 */
		float lastK;
		/** 0.12.6 诊断：主射线（未放宽）的遮挡值，用来对比"放宽了多少"。 */
		float lastOccMain;
	/** 空间封闭度闸门（0=开阔不闷，1=封闭满物理）。 */
	float lastSpaceGate = 1.0f;
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
	}

	private static final Map<Integer, State> STATES = new HashMap<>();
	/** DSP 回退路径当前跟随的状态（最后一个被评估的声源）。 */
	private static volatile State dspState = new State();

	private Acoustics() {
	}

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
				// 播放途中被关掉：把已经挂上去的 EFX 摘干净（只做一次），
				// 否则低通会一直留在声源上 —— 表现是"关了还是闷"。
				if (wasEnabled) {
					wasEnabled = false;
					EfxEngine.bypassSource(resolveSourceId(instance));
					reset();
					CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效: 已关闭 → 摘掉 EFX / 恢复干净直通");
				}
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
				applyPosition(sourceId, st, center.x, center.y, center.z);
			}
			if (EfxEngine.isAvailable()) {
				syncReverb(st);
				EfxEngine.applyToSource(sourceId, st.directCutoff, st.directGain, st.sendGain, st.sendCutoff);
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
	 *   <li>遮挡值来自 {@link BlockAcoustics}（自己按方块声音组 + 完整方块 + 硬度 + 液体推导）。</li>
	 *   <li>非严格模式：再把两个端点各偏移 ±1 格的 8 个对角点算一遍 —— 但
	 *       <b>不再"取最小值"</b>（那样一条缝就把遮挡抹平成 0，效果全没）。
	 *       现在数"<b>有几条通透通路</b>"：{@code 放宽 = MAX_RELAX × min(1, 通路数 / 需要通路数)}，
	 *       不足量就只按比例放宽，且最多降到原值的 15%。</li>
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
			// 【0.12.54】9 条射线里最小遮挡（绕射：走最好走的那条路）
			double bestOffset = Double.MAX_VALUE;
		double occ = occMain;
		if (!strictOcclusion() && occMain > 0.0) {
			// 只要主射线被挡就试 8 个对角偏移；主射线本来就通透时遮挡必然是 0，不用白算。
			// 注意：这里不再取最小值，而是"数通路"——见方法注释与 MAX_RELAX。
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sy = -1; sy <= 1; sy += 2) {
					for (int sz = -1; sz <= 1; sz += 2) {
						// 【0.12.62】偏移改为垂直于"听者→声源"的平面内采样：原来用三维 (±1,±1,±1)，
						// 其中带 y=−1 的射线会钻进地面/台阶，被算成"被挡" → 被挡占比虚高到 8/8
						// → 一根八竿子打不着的柱子就能让遮挡吃满 1.0（实测日志：通透通路=0/8、GAINHF=0.017）。
						// 我们要采样的是"声束的横截面"，所以偏移必须在垂直平面内。
						Vec3d off = perpendicularOffset(center, ear, sx, sy, sz);
						// 【0.12.64】关键修复：偏移射线的起点可能落在【地面/地板】等实心方块内部
						//（垂直平面内的偏移有朝下/朝上的方向）→ occlusionAt 从方块内部起步，
						// 第一格立刻 +1.0 → 这几条永远算"被挡" → 被挡占比虚高到 8/8
						// → 一根柱子、甚至只挡上一块方块就让遮挡吃满 1.0（实测"拆了柱子还是全闷"）。
						// 物理上：紧贴声源的底座/地面不在"你我之间"，不该算遮挡 → 起点沿射线前进 1.5 格。
						Vec3d offFrom = center.add(off);
						Vec3d offTo = ear.add(off);
						Vec3d offDir = offTo.subtract(offFrom);
						double offLen = offDir.length();
						Vec3d offStart = offLen > 1.6 ? offFrom.add(offDir.multiply(1.5 / offLen)) : offFrom;
						double o = occlusionAt(world, offStart, offTo, jukebox);
						if (o < bestOffset) {
							bestOffset = o;
						}
						if (o <= OPEN_PATH_OCC) {
							openPaths++;
						}
					}
				}
			}
			int need = openPathsRequired();
			// 【0.12.27】软衰减：按"没被挡的射线占比"缩放遮挡。
			// 一盏栅栏/一格高方块只挡住少数射线 → 遮挡很小（符合"声音会绕过去"的直觉）；
			// 整面墙挡住全部射线 → 与原来一致。
			double blockedFrac = 1.0 - (openPaths / (double) (openPaths + 1));
			double relax = Math.max(0.0, 1.0 - Math.max(0.15, blockedFrac));
			relax = Math.min(relax, 0.92);
			// 【0.12.37 紧急修】"按被挡射线占比软衰减"只在【双方都开阔】时才有意义
			//（那种情况下面的 spaceGate 会把遮挡压到 0）。
			// 只要 spaceGate > 0（说明声源或听者处于封闭空间），就必须采用【主射线】的遮挡：
			// 否则"室内贴墙放唱片机、人站在室外某个角度"时，8 条偏移射线会从墙边绕过去，
			// 把整面墙的遮挡削到 8%（实测 bug：特定范围完全不变闷）。
			// 【0.12.40】改用【被挡射线占比】的幂次做软衰减 —— 这才是"一格方块 vs 一堵墙"的真正区别：
			//   一格方块 / 树干  → 只挡住少数射线（占比 ~0.1）→ 0.1^1.5≈0.03 → 几乎不闷 ✓
			//   整堵墙 / 封闭小屋 → 挡住全部射线（占比 1.0）→ 完整物理 ✓
			// （0.12.27 那版是"1 - 占比"直接线性，太狠；0.12.32 的空间闸门又会把露天一堵墙清零 ✗）
			// 【0.12.54】采用"最通畅那条路"的遮挡：门开着 / 有小缝 → 声音从那里过去 ✓

			double fracBlocked = 1.0 - (openPaths / 8.0);
			occ = occMain * Math.pow(Math.max(0.0, Math.min(1.0, fracBlocked)), 1.5);
			// 【0.12.65】"声路"覆盖：若存在一条可走过去的路线（半径内 BFS 可达），
			// 就按【绕行比】决定最终遮挡 —— 绕 2 格 ≈ 几乎无影响，绕 1 倍距离 ≈ 打三折多。
			// 不可达（pathLen<0）= 只能穿墙 → 保持上面的材质遮挡（明显闷）。
			int pathLen = roomPathLength(world, jukebox, ear); // 【0.12.69】Valve 式：房间连通域路径
			if (pathLen > 0 && pathLen <= center.distanceTo(ear) * 2.5) {
				double straight = Math.max(0.5, center.distanceTo(ear));
				double detour = Math.max(0.0, pathLen / straight - 1.0);
				double f = Math.max(0.05, 1.0 / (1.0 + 10.0 * detour));
				occ = Math.min(occ, occMain * f);
			}
			// 【0.12.32】按用户思路：真正决定"闷不闷"的是【声源/听者是否处在封闭空间】，
			// 而不是"中间隔没隔东西"。树、一格高方块、栅栏这类小障碍不该闷（声音会绕过去）。
			//   · 双方都在开阔空间 → 遮挡 ×0.15（树后几乎不闷）
			//   · 声源开阔、听者在室内 → ×0.85（你隔着自己家的墙听外面的唱片机，该闷）
			//   · 声源被封闭（小屋/矿洞里的唱片机）→ ×1.0（完整物理，门开/门关照旧生效）
			float gate = spaceGate(world, ear, center);
			st.lastSpaceGate = gate;
			occ *= gate; // 【0.12.50】重新启用，但 gate 已是连续的温和系数（0.55~1.0），不会再像 0.12.32 那样清零
			// 【0.12.46】对最终遮挡做时间平滑（时间常数约 8 刻 = 0.4 秒）。
			// 原因：偏移射线只有 8 条，"被挡占比"是 0/8…8/8 的整数台阶 —— 贴着墙听是 1.0，
			// 走开五六格跨过墙角时好几条同时变通透 → 遮挡一步跳下来 → 实测"声音突然变小、没有过渡"。
			// 平滑后任何跳变都变成 0.4 秒的过渡，听感自然。
			{
				long dt = nowTick - st.smoothOccTick;
				if (st.smoothOcc < 0.0f || dt <= 0L || dt > 100L) {
					st.smoothOcc = (float) occ;
				} else {
					float kk = (float) (1.0 - Math.exp(-dt / 8.0));
					st.smoothOcc += ((float) occ - st.smoothOcc) * kk;
				}
				st.smoothOccTick = nowTick;
				occ = st.smoothOcc;
			}
		}
		st.lastOccMain = (float) occMain;
		st.lastOpenPaths = openPaths;

		// 强度旋钮同时缩放"遮挡强度"：k_eff = k(配置) x 强度
		// 0 = 关闭（上面已提前返回）、1.0 = 默认、2.0 = 非常激进
		float k = absorption() * level;
		st.lastK = k;
		float cutoffNoAir = (float) Math.exp(-occ * k);
		st.occlusionAcc = (float) occ;

		// ---- 混响射线（M4/M5）：从唱片机按黄金角球面均匀发射，每条最多 4 次反弹 ----
		ReverbResult rr = traceReverb(world, center, ear, jukebox, occ, k);
		// 【0.12.55】反射通路优先：只要有一条反弹射线能"暴露到听者所在空间"（360° 无死角），
		// 就说明声音能绕过障碍从间接路径到达 —— 现实里硬表面反射几乎不损失能量（0.04~0.2 dB），
		// 所以这时不该被判成"密闭般闷"。幅度先取 0.15（保留一点空间感），后续可按实测再调。
		// 【0.12.60】互易搜索：从听者出发找"能走到唱片机"的路径（用户思路：用自己的射线接住它）
		int backPaths = listenerPathsTo(world, ear, center, jukebox);
		// 【0.12.61】边缘绕射：朝挡路方块的角发专门射线（"声音拐弯"的物理机制）
		int edgePaths = edgePathsTo(world, ear, center, jukebox);
		if (edgePaths > 0) {
			// 绕射比镜面反射多损失一些能量（Maekawa 低频渐近约 5 dB），所以封顶比 0.25 松一点
			occ = Math.min(occ, 0.45);
		}
		if (rr.earReach > 0 || backPaths > 0) {
			// 【0.12.59】关键修正：声音是【绕过去/反射过去】的，直线路径上那堵墙根本不该算进去 ✗。
			// 之前用 occ *= 0.15 缩放：当直线遮挡累积到 2~3 时，剩 0.3~0.45 → 高频仍被削 -17 dB ✗
			// （实测"躲在石柱后还是那个吊样"）。改成【绝对封顶】：
			occ = Math.min(occ, 0.25);
		}

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

		st.tDirectCutoff = clamp01(Math.max(0.015f, cutoff)); // 【0.12.53】滤波下限 -36 dB：真实墙体仍会漏一点中频，不会只剩低音嗡嗡
		st.tDirectGain = clamp01(Math.max(0.55f, gain)); // 【0.12.48】直通增益下限 0.40（-8 dB）：遮挡再深也听得清内容。只压增益、不碰遮挡判据（0.12.43 的教训）

		// ---- M6 方向性：把声源位置沿"反射来向"偏移（到听者的距离不变，所以音量不变） ----
		st.tPosX = center.x;
		st.tPosY = center.y;
		st.tPosZ = center.z;
		if (directionEnabled() && occ > 0.25 && rr.hasDirection && center.distanceTo(ear) <= 24.0) {
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

	private static boolean reverbDiffers(EfxEngine.Reverb a, EfxEngine.Reverb b) {
		final float e = 0.02f;
		return Math.abs(a.gain - b.gain) > e
				|| Math.abs(a.gainHF - b.gainHF) > e
				|| Math.abs(a.decayTime - b.decayTime) > 0.05f
				|| Math.abs(a.decayHFRatio - b.decayHFRatio) > e
				|| Math.abs(a.reflectionsGain - b.reflectionsGain) > e
				|| Math.abs(a.lateReverbGain - b.lateReverbGain) > e
				|| Math.abs(a.lateReverbDelay - b.lateReverbDelay) > 0.005f
				|| Math.abs(a.density - b.density) > e
				|| Math.abs(a.diffusion - b.diffusion) > e
				|| Math.abs(a.airAbsorptionGainHF - b.airAbsorptionGainHF) > 0.005f;
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
		/**
		 * 【0.12.55】反射通路计数：有多少个反弹命中点"能直视到听者"。
		 * 物理依据（外部查证）：硬表面一次镜面反射损失仅 10·log10(1−α) ≈ 0.04~0.2 dB（混凝土/玻璃 α≈0.01~0.05），
		 * 几乎可忽略 ⇒ 只要存在这样一条间接通路，听感应与直达声同样清楚。
		 */
		int earReach;
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

				// 【0.12.56】接收球判定（房间声学标准做法，用户提出的思路）：
				// 射线不必碰到听者、也不必从反射点直视听者 —— 只要它【从听者附近掠过】，
				// 听者就能"用自己的射线接住它"（= 从听者连一条短线到射线的最近点）。
				// 物理依据：声音是压力场，决定听到与否的是"有无传播路径经过听者附近"，
				// 而不是"能否看见音源"。反射只是延长路径的一种方式，与直达段同等对待。
				{
					Vec3d segEnd = hit == null ? end : hit.point();
					Vec3d near = closestPointOnSegment(origin, segEnd, ear);
					if (near != null && near.squaredDistanceTo(ear) <= EAR_REACH * EAR_REACH) {
						// 听者的"捕获射线"也要通（最近点到耳朵之间没有实体），
						// 否则贴着墙站在墙外、射线在墙内掠过会被误判为听到了。
						if (RayWalk.cast(world, near.add(ear.subtract(near).normalize().multiply(0.02)), ear, 24) == null) {
							out.earReach++;
						}
					}
				}
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
						out.earReach++;
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

	/** 需要几条通透通路才算"真的漏音"（配置 {@code physicsOcclusionPaths}，默认 3）。 */
	private static int openPathsRequired() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		int v = cfg == null ? DEFAULT_OPEN_PATHS : Math.max(DEFAULT_OPEN_PATHS, cfg.physicsOcclusionPaths);
		return Math.max(OPEN_PATHS_MIN, Math.min(OPEN_PATHS_MAX, v));
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
			// 【0.12.27】排除唱片机自身及其紧邻 3×3×3：
			// 射线终点带 ±0.4 偏移，否则会打到"唱片机旁的墙/脚下底座/头顶方块"，
			// 于是"把唱片机垫高 4 格、人站在正下方"也会被判成被挡（实测反直觉）。
			// 【0.12.41】只忽略"正上方/正下方"那一格（唱片机的底座 / 天花），
			// 不再忽略水平相邻的方块 —— 原来排除整个 3×3×3 时，
			// **贴墙放置的唱片机旁边那堵墙正好落在里面** → 8 条偏移射线全都看不见它
			// → 被挡占比≈0 → 遮挡被 ^1.5 削成 0（实测：挪离墙一格就正常 ✓）。
			if (skip != null && x == skip.getX() && z == skip.getZ() && Math.abs(y - skip.getY()) <= 1) {
				return true;
			}
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
				try {
					if (!hitsShape(bs.getCollisionShape(world, p), from, to, p)) {
						return true; // 草/火把/藤蔓这类没有碰撞体积的东西不算遮挡
					}
				} catch (Throwable e) {
					return true;
				}
			}
			noteProbe(bs, world, p); // 诊断：这次真的算它了（① 材质探针）
			acc[0] += BlockAcoustics.occlusionOf(bs, world, p);
			return acc[0] < MAX_OCC;
		});
		return Math.min(acc[0], MAX_OCC);
	}

	/**
	 * 射线 from→to 是否真的撞上这一格的碰撞形状。
	 *
	 * <p><b>为什么必须求交</b>：开关门/楼梯/玻璃板/栅栏这些"非完整方块"的碰撞形状会变，
	 * 只判"有没有碰撞体积"会把**开着的门**也算成挡（实测就是这个问题）。
	 *
	 * <p>满格方块（石头/木板/关着的门这类单盒且填满 0..1）直接判挡：
	 * 射线进这一格必然撞上，同时也避开"入射点正好落在边界上"的浮点误差。
	 * 其余情况用 {@link VoxelShape#raycast} 真正求交（它需要**世界坐标**，内部会 box.offset(pos)）；
	 * 若起点/终点已经落在实体内部（raycast 对这种情形可能返回 null），用包围盒补判。
	 */
	private static boolean hitsShape(VoxelShape shape, Vec3d from, Vec3d to, BlockPos pos) {
		try {
			Box bb = shape.getBoundingBox();
			boolean fullCube = bb.minX <= 1.0e-3 && bb.minY <= 1.0e-3 && bb.minZ <= 1.0e-3
					&& bb.maxX >= 1.0 - 1.0e-3 && bb.maxY >= 1.0 - 1.0e-3 && bb.maxZ >= 1.0 - 1.0e-3;
			if (fullCube) {
				return true;
			}
			if (shape.raycast(from, to, pos) != null) {
				return true;
			}
			return bb.contains(from) || bb.contains(to);
		} catch (Throwable t) {
			return true; // 保守：算挡住
		}
	}

	private static boolean strictOcclusion() {
		CloudDiscConfig cfg = CloudDiscClient.config();
		return cfg != null && cfg.physicsStrictOcclusion;
	}

	/**
	 * 空间封闭度闸门（用户提出的思路，0.12.32 实现）。
	 *
	 * <p>从某个点向 12 个方向各打一条射线；如果大多数射线都能跑出 14 格而不撞方块，
	 * 就认为这个点在开阔空间里（露天、树旁、一格方块旁边都算开阔 —— 声音会绕过去）。
	 *
	 * @return 0..1，越大表示越"该闷"
	 */
	private static float openness(World world, Vec3d p, Entity self) {
		int open = 0;
		int total = 0;
		final double[][] dirs = {
				{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
				{0.7, 0.7, 0}, {-0.7, 0.7, 0}, {0, 0.7, 0.7}, {0, 0.7, -0.7}, {0.7, 0, 0.7}, {-0.7, 0, -0.7}
		};
		for (double[] d : dirs) {
			total++;
			try {
				// 【0.12.34】起点必须离开方块本身 0.6 格：否则向下的射线会撞到脚下的底座、
				// 水平射线会撞到唱片机自己或紧贴的方块，于是"露天也会被判成封闭"（实测真凶）。
				Vec3d from = p.add(d[0] * 0.6, d[1] * 0.6, d[2] * 0.6);
				Vec3d to = p.add(d[0] * 14.0, d[1] * 14.0, d[2] * 14.0);
				BlockHitResult hit = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
						RaycastContext.FluidHandling.NONE, self));
				if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
					open++;
				} else if (false && hit.getPos().distanceTo(p) < 0.9) { // 【0.12.39】禁用：贴墙时它把墙忽略了，导致遮挡被闸门清零
					// 【0.12.35】只忽略 0.9 格以内的命中：那是脚下的地面 / 紧贴声源的方块（"就地放置"）。
					// 上一版写成 2.0，结果小房间的墙（常在 1.5~2 格处）也被当成"就地放置"，
					// 于是小屋里反而完全不闷 —— 实测反馈后收紧到 0.9。
					// 判断依据：开阔处 12 条射线基本都能跑满 14 格；小屋里几乎所有射线都在 1~3 格内撞墙。
					open++;
				}
			} catch (Throwable ignored) {
			}
		}
		return total == 0 ? 1.0f : (open / (float) total);
	}

	/** 双方空间封闭度 → 遮挡闸门（见调用处注释）。 */
	private static float spaceGate(World world, Vec3d ear, Vec3d center) {
		try {
			Entity self = MinecraftClient.getInstance().player;
			float openSrc = openness(world, center, self);
			float openEar = openness(world, ear, self);
			// 阈值放宽到 0.45：站在树下、屋檐下、半开放走廊里都算"开阔"（不该莫名变闷）
			boolean srcOpen = openSrc >= 0.45f;
			boolean earOpen = openEar >= 0.45f;
			if (srcOpen && earOpen) {
				// 都在开阔处（露天/树后/一格方块后/台阶后）：完全不闷 —— 声波绕过去，
				// 这也是用户实测最想要的行为（树后不该像隔墙一样）。
				// 【0.12.50】连续、温和：衍射让声波总能绕过开阔处的障碍，所以只削弱、绝不清零。
				return (float) (0.55 + 0.45 * (1.0 - Math.min(openSrc, openEar)));
			}
			if (srcOpen) {
				return 0.85f; // 声源在外面、你在室内 → 隔着自己的墙，该闷
			}
			return 1.0f; // 声源被封住（小屋/矿洞）→ 走完整物理，门开/门关照旧
		} catch (Throwable t) {
			return 1.0f;
		}
	}

	/** 线段 ab 上离点 p 最近的点（clamp 投影参数 t∈[0,1]）。 */
	private static Vec3d closestPointOnSegment(Vec3d a, Vec3d b, Vec3d p) {
		try {
			Vec3d ab = b.subtract(a);
			double len2 = ab.lengthSquared();
			if (len2 < 1.0e-9) {
				return a;
			}
			double t = p.subtract(a).dotProduct(ab) / len2;
			t = Math.max(0.0, Math.min(1.0, t));
			return a.add(ab.multiply(t));
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 【0.12.60】互易（双向）路径搜索：从【听者】向四周发射黄金角射线，各反弹 3 次，
	 * 看是否存在一条"能到达唱片机"的路径。物理依据：声传播互易 ——
	 * 从听者出发能走到声源的路径，等价于声源到听者的传播路径。
	 *
	 * <p>为什么必须从听者发射：从声源发射的 32 条金角射线方向均匀，很难恰好对准一扇门
	 * （实测"声音出不了屋子"），而在屋里反复反弹又永远碰不到听者；
	 * 从听者出发只需对准自己所在空间/走廊的出口，命中率高得多。
	 *
	 * @return 命中的路径条数（≥1 即认为存在通路）
	 */
	private static int listenerPathsTo(World world, Vec3d ear, Vec3d center, BlockPos jukebox) {
		int hits = 0;
		int rays = Math.max(16, Math.min(64, rays()));
		for (int i = 0; i < rays; i++) {
			double yy = 1.0 - 2.0 * (i + 0.5) / rays;
			double rr = Math.sqrt(Math.max(0.0, 1.0 - yy * yy));
			double th = GOLDEN_ANGLE * i;
			Vec3d dir = new Vec3d(rr * Math.cos(th), yy, rr * Math.sin(th)).normalize();
			Vec3d origin = ear;
			for (int b = 0; b < 3; b++) {
				Vec3d end = origin.add(dir.multiply(REVERB_MAX_DISTANCE));
				RayWalk.Hit hit;
				try {
					hit = RayWalk.cast(world, origin, end, MAX_REVERB_STEPS);
				} catch (Throwable t2) {
					break;
				}
				Vec3d segEnd = hit == null ? end : hit.point();
				// 这一段是否"到达唱片机"：直接命中唱片机方块，或从它 1.5 格内掠过且直线通畅
				if (hit != null && hit.pos.equals(jukebox)) {
					hits++;
					break;
				}
				Vec3d near = closestPointOnSegment(origin, segEnd, center);
				if (near != null && near.squaredDistanceTo(center) <= 2.25) {
					if (RayWalk.cast(world, near, center, 24) == null) {
						hits++;
						break;
					}
				}
				if (hit == null) {
					break;
				}
				// 镜面反射（dir - 2(dir·n)n），与追踪器同一公式
				double d = dir.dotProduct(hit.normal()) * 2.0;
				dir = dir.subtract(hit.normal().multiply(d)).normalize();
				origin = hit.point().add(hit.normal().multiply(0.002));
			}
			if (hits >= 3) {
				break; // 够用了，省开销
			}
		}
		return hits;
	}

	/**
	 * 【0.12.61】边缘绕射搜索（参考资料第 4 条：针对遮挡几何的【边缘】发射专门射线）。
	 *
	 * <p>为什么需要它：均匀撒向四周的金角射线要恰好穿过一扇门/绕过一根柱子，是"大海捞针"
	 * （实测日志：主射线遮挡=1.000 而两条路径搜索都返回 0）。现实里声音是【绕边缘】拐弯的，
	 * 所以应该直接朝"挡路那个方块的 8 个角"发射线，检查 听者→角→声源 这条折线是否两段都通畅。
	 *
	 * @return 可行的绕行折线条数（≥1 即存在绕射通路）
	 */
	private static int edgePathsTo(World world, Vec3d ear, Vec3d center, BlockPos jukebox) {
		int hits = 0;
		try {
			RayWalk.Hit h = RayWalk.cast(world, ear, center, MAX_REVERB_STEPS);
			if (h == null) {
				return 1; // 直线本来就通
			}
			BlockPos bp = h.pos;
			// 【0.12.63】在"第一个挡路点"周围半径 2 格的球面采样：
			// 原来只查那一个方块的 8 个角 —— 若射线是穿【墙】而过，8 个角全在墙体内部，
			// 两段都不通 → 找不到就在旁边一两格的【门/窗/缝隙】（实测"门开着但在屋外听还是闷"）。
			for (int dx = -2; dx <= 2; dx++) {
				for (int dy = -2; dy <= 2; dy++) {
					for (int dz = -2; dz <= 2; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						if (dx * dx + dy * dy + dz * dz > 6) {
							continue; // 只取半径 ~2.4 格内的点，控制开销
						}
						Vec3d p = new Vec3d(bp.getX() + 0.5 + dx, bp.getY() + 0.5 + dy, bp.getZ() + 0.5 + dz);
						if (RayWalk.cast(world, ear, p, MAX_REVERB_STEPS) == null
								&& RayWalk.cast(world, p, center, MAX_REVERB_STEPS) == null) {
							hits++;
							if (hits >= 2) {
								return hits; // 够用
							}
						}
					}
				}
			}
		} catch (Throwable t2) {
			return 0;
		}
		return hits;
	}

	/** 【0.12.62】把 (sx,sy,sz)∈{±1}³ 映射成"垂直于 听者→声源 轴"的平面内 8 个方向之一（半径 1 格）。 */
	private static Vec3d perpendicularOffset(Vec3d center, Vec3d ear, int sx, int sy, int sz) {
		try {
			Vec3d axis = ear.subtract(center);
			if (axis.lengthSquared() < 1.0e-9) {
				return new Vec3d(sx, sy, sz);
			}
			axis = axis.normalize();
			Vec3d ref = Math.abs(axis.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
			Vec3d u = axis.crossProduct(ref);
			if (u.lengthSquared() < 1.0e-9) {
				return new Vec3d(sx, sy, sz);
			}
			u = u.normalize();
			Vec3d v = axis.crossProduct(u).normalize();
			int idx = ((sx > 0 ? 1 : 0) << 2) | ((sy > 0 ? 1 : 0) << 1) | (sz > 0 ? 1 : 0);
			double ang = idx * (Math.PI / 4.0);
			return u.multiply(Math.cos(ang)).add(v.multiply(Math.sin(ang)));
		} catch (Throwable t) {
			return new Vec3d(sx, sy, sz);
		}
	}

	/**
	 * 【0.12.65】Steam-Audio 式「声路」寻路：在【可通行方块】（空气/水/开着的门/植物…）上做 BFS，
	 * 返回从唱片机到听者的最短步数（格）。返回 -1 表示半径内不可达（= 只能穿墙）。
	 *
	 * <p>业界依据：Valve 的 Portal 2 用"房间/传送门图"、Steam Audio 用"导航网格寻路"——
	 * 都是问"有没有一条【能走过去的路线】"，而不是"直线上有没有方块"。
	 * 这样才能做到：一根柱子（绕 2 格）几乎不影响 ✓；关着的门（无路）明显闷 ✓。
	 */
	private static int pathLengthTo(World world, BlockPos from, Vec3d ear) {
		try {
			BlockPos to = BlockPos.ofFloored(ear.x, ear.y, ear.z);
			int ddx = from.getX() - to.getX();
			int ddy = from.getY() - to.getY();
			int ddz = from.getZ() - to.getZ();
			if (ddx * ddx + ddy * ddy + ddz * ddz > 64 * 64) {
				return -1; // 太远不做寻路
			}
			java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
			java.util.HashMap<BlockPos, Integer> dist = new java.util.HashMap<>();
			queue.add(from);
			dist.put(from, 0);
			final int[] DX = {1, -1, 0, 0, 0, 0};
			final int[] DY = {0, 0, 1, -1, 0, 0};
			final int[] DZ = {0, 0, 0, 0, 1, -1};
			int cap = 9000;
			while (!queue.isEmpty() && dist.size() < cap) {
				BlockPos p = queue.poll();
				int d = dist.get(p);
				if (p.equals(to)) {
					return d;
				}
				if (d >= 48) {
					continue;
				}
				for (int i = 0; i < 6; i++) {
					BlockPos n = p.add(DX[i], DY[i], DZ[i]);
					if (dist.containsKey(n)) {
						continue;
					}
					BlockState bs;
					try {
						bs = world.getBlockState(n);
					} catch (Throwable e) {
						continue;
					}
					// 【0.12.68】高度限制：真正的门道/走廊都在同一层；爬到屋顶/墙头上属于
					// 【边缘绕射】而非"走一条路"，必须排除（实测楼梯屋顶与房子都被"穿"过）。
					int yLo = Math.min(from.getY(), to.getY()) - 1;
					int yHi = Math.max(from.getY(), to.getY()) + 1;
					if (n.getY() < yLo || n.getY() > yHi) {
						continue;
					}
					if (!walkableCell(world, n)) {
						continue; // 严格"能走过去"：净空 + 支撑（只能上 1 格）；不能从墙头上方绕
					}
					dist.put(n, d + 1);
					queue.add(n);
				}
			}
		} catch (Throwable t) {
			return -1;
		}
		return -1;
	}

	/** 该格是否为"空气/可穿过"（无碰撞体积）。 */
	private static boolean freeAt(World world, BlockPos p) {
		try {
			return world.getBlockState(p).getCollisionShape(world, p).isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}

	/** 该格是否为实心（有碰撞体积）。 */
	private static boolean solidAt(World world, BlockPos p) {
		return !freeAt(world, p);
	}

	/**
	 * 【0.12.67】"声音能走过去吗"的严格判定（业界用导航网格，我们用它近似）：
	 * 1) 本格与头顶一格都必须无碰撞（有净空）；2) 下方必须有支撑，或再下一格有支撑（= 上 1 格台阶）。
	 * 这样"从墙头上方/屋顶上方的空气绕过去"就不成立（上一版正因为把空气当可通行，
	 * 门开与门关都能绕，导致完全不闷）。副作用符合现实：1 格矮墙能跨（轻微衰减）、2 格以上跨不过（明显闷）。
	 */
	private static boolean walkableCell(World world, BlockPos n) {
		try {
			if (!freeAt(world, n) || !freeAt(world, n.up())) {
				return false;
			}
			BlockPos below = n.down();
			if (solidAt(world, below)) {
				return true;
			}
			return solidAt(world, below.down());
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * 【0.12.69】Valve 式「房间 + 传送门」声路（Portal 2 的做法）：
	 * 从声源与听者分别对【空气】做一次有界洪泛（flood fill）——即"声波能在哪些空气体积里自由传播"。
	 * <ul>
	 *   <li>两个连通域【相通】→ 它们之间存在开口（门/走廊/窗）= 传送门 ✓
	 *       路径长度 = 两次洪泛距离之和（相遇格）→ 用它算绕行比，做轻微/中等衰减 ✓</li>
	 *   <li>两个连通域【不相通】→ 中间是真墙（空气过不去）→ 由材质遮挡决定（明显闷）✓</li>
	 * </ul>
	 * 关键优势：洪泛是在【整个空气体积】里铺开的，所以房间里的柱子、家具、台阶【完全不影响】它 ✓✓
	 * （这正是"躲在柱子后面不该闷"的物理含义）；而墙/关闭的门把空气体积切断 ✓ → 自然明显闷 ✓。
	 *
	 * @return 房间内路径格数；-1 表示两者空气不相通（只能穿墙）
	 */
	private static int roomPathLength(World world, BlockPos from, Vec3d ear) {
		try {
			BlockPos to = BlockPos.ofFloored(ear.x, ear.y, ear.z);
			int ddx = from.getX() - to.getX();
			int ddy = from.getY() - to.getY();
			int ddz = from.getZ() - to.getZ();
			if (ddx * ddx + ddy * ddy + ddz * ddz > 48 * 48) {
				return -1;
			}
			java.util.HashMap<BlockPos, Integer> a = floodAir(world, from, 2200);
			java.util.HashMap<BlockPos, Integer> b = floodAir(world, to, 2200);
			int best = Integer.MAX_VALUE;
			for (java.util.Map.Entry<BlockPos, Integer> e : a.entrySet()) {
				Integer other = b.get(e.getKey());
				if (other != null) {
					int sum = e.getValue() + other;
					if (sum < best) {
						best = sum;
					}
				}
			}
			return best == Integer.MAX_VALUE ? -1 : best;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** 在空气里做有界 BFS，返回"格 → 距起点步数"。 */
	private static java.util.HashMap<BlockPos, Integer> floodAir(World world, BlockPos start, int cap) {
		java.util.HashMap<BlockPos, Integer> dist = new java.util.HashMap<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		dist.put(start, 0);
		queue.add(start);
		final int[] DX = {1, -1, 0, 0, 0, 0};
		final int[] DY = {0, 0, 1, -1, 0, 0};
		final int[] DZ = {0, 0, 0, 0, 1, -1};
		while (!queue.isEmpty() && dist.size() < cap) {
			BlockPos p = queue.poll();
			int d = dist.get(p);
			if (d >= 32) {
				continue;
			}
			for (int i = 0; i < 6; i++) {
				BlockPos n = p.add(DX[i], DY[i], DZ[i]);
				if (dist.containsKey(n)) {
					continue;
				}
				if (!freeAt(world, n)) {
					continue; // 空气才连通；墙/关着的门切断体积 ✓
				}
				dist.put(n, d + 1);
				queue.add(n);
			}
		}
		return dist;
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
	 */
	private static void applyPosition(int sourceId, State st, double centerX, double centerY, double centerZ) {
		if (!st.posSeeded) {
			return;
		}
		double dx = st.posX - centerX;
		double dy = st.posY - centerY;
		double dz = st.posZ - centerZ;
		if (dx * dx + dy * dy + dz * dz < 0.01) {
			return; // 没偏移：保持原版给的位置
		}
		try {
			AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) st.posX, (float) st.posY, (float) st.posZ);
		} catch (Throwable t) {
			// 位置偏移失败不影响播放
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
		CloudDiscClient.LOGGER.info("[CloudDisc] 物理声效[M7]: source={} EFX={} 遮挡累积={} 主射线遮挡={} 通透通路={}/8 吸收k={} 直通截止(GAINHF)={} 直通增益={} 开阔度={} 距离={}格 水下={} 位置偏移={}格 评估耗时={}ms"
						+ "｜sendGain={} sendCutoff={} 逐层反射率={} 平均反射率={} 自由程={}格",
				sourceId,
				EfxEngine.isAvailable() ? "可用(" + EfxEngine.bands() + "段)" : "不可用→DSP",
				fmt(st.occlusionAcc), fmt(st.lastOccMain), st.lastOpenPaths, fmt(st.lastK),
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

	/** 对一段单声道、归一化样本做处理：直通低通 + 增益 + 混响。EFX 可用时一个样本都不碰。 */
	public static void filterMono(float[] buf, int offset, int frames, float sampleRate) {
		if (EfxEngine.isAvailable() || !isActive() || sampleRate <= 0.0f) {
			if (smoothCutoffHz != -1.0f) {
				smoothCutoffHz = -1.0f;
				lpState = 0.0f;
			}
			return;
		}
		State st = dspState;
		if (st == null) {
			return;
		}
		prepareReverb(sampleRate);

		float directCutoff = st.directCutoff;
		float targetHz = (float) (MAX_CUTOFF_HZ * Math.pow(MIN_CUTOFF_HZ / MAX_CUTOFF_HZ, 1.0f - directCutoff));
		// 平滑按时间收敛（1 - exp(-块时长/tau)），不按块数。
		float k = (float) (1.0 - Math.exp(-(double) frames / (SMOOTH_SECONDS * sampleRate)));
		if (smoothCutoffHz < 0.0f) {
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
