/*
 * AcousticsMath —— 物理声效"数值对照"自检脚本（不依赖 Minecraft，纯公式）。
 *
 * 跑法（JDK 17+，本项目用 Minecraft 自带的 java-runtime-gamma-snapshot）：
 *     java tools/AcousticsMath.java
 *
 * 它做的事：把 src/main/java/dev/clouddisc/audio/Acoustics.java 里
 * "遮挡累积 → 放宽(漏音) → 直通截止(AL_LOWPASS_GAINHF) → 直通增益(AL_LOWPASS_GAIN)" 这一段
 * 的公式<b>照抄一遍</b>，对几张代表性材质算出数值，并同时给出 0.12.5 / 0.12.6 的同一格，
 * 用来回答"为什么门关着和门开着听起来一样"以及"0.12.7 改完是多少"。
 *
 * 注意：这里只验证"给定遮挡值 → 下发的参数"，不验证"遮挡值本身算得对不对"
 * （那要跑游戏，看"材质探针"日志）。两件事不要混。
 * 遮挡累积值来自 BlockAcoustics 的材质表 + 0.12.7 的"射线命中碰撞形状才累加"规则：
 *   door CLOSED : WOOD 0.55 x 0.8(非不透明材质) = 0.44   （旧版还要再 x0.5 几何打折 = 0.22）
 *   door OPEN   : 碰撞形状为空 -> 一分不算 = 0.00
 *   planks      : WOOD 0.55（不透明完整方块，无折扣）= 0.55
 *   glass       : GLASS 0.25 x 0.8 = 0.20（0.12.7 把基础值 0.50→0.25 抵消掉删掉的 x0.5，实际值不变）
 *   stone       : STONE 1.00
 *
 * 文件刻意保持纯 ASCII（只输出英文），避免中文 Windows 控制台的编码问题。
 */
public final class AcousticsMath {

	// ---- 0.12.7（当前）的常数：与 Acoustics.java 一一对应 ----
	static final double K_NEW = 4.5;              // ABSORPTION_DEFAULT
	static final double GATE_OCC = 0.6;           // OPENNESS_GATE_OCC
	static final double FLOOR_COEF = 0.2;         // OPENNESS_FLOOR_COEF
	static final double MIN_CUTOFF_NEW = 0.005;   // MIN_DIRECT_CUTOFF
	static final double GAIN_EXP_NEW = 0.2;       // DIRECT_GAIN_EXP
	static final double SHARED = 0.9;             // 代表值：站在开阔房间里的 avgShared
	static final int OPEN_PATH_TOTAL = 8;         // 偏移射线总数
	static final int PATHS_NEED_NEW = 6;          // DEFAULT_OPEN_PATHS（0.12.7）
	static final double RELAX_CAP_NEW = 0.40;     // DEFAULT_RELAX_MAX（0.12.7）

	// ---- 0.12.9（当前）的常数 ----
	static final double MIN_AUDIBLE_GAIN = 0.25;   // Acoustics.MIN_AUDIBLE_DIRECT_GAIN（可听下限）
	static final double MIN_AUDIBLE_CUTOFF = 0.02; // Acoustics.MIN_AUDIBLE_DIRECT_CUTOFF
	static final double SEND_CUTOFF_MIN = 0.20;    // Acoustics.SEND_CUTOFF_MIN（混响发送高频的下限）
	static final double SEND_OCC_K = 0.5;          // Acoustics.SEND_OCC_K（比直通的 k 温和得多）
	static final double HF_REFL_BASE = 0.60;       // Acoustics.HF_REFL_BASE（每层反射面的高频增益基线）

	// ---- 0.12.6（旧）的常数 ----
	static final int PATHS_NEED_OLD = 3;          // 0.12.6 的 physicsOcclusionPaths 默认值
	static final double RELAX_CAP_OLD = 0.85;     // 0.12.6 写死的 MAX_RELAX

