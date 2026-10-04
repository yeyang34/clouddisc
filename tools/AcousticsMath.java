/*
 * AcousticsMath —— 物理声效"数值对照"自检脚本（不依赖 Minecraft，纯公式）。
 *
 * 跑法（JDK 17+，本项目用 Minecraft 自带的 java-runtime-gamma-snapshot）：
 *     java tools/AcousticsMath.java
 *
 * 它做的事：把 src/main/java/dev/clouddisc/audio/Acoustics.java 里
 * "遮挡累积 → 直通截止(AL_LOWPASS_GAINHF) → 直通增益(AL_LOWPASS_GAIN)" 这一段
 * 的公式<b>照抄一遍</b>，对几张代表性材质算出数值，并同时给出 0.12.5（旧）的同一格，
 * 用来回答"为什么以前只是稍微暗一点"。
 *
 * 注意：这里只验证"给定遮挡值 → 下发的参数"，不验证"遮挡值本身算得对不对"
 * （那要跑游戏，看"材质探针"日志）。两件事不要混。
 *
 * 文件刻意保持纯 ASCII（只输出英文），避免中文 Windows 控制台的编码问题。
 */
public final class AcousticsMath {

	// ---- 0.12.6（新）的常数：与 Acoustics.java 一一对应 ----
	static final double K_NEW = 4.5;              // ABSORPTION_DEFAULT
	static final double GATE_OCC = 0.6;           // OPENNESS_GATE_OCC
	static final double FLOOR_COEF = 0.2;         // OPENNESS_FLOOR_COEF
	static final double MIN_CUTOFF_NEW = 0.005;   // MIN_DIRECT_CUTOFF
	static final double GAIN_EXP_NEW = 0.2;       // DIRECT_GAIN_EXP
	static final double SHARED = 0.9;             // 代表值：站在开阔房间里的 avgShared

	// ---- 0.12.5（旧）的常数：写死在代码里的那几个 ----
	static final double K_OLD = 3.0;
	static final double MIN_CUTOFF_OLD = 0.02;
	static final double GAIN_EXP_OLD = 0.1;

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** 0.12.6：带门槛的开阔度修正 + 增益指数 0.2 + 下限 0.005。 */
	static double[] newPipeline(double occ, double k) {
		double cutoffNoAir = Math.exp(-occ * k);
		double gate = clamp(occ / GATE_OCC, 0.0, 1.0);
		double opennessFloor = FLOOR_COEF * Math.sqrt(SHARED) * (1.0 - gate);
		double cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		double gain = Math.pow(cutoffWithShared, GAIN_EXP_NEW);
		double cutoff = Math.max(MIN_CUTOFF_NEW, cutoffWithShared); // dist<=12 时 air=1
		return new double[] {cutoff, gain};
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

	static double db(double v) {
		return 20.0 * Math.log10(Math.max(1.0e-9, v));
	}

	public static void main(String[] args) {
		System.out.printf("openness shared airspace (representative avgShared) = %.2f%n", SHARED);
		System.out.printf("openness floor at shared=%.2f -> %.4f (0.12.5 used it unconditionally)%n",
				SHARED, FLOOR_COEF * Math.sqrt(SHARED));
		System.out.println();

		// 遮挡值来自 BlockAcoustics（0.12.6 表）：
		//   glass 0.50 x0.8(non-opaque) x0.5(non-full-cube) = 0.20
		//   wood  0.55 (opaque full cube)                    = 0.55
		//   stone 1.00 (opaque full cube)                    = 1.00
		String[] names = {
				"no blocker             ",
				"1x glass (0.12.6 table)",
				"1x wood plank          ",
				"1x stone               ",
				"2x stone (stacked)     ",
				"1x deepslate           ",
		};
		double[] occs = {0.0, 0.20, 0.55, 1.00, 2.00, 1.00};

		System.out.println("== 0.12.6 (new defaults: k=4.5, gate, gain^0.2, floor 0.005) ==");
		System.out.printf("%-24s %8s %10s %8s %10s %8s%n",
				"scene", "occ", "GAINHF", "HF dB", "GAIN", "gain dB");
		for (int i = 0; i < names.length; i++) {
			double[] r = newPipeline(occs[i], K_NEW);
			System.out.printf("%-24s %8.2f %10.4f %8.1f %10.3f %8.1f%n",
					names[i], occs[i], r[0], db(r[0]), r[1], db(r[1]));
		}

		System.out.println();
		System.out.println("== 0.12.6 with physicsSoundLevel = 2.0 (k = 4.5 x 2 = 9.0) ==");
		for (int i = 0; i < names.length; i++) {
			double[] r = newPipeline(occs[i], K_NEW * 2.0);
			System.out.printf("%-24s %8.2f %10.4f %8.1f %10.3f %8.1f%n",
					names[i], occs[i], r[0], db(r[0]), r[1], db(r[1]));
		}

		System.out.println();
		System.out.println("== 0.12.5 (old: k=3.0, no gate, gain^0.1, floor 0.02) ==");
		for (int i = 0; i < names.length; i++) {
			double[] r = oldPipeline(occs[i]);
			System.out.printf("%-24s %8.2f %10.4f %8.1f %10.3f %8.1f%n",
					names[i], occs[i], r[0], db(r[0]), r[1], db(r[1]));
		}

		System.out.println();
		double[] oldStone = oldPipeline(1.00);
		double[] newStone = newPipeline(1.00, K_NEW);
		System.out.println("== why 0.12.5 felt weak: 1x stone wall ==");
		System.out.printf("  raw exp(-1.0*3.0)            = %.4f  (%.1f dB) <- what physics says%n",
				Math.exp(-3.0), db(Math.exp(-3.0)));
		System.out.printf("  after unconditional openness = %.4f  (%.1f dB) <- what was actually sent%n",
				oldStone[0], db(oldStone[0]));
		System.out.printf("  0.12.6 sends                 = %.4f  (%.1f dB)%n", newStone[0], db(newStone[0]));
		System.out.println();

		// 分离度检查：玻璃 / 木板 / 石头 三档是不是落在可分辨的区间
		double g = newPipeline(0.20, K_NEW)[0];
		double w = newPipeline(0.55, K_NEW)[0];
		double s = newPipeline(1.00, K_NEW)[0];
		System.out.println("== 0.12.6 material separation (GAINHF, 20log10) ==");
		System.out.printf("  glass %.4f (%5.1f dB) | wood %.4f (%5.1f dB) | stone %.4f (%5.1f dB)%n",
				g, db(g), w, db(w), s, db(s));
		System.out.printf("  glass/wood ratio %.2fx (%.1f dB apart), wood/stone ratio %.2fx (%.1f dB apart)%n",
				g / w, db(g) - db(w), w / s, db(w) - db(s));
		System.out.println();
		System.out.println("(audibility is NOT verified here - only the numbers. Please judge by ear in game.)");
	}
}
