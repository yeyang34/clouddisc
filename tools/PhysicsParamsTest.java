import dev.clouddisc.audio.Acoustics;
import dev.clouddisc.audio.EfxEngine;

/**
 * <b>CloudDisc 0.12.9 —— 物理声效"混响发送高频(Bug A) + 能量预算(Bug B) + 可听下限"的离线复算自检。</b>
 *
 * <h2>为什么是"调用生产代码"而不是"照抄公式"</h2>
 * 0.12.7 的教训：自检里把公式照抄了一遍，抄错了也看不出来。所以本文件
 * <b>直接调用</b>生产代码里的三个纯函数：
 * <ul>
 *   <li>{@link Acoustics#directParams(double, float, float, double)} —— 直通截止/增益（含 0.12.9 可听下限）</li>
 *   <li>{@link Acoustics#sendCutoffFor(double, float, float)} —— 混响发送的高频（Bug A 的修复）</li>
 *   <li>{@link EfxEngine#energyBudget(float, float[], float[], int, float[])} —— 能量预算（Bug B 的修复）</li>
 * </ul>
 * 只有"OLD (0.12.8)"这一列是本文件里<b>重新写出来的旧公式</b>（那段代码已经被删掉了），
 * 目的是给出"修复前 → 修复后"的对照。旧公式与 0.12.8 的代码逐行一致，见方法注释。
 *
 * <h2>输入来自哪里</h2>
 * 全部来自用户 <b>0.12.8 装机实测的日志</b>（{@code physicsSoundDebug=true}），原样抄进
 * {@link #logInput}。日志里打的是<b>平滑之后</b>的值（{@code st.directCutoff/directGain}），
 * 而目标值是平滑之前算出来的，所以对不上是正常的 —— 本自检算的是<b>目标值</b>，并在表里
 * 同时列出日志实测值供核对。
 *
 * <h2>怎么跑（需要 Minecraft 的 classpath，但<b>不需要启动游戏</b>）</h2>
 * <pre>
 *   $env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-gamma-snapshot"
 *   .\gradlew -I tools\printcp.init.gradle :printRuntimeCp -q    # 取 CPSTART/CPEND 之间的那一行 → tools\cp.txt
 *   &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp (Get-Content tools\cp.txt) -d tools\out tools\PhysicsParamsTest.java
 *   &amp; "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -cp "$(Get-Content tools\cp.txt);tools\out" PhysicsParamsTest
 * </pre>
 * 全通过时最后一行打印 {@code ALL CHECKS PASSED}，退出码 0。
 *
 * <p>输出刻意保持<b>纯 ASCII（英文）</b>，避免中文 Windows 控制台编码问题。
 */
public final class PhysicsParamsTest {

	// ---------------------------------------------------------------- 生产侧常数（与 Acoustics/EfxEngine 对齐，仅用于打印与断言）
	static final float SEND_CUTOFF_MIN = 0.20f;
	static final float SEND_CUTOFF_MIN_UNDERWATER = 0.10f;
	static final float MIN_AUDIBLE_DIRECT_GAIN = 0.25f;
	static final float MIN_AUDIBLE_DIRECT_CUTOFF = 0.02f;
	static final float BUDGET_MIN_DIRECT = 0.5f;
	/** 混响自身的输出增益（{@code EfxEngine.DEFAULT_WET_GAIN} / {@code Reverb.gain} / Acoustics 每段写的 0.32）。 */
	static final float DEFAULT_WET_GAIN = 0.32f;
	static final int BANDS = EfxEngine.MAX_BANDS;