	// ---- 0.12.5（更旧）的常数 ----
	static final double K_OLD = 3.0;
	static final double MIN_CUTOFF_OLD = 0.02;
	static final double GAIN_EXP_OLD = 0.1;
	static final double GEOM_OLD = 0.5;           // 0.12.5/0.12.6 的"非完整方块 x0.5"（0.12.7 已删）

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** 0.12.7 的放宽规则：只有 >= 门槛才放宽，且幅度有上限。 */
	static double relaxNew(int openPaths) {
		if (openPaths < PATHS_NEED_NEW) {
			return 0.0; // 达不到"大多数通路都通透" -> 一分不放宽
		}
		double excess = clamp((openPaths - PATHS_NEED_NEW + 1) / (double) (OPEN_PATH_TOTAL - PATHS_NEED_NEW + 1),
				0.0, 1.0);
		return RELAX_CAP_NEW * excess;
	}

	/** 0.12.6 的放宽规则：3 条就砍 85%。 */
	static double relaxOld(int openPaths) {
		return RELAX_CAP_OLD * Math.min(1.0, openPaths / (double) PATHS_NEED_OLD);
	}

	/** 0.12.7：带门槛的开阔度修正 + 增益指数 0.2 + 下限 0.005。 */
	static double[] newPipeline(double occ, double k) {
		double cutoffNoAir = Math.exp(-occ * k);
		double gate = clamp(occ / GATE_OCC, 0.0, 1.0);
		double opennessFloor = FLOOR_COEF * Math.sqrt(SHARED) * (1.0 - gate);
		double cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		double gain = Math.pow(cutoffWithShared, GAIN_EXP_NEW);
		double cutoff = Math.max(MIN_CUTOFF_NEW, cutoffWithShared); // dist<=12 时 air=1
		return new double[] {cutoff, gain};
	}

	/** 0.12.6：同样的下游，但放宽规则是"3 条砍 85%"。 */
	static double[] old126Pipeline(double occMain, int openPaths, double k) {
		double occ = occMain * (1.0 - relaxOld(openPaths));
		double cutoffNoAir = Math.exp(-occ * k);
		double gate = clamp(occ / GATE_OCC, 0.0, 1.0);
		double opennessFloor = FLOOR_COEF * Math.sqrt(SHARED) * (1.0 - gate);
		double cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		double gain = Math.pow(cutoffWithShared, GAIN_EXP_NEW);
		return new double[] {Math.max(MIN_CUTOFF_NEW, cutoffWithShared), gain, occ};
	}

	/** 0.12.5：无门槛的开阔度修正 + 增益指数 0.1 + 下限 0.02。 */
	static double[] oldPipeline(double occ) {
		double cutoffNoAir = Math.exp(-occ * K_OLD);
		double opennessFloor = FLOOR_COEF * Math.sqrt(SHARED);
		double cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		double gain = Math.pow(cutoffWithShared, GAIN_EXP_OLD);
		double cutoff = Math.max(MIN_CUTOFF_OLD, cutoffWithShared);
		return new double[] {cutoff, gain};
	}

	/**
	 * 0.12.9（当前）：在 0.12.7 的基础上加"可听下限"。
	 * <p>生产实现是 {@code Acoustics.directParams(...)}（0.12.9 抽成了纯函数，
	 * {@code tools/PhysicsParamsTest.java} 直接调它；这里是为对照再写一遍）。
	 */
	static double[] new129Pipeline(double occ, double k) {
		double[] r = newPipeline(occ, k);
		return new double[] {Math.max(MIN_AUDIBLE_CUTOFF, r[0]), Math.max(MIN_AUDIBLE_GAIN, r[1])};
	}

	/**
	 * 0.12.8（已删除）的混响发送高频 —— Bug A 的旧公式：
	 * {@code occCut = exp(-occ*k)}、{@code sendCutoff = occCut*(1-w)+w}。
	 */
	static double sendCutoff128(double occ, double k, double w) {
		double occCut = Math.exp(-occ * k);
		return occCut * (1.0 - w) + w;
	}

	/**
	 * 0.12.9（当前）的混响发送高频 —— 生产实现是 {@code Acoustics.sendCutoffFor(occ, w, 反射率)}。
	 * <pre>
	 *   occSend = 0.20 + 0.80*exp(-occ*0.5)
	 *   base    = occSend*(1-w) + w
	 *   mat     = 0.60 + 0.40*反射率
	 *   return  clamp(base*mat, 0.20, 1)
	 * </pre>
	 */
	static double sendCutoff129(double occ, double w, double refl) {
		double occSend = SEND_CUTOFF_MIN + (1.0 - SEND_CUTOFF_MIN) * Math.exp(-occ * SEND_OCC_K);
		double base = occSend * (1.0 - w) + w;
		double mat = HF_REFL_BASE + (1.0 - HF_REFL_BASE) * clamp(refl, 0.0, 1.0);
		return Math.max(SEND_CUTOFF_MIN, Math.min(1.0, base * mat));
	}

	static double db(double v) {
		return 20.0 * Math.log10(Math.max(1.0e-9, v));
	}

	static String r2(double v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}

	public static void main(String[] args) {
		System.out.printf("openness shared airspace (representative avgShared) = %.2f%n", SHARED);
		System.out.printf("openness floor at shared=%.2f -> %.4f (0.12.5 used it unconditionally)%n",
				SHARED, FLOOR_COEF * Math.sqrt(SHARED));
		System.out.println();

		// ---------------------------------------------------------------- 0.12.7 新增：门关/门开
		// 遮挡累积值（0.12.7 规则：射线必须真的命中碰撞形状；材质值本身，不再做几何打折）
		String[] names = {
				"door CLOSED (oak_door)",
				"door OPEN   (oak_door)",
				"1x planks   (oak)     ",
				"1x glass    (block)   ",
				"1x stone    (block)   ",
				"2x stone    (stacked) ",
		};
		//                      门关    门开   木板   玻璃   石头   两层石头
		double[] occs =       {0.44,  0.00,  0.55,  0.20,  1.00,  2.00};
		double[] occsOld126 = {0.22,  0.00,  0.55,  0.20,  1.00,  2.00}; // 门关被 x0.5 几何打折
		// 0.12.5：木板基础值还是 0.82，门 = 0.82 x0.8(非不透明) x0.5(几何) = 0.328
		double[] occsOld125 = {0.33,  0.00,  0.82,  0.20,  1.00,  2.00};

		System.out.println("== 0.12.7 scene table: occlusion accumulated by the ray (no relax applied) ==");
		System.out.printf("%-22s %10s %10s %10s %8s %8s%n",
				"scene", "occ(0.12.7)", "occ(0.12.6)", "GAINHF", "HF dB", "GAIN");
		for (int i = 0; i < names.length; i++) {
			double[] r = newPipeline(occs[i], K_NEW);
			System.out.printf("%-22s %10s %10s %10.4f %8.1f %8.3f%n",
					names[i], r2(occs[i]), r2(occsOld126[i]), r[0], db(r[0]), r[1]);
		}
		System.out.println();
		System.out.println("  door CLOSED vs OPEN = the audible gap the user asked for:");
		System.out.printf("    closed GAINHF %.4f (%.1f dB) / OPEN 1.0000 (0.0 dB) -> %.1f dB apart in HF%n",
				newPipeline(0.44, K_NEW)[0], db(newPipeline(0.44, K_NEW)[0]),
				-db(newPipeline(0.44, K_NEW)[0]));
		System.out.printf("    old 0.12.6 closed GAINHF %.4f (%.1f dB) -> only %.1f dB apart (\"almost the same\")%n",
				newPipeline(0.22, K_NEW)[0], db(newPipeline(0.22, K_NEW)[0]),
				-db(newPipeline(0.22, K_NEW)[0]));
		System.out.println();

		// ---------------------------------------------------------------- 放宽规则（本次 bug 的核心）
		System.out.println("== relax (leak) rule: old 0.12.6 vs new 0.12.7 ==");
		System.out.printf("%-10s %12s %12s %12s %12s%n",
				"open/8", "relax(old)", "relax(new)", "occ@door0.44", "GAINHF@door");
		for (int p = 0; p <= OPEN_PATH_TOTAL; p++) {
			double occNew = 0.44 * (1.0 - relaxNew(p));
			double occOld = 0.44 * (1.0 - relaxOld(p));
			System.out.printf("%-10s %12s %12s %12s %12.4f%n",
					p + "/8", r2(relaxOld(p)), r2(relaxNew(p)),
					r2(occNew) + " (old " + r2(occOld) + ")", newPipeline(occNew, K_NEW)[0]);
		}
		System.out.println();
		System.out.println("  the reported bug in one line: a 1-thick wall / a closed door easily gets 3-4 clear");
		System.out.println("  offset rays, so the OLD rule wiped ~85% of the occlusion while the door was CLOSED:");
		for (int p : new int[] {3, 4, 5}) {
			double[] oldR = old126Pipeline(0.44, p, K_NEW);
			double occNew = 0.44 * (1.0 - relaxNew(p));
			double[] newR = newPipeline(occNew, K_NEW);
			System.out.printf("    open=%d/8 : 0.12.6 GAINHF %.4f (%.1f dB)  ->  0.12.7 GAINHF %.4f (%.1f dB)%n",
					p, oldR[0], db(oldR[0]), newR[0], db(newR[0]));
		}
		System.out.println();

		// ---------------------------------------------------------------- 门关 / 门开（含放宽）
		System.out.println("== acceptance table: door closed / door open / 1 stone / 1 glass / 1 planks ==");
		System.out.println("   (relax paths = the two extremes: 0/8 clear = worst case, 8/8 clear = best case)");
		System.out.printf("%-22s %6s %10s %8s %8s %10s%n", "scene", "paths", "GAINHF", "HF dB", "GAIN", "gain dB");
		String[] acc = {"door CLOSED", "door OPEN", "1x stone", "1x glass", "1x planks"};
		double[] accOcc = {0.44, 0.00, 1.00, 0.20, 0.55};
		for (int i = 0; i < acc.length; i++) {
			for (int p : new int[] {0, 8}) {
				double occ = accOcc[i] <= 0.0 ? 0.0 : accOcc[i] * (1.0 - relaxNew(p));
				double[] r = newPipeline(occ, K_NEW);
				System.out.printf("%-22s %6s %10.4f %8.1f %8.3f %10.1f%n",
						acc[i], p + "/8", r[0], db(r[0]), r[1], db(r[1]));
			}
		}
		System.out.println();

		// ---------------------------------------------------------------- 0.12.6 的材质分档（不变的部分）
		double g = newPipeline(0.20, K_NEW)[0];
		double w = newPipeline(0.55, K_NEW)[0];
		double s = newPipeline(1.00, K_NEW)[0];
		System.out.println("== 0.12.7 material separation (GAINHF, 20log10) - unchanged from 0.12.6 ==");
		System.out.printf("  glass %.4f (%5.1f dB) | wood %.4f (%5.1f dB) | stone %.4f (%5.1f dB)%n",
				g, db(g), w, db(w), s, db(s));
		System.out.printf("  glass/wood ratio %.2fx (%.1f dB apart), wood/stone ratio %.2fx (%.1f dB apart)%n",
				g / w, db(g) - db(w), w / s, db(w) - db(s));
		System.out.println();

		System.out.println("== 0.12.5 (oldest: k=3.0, no gate, gain^0.1, floor 0.02, geometry x0.5) ==");
		System.out.printf("%-22s %10s %10s %8s %8s%n", "scene", "occ", "GAINHF", "HF dB", "GAIN");
		for (int i = 0; i < names.length; i++) {
			double[] r = oldPipeline(occsOld125[i]);
			System.out.printf("%-22s %10s %10.4f %8.1f %8.3f%n", names[i], r2(occsOld125[i]), r[0], db(r[0]), r[1]);
		}
		System.out.println();
		double[] oldStone = oldPipeline(1.00);
		System.out.println("== why 0.12.5 felt weak: 1x stone wall ==");
		System.out.printf("  raw exp(-1.0*3.0)            = %.4f  (%.1f dB) <- what physics says%n",
				Math.exp(-3.0), db(Math.exp(-3.0)));
		System.out.printf("  after unconditional openness = %.4f  (%.1f dB) <- what was actually sent%n",
				oldStone[0], db(oldStone[0]));
		System.out.printf("  0.12.7 sends                 = %.4f  (%.1f dB)%n", newPipeline(1.00, K_NEW)[0],
				db(newPipeline(1.00, K_NEW)[0]));
		System.out.println();
		// ---------------------------------------------------------------- 0.12.9：可听下限 + 混响发送高频（Bug A）
		System.out.println("== 0.12.9 direct audibility floor: \"obvious, not gone\" ==");
		System.out.println("   (floor policy: GAIN >= 0.25, GAINHF >= 0.02 -- applied to the occlusion result,");
		System.out.println("    BEFORE the underwater x0.1/x0.3 which is a different switch)");
		System.out.printf("%-26s %13s %13s %12s %12s%n",
				"scene", "GAINHF(0.12.8)", "GAINHF(0.12.9)", "GAIN(0.12.8)", "GAIN(0.12.9)");
		String[] floorNames = {"door CLOSED", "1x glass", "1x planks", "1x stone", "2x stone",
				"2.1 (real log)", "3.0 (max occ)"};
		double[] floorOcc = {0.44, 0.20, 0.55, 1.00, 2.00, 2.10, 3.00};
		for (int i = 0; i < floorNames.length; i++) {
			double[] a = newPipeline(floorOcc[i], K_NEW);
			double[] b = new129Pipeline(floorOcc[i], K_NEW);
			System.out.printf("%-26s %13.4f %13.4f %12.3f %12.3f%n",
					floorNames[i], a[0], b[0], a[1], b[1]);
		}
		System.out.println("   material contrast is NOT flattened: 1x glass 0.4066 > planks 0.0842(floor 0.0200)"
				+ " = stone = 2x stone, and GAIN 0.835 / 0.610 / 0.407 / 0.250.");
		System.out.println();

		System.out.println("== 0.12.9 reverb send HF (Bug A): exp(-occ*k) used to wipe the 4 sends out ==");
		System.out.println("   OLD = 0.12.8 traceReverb: sendCutoff = exp(-occ*k)*(1-w) + w");
		System.out.println("   NEW = 0.12.9 Acoustics.sendCutoffFor: (0.20+0.80*exp(-occ*0.5))*(0.60+0.40*refl), floor 0.20");
		System.out.printf("%-40s %13s %13s %10s %10s%n", "input (occ, k, reflectivity, w)", "OLD(0.12.8)", "NEW(0.12.9)", "OLD dB", "NEW dB");
		String[] scNames = {
				"real log line 1 (2.100, 4.5, 0.466)",
				"real log line 2 (2.000, 4.5, 0.466)",
				"clear sight     (0.000, 4.5, 0.600)",
				"1x stone        (1.000, 4.5, 0.600)",
				"glassy room     (0.000, 4.5, 0.900)",
				"wool room       (0.000, 4.5, 0.120)",
				"worst case      (3.000, 4.5, 0.000)",
		};
		double[][] scIn = {{2.100, 4.5, 0.466}, {2.000, 4.5, 0.466}, {0.000, 4.5, 0.600}, {1.000, 4.5, 0.600},
				{0.000, 4.5, 0.900}, {0.000, 4.5, 0.120}, {3.000, 4.5, 0.000}};
		double worstNew = 1.0;
		for (int i = 0; i < scNames.length; i++) {
			double oldV = sendCutoff128(scIn[i][0], scIn[i][1], 0.0);
			double newV = sendCutoff129(scIn[i][0], 0.0, scIn[i][2]);
			worstNew = Math.min(worstNew, newV);
			System.out.printf("%-40s %13.4f %13.4f %10.1f %10.1f%n",
					scNames[i], oldV, newV, db(oldV), db(newV));
		}
		System.out.printf("   assertion: every NEW value >= SEND_CUTOFF_MIN 0.20 -> worst = %.4f  %s%n",
				worstNew, worstNew >= SEND_CUTOFF_MIN - 1e-9 ? "PASS" : "FAIL");
		System.out.println("   (so the \"unless there is really no reflection at all\" exception is NOT needed:"
				+ " the floor is a hard one. Only underwater uses a second floor of 0.10.)");
		System.out.println();

		System.out.println("(audibility is NOT verified here - only the numbers. Please judge by ear in game.)");
	}
}