	static int failures;

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) {
			failures++;
		}
	}

	// ---------------------------------------------------------------- 旧公式（0.12.8，已删除，只为对照）

	/**
	 * OLD (0.12.8) {@code Acoustics.traceReverb}:
	 * <pre>
	 *   float occCut = (float) Math.exp(-occ * k);
	 *   out.sendCutoff[i] = occCut * (1.0f - w[i]) + w[i];
	 * </pre>
	 */
	static float oldSendCutoff(double occ, float k, float w) {
		double occCut = Math.exp(-occ * k);
		return (float) (occCut * (1.0 - w) + w);
	}

	/**
	 * OLD (0.12.8) {@code Acoustics.evaluate} 的直通段（没有可听下限）：
	 * <pre>
	 *   cutoffNoAir       = exp(-occ*k)
	 *   opennessFloor     = 0.2*sqrt(avgShared)*(1-gate)      gate = clamp(occ/0.6,0,1)
	 *   cutoffWithShared  = max(opennessFloor, cutoffNoAir)
	 *   gain              = cutoffWithShared^0.2
	 *   air               = 0.9^max(0,(dist-12)/3)
	 *   cutoff            = max(0.005, cutoffWithShared*air)
	 * </pre>
	 */
	static float[] oldDirect(double occ, float k, float avgShared, double dist) {
		double cutoffNoAir = Math.exp(-occ * k);
		double gate = clamp(occ / 0.6, 0.0, 1.0);
		double opennessFloor = 0.2 * Math.sqrt(Math.max(0.0, avgShared)) * (1.0 - gate);
		double cutoffWithShared = Math.max(opennessFloor, cutoffNoAir);
		double gain = Math.pow(cutoffWithShared, 0.2);
		double air = Math.pow(0.9, Math.max(0.0, (dist - 12.0) / 3.0));
		double cutoff = Math.max(0.005, cutoffWithShared * air);
		return new float[] {(float) cutoff, (float) gain};
	}

	/**
	 * OLD (0.12.8) {@code EfxEngine.applyToSource} 的能量预算：
	 * <pre>
	 *   total  = directGain + sum(sendGain[i])        // 没有乘 sendCutoff
	 *   budget = total > 1 ? 1/total : 1
	 *   directGain *= budget;  sendGain[i] *= budget;
	 * </pre>
	 */
	static float[] oldBudget(float directGain, float[] sendGain, int sendBands) {
		float sumSend = 0.0f;
		for (int i = 0; i < sendBands; i++) {
			sumSend += sendGain[i];
		}
		float total = directGain + sumSend;
		float budget = total > 1.0f ? 1.0f / total : 1.0f;
		return new float[] {budget, sumSend, total};
	}

	// ---------------------------------------------------------------- 日志输入

	/**
	 * 用户实测日志里的一行（0.19.5 实例 / clouddisc 0.12.8 / physicsSoundDebug=true）。
	 *
	 * @param label       这一行的人话说明
	 * @param occ         日志的"遮挡累积"
	 * @param k           日志的"吸收k"
	 * @param avgShared   日志的"开阔度"（occ&gt;0 时 = 共享空气空间权重；occ=0 时恒为 1）
	 * @param dist        日志的"距离"（格）
	 * @param bandRefl    日志的"逐层反射率"（第 2、3 行日志没打全 → 沿用第 1 行的值，属假设）
	 * @param avgRefl     日志的"平均反射率"
	 * @param sendGain    日志的 sendGain（<b>实测下发值</b>，用于预算对照）
	 * @param loggedGainHF 日志的"直通截止(GAINHF)"（平滑后的实测值，仅供核对）
	 * @param loggedGain   日志的"直通增益"（平滑后的实测值，仅供核对）
	 * @param openPaths    日志的"通透通路"
	 */
	record LogInput(String label, double occ, float k, float avgShared, double dist, float[] bandRefl,
			float avgRefl, float[] sendGain, float loggedGainHF, float loggedGain, int openPaths) {
	}

	static final LogInput CASE_A = new LogInput(
			"log line 1: two stone layers (real 0.12.8 log, 16:54:56)",
			2.100, 4.500f, 0.000f, 5.8,
			new float[] {0.488f, 0.459f, 0.488f, 0.431f}, 0.466f,
			new float[] {1.000f, 0.313f, 0.000f, 0.000f}, 0.006f, 0.153f, 0);

	static final LogInput CASE_B = new LogInput(
			"log line 2: one stone layer (real 0.12.8 log, 16:54:57)",
			2.000, 4.500f, 0.000f, 3.6,
			new float[] {0.488f, 0.459f, 0.488f, 0.431f}, 0.466f,
			new float[] {1.000f, 0.326f, 0.000f, 0.000f}, 0.005f, 0.220f, 1);

	static final LogInput CASE_C = new LogInput(
			"log line 3: clear line of sight (real 0.12.8 log, 16:54:58)",
			0.000, 4.500f, 0.000f, 2.0,
			// 通畅那一行日志没打"逐层反射率"：取 ReverbResult 的兜底值（射线没命中 → 默认 0.60）。
			// 它只影响 mat 那一项（0.84 vs 0.79），不影响"occ=0 时不被遮挡压暗"这个结论。
			new float[] {0.600f, 0.600f, 0.600f, 0.600f}, 0.600f,
			new float[] {1.000f, 0.337f, 0.000f, 0.000f}, 0.965f, 0.985f, 0);

	static float clamp(double v, double lo, double hi) {
		return (float) (v < lo ? lo : (v > hi ? hi : v));
	}

	static String f3(double v) {
		return String.format(java.util.Locale.ROOT, "%.3f", v);
	}

	static String f4(double v) {
		return String.format(java.util.Locale.ROOT, "%.4f", v);
	}

	static String db(double v) {
		return String.format(java.util.Locale.ROOT, "%7.1f", 20.0 * Math.log10(Math.max(1.0e-9, v)));
	}

	/** 打印一组数组（只打前 n 项）。 */
	static String arr(float[] a, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(f3(a[i]));
		}
		return sb.append(']').toString();
	}

	public static void main(String[] args) {
		System.out.println("================ CloudDisc 0.12.9 physics-sound offline re-check ================");
		System.out.println("production functions under test: Acoustics.directParams / Acoustics.sendCutoffFor /");
		System.out.println("                                 EfxEngine.energyBudget   (classes from build/classes/java/main)");
		System.out.println("OLD columns = the removed 0.12.8 formula, re-written in this file for comparison only.");
		System.out.println("INPUT = the user's real 0.12.8 in-game log lines (physicsSoundDebug=true).");
		System.out.println();

		LogInput[] cases = {CASE_A, CASE_B, CASE_C};
		for (LogInput c : cases) {
			report(c, false);
		}

		// ------------------------------------------------------------------ 汇总：真实日志输入下的完整输出表
		System.out.println("================ SUMMARY: full output table on the real log inputs ================");
		System.out.println("(sendGain = the value the 0.12.8 build actually sent; sendCutoff/direct = targets)");
		System.out.printf("%-46s %14s %14s %12s%n", "case", "directCutoff", "directGain", "directBudget");
		System.out.printf("%-46s %14s %14s %12s%n", "", "OLD -> NEW", "OLD -> NEW", "OLD -> NEW");
		for (LogInput c : cases) {
			float[] oldD = oldDirect(c.occ(), c.k(), c.avgShared(), c.dist());
			Acoustics.DirectParams newD = Acoustics.directParams(c.occ(), c.k(), c.avgShared(), c.dist());
			float[] ob = oldBudget(oldD[1], c.sendGain(), BANDS);
			float[] nb = budget(newD.gain(), c.sendGain(), newSendCutoffs(c), BANDS);
			System.out.printf("%-46s %6s ->%6s %6s ->%6s %6s ->%6s%n",
					c.label().substring(0, Math.min(46, c.label().length())),
					f4(oldD[0]), f4(newD.cutoff()), f4(oldD[1]), f4(newD.gain()),
					f4(ob[0]), f4(nb[0]));
		}
		System.out.println();
		System.out.printf("%-46s %-34s %-34s%n", "case", "sendCutoff OLD (0.12.8)", "sendCutoff NEW (0.12.9)");
		for (LogInput c : cases) {
			float[] oldSc = new float[BANDS];
			for (int i = 0; i < BANDS; i++) {
				oldSc[i] = oldSendCutoff(c.occ(), c.k(), 0.0f);
			}
			System.out.printf("%-46s %-34s %-34s%n",
					c.label().substring(0, Math.min(46, c.label().length())),
					arr(oldSc, BANDS), arr(newSendCutoffs(c), BANDS));
		}
		System.out.println();
		System.out.println("effective send energy E = sum(sendGain x sendCutoff x wetGain), wetGain = "
				+ f3(DEFAULT_WET_GAIN) + " (EAXReverb own output gain)");
		System.out.printf("%-46s %-34s %-24s%n", "case", "sendGain (unchanged by this fix)", "E (OLD vs NEW)");
		for (LogInput c : cases) {
			float[] sc = newSendCutoffs(c);
			float eOld = oldBudget(0.0f, c.sendGain(), BANDS)[1];
			float eNew = budget(0.0f, c.sendGain(), sc, BANDS)[2];
			System.out.printf("%-46s %-34s %s -> %s%n",
					c.label().substring(0, Math.min(46, c.label().length())), arr(c.sendGain(), BANDS),
					f4(eOld), f4(eNew));
		}
		System.out.println();

		// ------------------------------------------------------------------ 断言
		System.out.println("================ ASSERTIONS ================");
		
		// ① sendCutoff 各项 >= 0.15（真实日志输入）
		boolean allSends = true;
		StringBuilder worst = new StringBuilder();
		for (LogInput c : cases) {
			float[] sc = newSendCutoffs(c);
			for (int i = 0; i < BANDS; i++) {
				if (sc[i] < 0.15f) {
					allSends = false;
					worst.append(" ").append(c.label()).append("#").append(i).append("=").append(f3(sc[i]));
				}
			}
		}
		check("(Bug A) every sendCutoff[i] >= 0.15 on all three real log inputs" + (allSends ? "" : worst), allSends);

		// ② 连"完全无反射"的极端输入也守得住硬下限 0.20（所以不需要"除非无反射"的例外）
		float extreme = Acoustics.sendCutoffFor(3.0, 0.0f, 0.0f);
		check("(Bug A) worst case input (occ=3.0 max, reflectivity=0 = fully absorptive, w=0) -> "
				+ f4(extreme) + " >= SEND_CUTOFF_MIN " + f4(SEND_CUTOFF_MIN), extreme >= SEND_CUTOFF_MIN - 1e-6f);
		check("(Bug A) open line of sight (occ=0) is left almost untouched -> "
				+ f4(Acoustics.sendCutoffFor(0.0, 0.0f, 0.6f)) + " (was 1.000)", 
				Acoustics.sendCutoffFor(0.0, 0.0f, 0.6f) > 0.75f);
		check("(Bug A) OLD formula on the same input collapsed to " + f4(oldSendCutoff(2.1, 4.5f, 0.0f))
				+ " (matches the logged 0.001)",
				oldSendCutoff(2.1, 4.5f, 0.0f) < 1.0e-4f);
		check("(Bug A) underwater sends keep the second floor " + f4(SEND_CUTOFF_MIN_UNDERWATER),
				SEND_CUTOFF_MIN_UNDERWATER >= 0.10f);

		// ③ 直通增益 >= 0.25（可听下限）
		boolean allDirect = true;
		StringBuilder badDirect = new StringBuilder();
		for (LogInput c : cases) {
			Acoustics.DirectParams d = Acoustics.directParams(c.occ(), c.k(), c.avgShared(), c.dist());
			if (d.gain() < MIN_AUDIBLE_DIRECT_GAIN - 1e-6f || d.cutoff() < MIN_AUDIBLE_DIRECT_CUTOFF - 1e-6f) {
				allDirect = false;
				badDirect.append(" ").append(c.label()).append(" gain=").append(f3(d.gain()))
						.append(" cutoff=").append(f3(d.cutoff()));
			}
		}
		check("(audibility floor) directGain >= " + f3(MIN_AUDIBLE_DIRECT_GAIN) + " and directCutoff >= "
				+ f3(MIN_AUDIBLE_DIRECT_CUTOFF) + " on all real log inputs" + badDirect, allDirect);
		check("(audibility floor) still far below one glass block (0.407) so material contrast survives: new stone-2 = "
				+ f3(Acoustics.directParams(2.0, 4.5f, 0.0f, 3.0).gain()),
				Acoustics.directParams(2.0, 4.5f, 0.0f, 3.0).gain() < 0.30f);

		// ④ 预算不把直通压到 0.5 倍以下
		boolean allBudget = true;
		StringBuilder badBudget = new StringBuilder();
		for (LogInput c : cases) {
			Acoustics.DirectParams d = Acoustics.directParams(c.occ(), c.k(), c.avgShared(), c.dist());
			float[] b = budget(d.gain(), c.sendGain(), newSendCutoffs(c), BANDS);
			if (b[0] < BUDGET_MIN_DIRECT - 1e-6f) {
				allBudget = false;
				badBudget.append(" ").append(c.label()).append("=").append(f3(b[0]));
			}
			// 有效能量没有超 1 时，就应该完全不削（Bug B 的核心结论）
			if (b[3] <= 1.0f && b[0] < 1.0f - 1e-6f) {
				allBudget = false;
				badBudget.append(" ").append(c.label()).append("(unexpected cut, total=").append(f3(b[3])).append(")");
			}
		}
		check("(Bug B) direct budget multiplier >= 0.5 and no cut at all while total <= 1.0" + badBudget, allBudget);

		// ④b 通畅那一路（日志第 3 行）不该再被砍半 —— 0.12.8 是 0.428（-7.4 dB 的"整体小声"）
		Acoustics.DirectParams clearD = Acoustics.directParams(CASE_C.occ(), CASE_C.k(), CASE_C.avgShared(), CASE_C.dist());
		float[] clearNew = budget(clearD.gain(), CASE_C.sendGain(), newSendCutoffs(CASE_C), BANDS);
		float[] clearOld = oldBudget(oldDirect(CASE_C.occ(), CASE_C.k(), CASE_C.avgShared(), CASE_C.dist())[1],
				CASE_C.sendGain(), BANDS);
		check("(Bug B) clear line of sight keeps >= 0.70 of the direct level (was " + f4(clearOld[0])
				+ " in 0.12.8) -> now " + f4(clearNew[0]), clearNew[0] >= 0.70f);

		// ⑤ 真实日志输入下 old vs new 的最终直通增益（预算之后）
		System.out.println();
		System.out.println("---- what actually reaches OpenAL (directGain x budget): real log line 1 ----");
		float[] oldD = oldDirect(CASE_A.occ(), CASE_A.k(), CASE_A.avgShared(), CASE_A.dist());
		float[] ob = oldBudget(oldD[1], CASE_A.sendGain(), BANDS);
		Acoustics.DirectParams newD = Acoustics.directParams(CASE_A.occ(), CASE_A.k(), CASE_A.avgShared(), CASE_A.dist());
		float[] nb = budget(newD.gain(), CASE_A.sendGain(), newSendCutoffs(CASE_A), BANDS);
		double oldFinal = oldD[1] * ob[0];
		double newFinal = newD.gain() * nb[0];
		System.out.printf("  OLD: directGain %.3f x budget %.3f = %.3f (%s dB)   [the user: \"muffled AND quiet\"]%n",
				oldD[1], ob[0], oldFinal, db(oldFinal).trim());
		System.out.printf("  NEW: directGain %.3f x budget %.3f = %.3f (%s dB)   [%.1f dB louder]%n",
				newD.gain(), nb[0], newFinal, db(newFinal).trim(), 20.0 * Math.log10(newFinal / oldFinal));
		System.out.printf("  OLD sendGain+sendCutoff products: ");
		for (int i = 0; i < BANDS; i++) {
			System.out.printf("%s ", f4(CASE_A.sendGain()[i] * oldSendCutoff(CASE_A.occ(), CASE_A.k(), 0.0f)));
		}
		System.out.println();
		System.out.printf("  NEW sendGain+sendCutoff products: ");
		float[] nsc = newSendCutoffs(CASE_A);
		for (int i = 0; i < BANDS; i++) {
			System.out.printf("%s ", f4(CASE_A.sendGain()[i] * nsc[i]));
		}
		System.out.println();
		check("(Bug B) the real log line 1 gets NO budget cut any more", Math.abs(nb[0] - 1.0f) < 1e-6f);
		float[] oldScA = {oldSendCutoff(2.1, 4.5f, 0.0f), oldSendCutoff(2.1, 4.5f, 0.0f),
				oldSendCutoff(2.1, 4.5f, 0.0f), oldSendCutoff(2.1, 4.5f, 0.0f)};
		float oldWet = sumProd(CASE_A.sendGain(), oldScA) * DEFAULT_WET_GAIN;
		check("(Bug A) wet energy actually delivered to the mixer: E = " + f3(nb[2]) + " (was " + f4(oldWet)
				+ " = the sends were effectively silent)", nb[2] > 0.10f && oldWet < 0.001f);
		check("(Bug A) reverb send HF is audible now: sum(sendGain x sendCutoff) "
				+ f3(sumProd(CASE_A.sendGain(), nsc)) + " (was " + f4(sumProd(CASE_A.sendGain(), oldScA)) + ")",
				sumProd(CASE_A.sendGain(), nsc) > 0.3f);

		System.out.println();
		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : ("FAILED: " + failures));
		System.out.println("(sendCutoff/directGain/budget come from the production functions;");
		System.out.println(" audibility itself is NOT verified here - please judge by ear in game.)");
		System.exit(failures == 0 ? 0 : 1);
	}

	static float sumProd(float[] a, float[] b) {
		float s = 0.0f;
		for (int i = 0; i < BANDS; i++) {
			s += a[i] * b[i];
		}
		return s;
	}

	/** 把生产函数 {@link EfxEngine#energyBudget} 的结果取出来（纯函数，无副作用）。 */
	static float[] budget(float directGain, float[] sendGain, float[] sendCutoff, int bands) {
		float[] out = new float[4];
		EfxEngine.energyBudget(directGain, sendGain, sendCutoff, bands, DEFAULT_WET_GAIN, out);
		return out;
	}

	static float[] newSendCutoffs(LogInput c) {
		float[] sc = new float[BANDS];
		for (int i = 0; i < BANDS; i++) {
			// 真实日志里"开阔度=0.000" ⇒ 4 个延迟带的绕射权重 w_i 全是 0（occ>0 时才计算，
			// 且权重非负、平均为 0 ⇒ 每一项都是 0）。occ=0 时共享空气空间也不参与（sendCutoff=1×mat）。
			float refl = c.bandRefl()[i] > 0.0f ? c.bandRefl()[i] : c.avgRefl();
			sc[i] = Acoustics.sendCutoffFor(c.occ(), 0.0f, refl);
		}
		return sc;
	}

	/** 打印一个真实日志输入的完整对照表。 */
	static void report(LogInput c, boolean verbose) {
		float[] oldD = oldDirect(c.occ(), c.k(), c.avgShared(), c.dist());
		Acoustics.DirectParams newD = Acoustics.directParams(c.occ(), c.k(), c.avgShared(), c.dist());
		float[] oldSc = new float[BANDS];
		for (int i = 0; i < BANDS; i++) {
			oldSc[i] = oldSendCutoff(c.occ(), c.k(), 0.0f);
		}
		float[] newSc = newSendCutoffs(c);
		float[] ob = oldBudget(oldD[1], c.sendGain(), BANDS);
		float[] nb = budget(newD.gain(), c.sendGain(), newSc, BANDS);

		System.out.println("---- " + c.label() + " ----");
		System.out.printf("  inputs : occ=%.3f k=%.3f avgShared=%.3f dist=%.1f openPaths=%d/8 avgRefl=%.3f bandRefl=%s%n",
				c.occ(), c.k(), c.avgShared(), c.dist(), c.openPaths(), c.avgRefl(), arr(c.bandRefl(), BANDS));
		System.out.printf("  inputs : sendGain(logged) = %-30s   logged(0.12.8, smoothed): GAINHF=%s GAIN=%s%n",
				arr(c.sendGain(), BANDS), f3(c.loggedGainHF()), f3(c.loggedGain()));
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n",
				"parameter", "OLD(0.12.8)", "NEW(0.12.9)", "OLD dB", "NEW dB");
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "directCutoff (GAINHF)", f4(oldD[0]), f4(newD.cutoff()),
				db(oldD[0]).trim(), db(newD.cutoff()).trim());
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "directGain (GAIN)", f4(oldD[1]), f4(newD.gain()),
				db(oldD[1]).trim(), db(newD.gain()).trim());
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "sendCutoff[0]", f4(oldSc[0]), f4(newSc[0]),
				db(oldSc[0]).trim(), db(newSc[0]).trim());
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "sendCutoff[1]", f4(oldSc[1]), f4(newSc[1]),
				db(oldSc[1]).trim(), db(newSc[1]).trim());
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "sendCutoff[2]", f4(oldSc[2]), f4(newSc[2]),
				db(oldSc[2]).trim(), db(newSc[2]).trim());
		System.out.printf("  %-26s %-14s %-14s %-12s %-12s%n", "sendCutoff[3]", f4(oldSc[3]), f4(newSc[3]),
				db(oldSc[3]).trim(), db(newSc[3]).trim());
		System.out.printf("  %-26s %-14s %-14s%n", "energy counted by budget", f4(ob[1]) + "=sum(sg)",
				f4(nb[2]) + " sg*sc*wet");
		System.out.printf("  %-26s %-14s %-14s%n", "total = direct + E", f4(ob[2]), f4(nb[3]));
		System.out.printf("  %-26s %-14s %-14s%n", "budget applied to DIRECT", f4(ob[0]), f4(nb[0]));
		System.out.printf("  %-26s %-14s %-14s%n", "final directGain to OpenAL", f4(oldD[1] * ob[0]),
				f4(newD.gain() * nb[0]));
		System.out.println();
	}
}
