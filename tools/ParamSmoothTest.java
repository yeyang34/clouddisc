/*
 * ParamSmoothTest —— "参数阶跃会不会产生爆音"的纯 Java 自检（不依赖 Minecraft）。
 *
 * 跑法（JDK 17+）：
 *     java tools/ParamSmoothTest.java
 *
 * 它验证 0.12.7 里改掉的两处"台阶"（用户反馈的"打响指 / bip 一下"的候选来源）：
 *   ① 参数阶跃：直通增益从 1.0 一步写到 0.3（旧写法：每 tick 直写目标值）
 *      vs 按时间一阶平滑（tau = 0.15s，k = 1 - exp(-dt/tau)）
 *   ② DSP 兜底链路在"旁路 → 接通"时把一阶低通的内部状态清成 0：
 *      重新接通后的第一个样本 = a*dry ≈ 0.05*dry，而旁路时输出是 dry 本身
 *      → 输出一步跳掉约 95% 的幅度（就是那声 "bip"）
 *      vs 链路全程接通（状态不清）→ 没有台阶
 *
 * 度量（爆音检测器）：二阶差分 |y[n] - 2y[n-1] + y[n-2]|。
 *   - 对稳定正弦它正比于 ω²·幅度（本测试的 4kHz 分量约 0.054），是"自然底噪"；
 *   - 包络上出现台阶时它会跳到"台阶幅度"（就是人耳听到的 click/pop）。
 * 因为"台阶出现在正弦的哪一相位"差别很大（过零点几乎听不出来），
 * 所以这里**扫 64 个切换相位取最坏值** —— 报的是最坏情况，不是运气好的那一次。
 *
 * 输出单位：满刻度（1.0 = 0 dBFS 幅度）。
 * 文件刻意保持纯 ASCII（只输出英文），避免中文 Windows 控制台的编码问题。
 */
public final class ParamSmoothTest {
	static final int SR = 48000;
	static final double DUR = 0.6;
	static final int SWITCH0 = (int) (SR * 0.2);
	static final int WINDOW = SR / 20; // 切换后 50ms
	static final int PHASES = 64;
	static final double TAU = 0.15;    // 0.12.7 的参数平滑时间常数（秒）

	/** 测试信号：220Hz 基波 + 4kHz 高频（低通/增益变化都能体现出来）。 */
	static double signal(int n) {
		double t = n / (double) SR;
		return 0.4 * Math.sin(2 * Math.PI * 220 * t) + 0.2 * Math.sin(2 * Math.PI * 4000 * t);
	}

	static double maxClick(double[] y, int from, int to) {
		double m = 0;
		for (int i = Math.max(2, from); i < Math.min(to, y.length); i++) {
			m = Math.max(m, Math.abs(y[i] - 2 * y[i - 1] + y[i - 2]));
		}
		return m;
	}

	static void report(String label, java.util.function.IntFunction<double[]> render, int n) {
		double worstClick = 0;
		double worstRatio = 0;
		double natAt = 0;
		int period = Math.max(1, SR / 220);
		for (int j = 0; j < PHASES; j++) {
			int sw = SWITCH0 + j * period / PHASES;
			double[] y = render.apply(sw);
			// 自然底噪取在"启动 50ms 之后、切换 100ms 之前"和"切换窗口之后"两段（躲开启动瞬态）
			double nat = Math.max(maxClick(y, SR / 20, sw - SR / 10), maxClick(y, sw + WINDOW, n));
			double click = maxClick(y, sw - 2, sw + WINDOW);
			double ratio = click / Math.max(1e-12, nat);
			if (ratio > worstRatio) {
				worstRatio = ratio;
				worstClick = click;
				natAt = nat;
			}
		}
		System.out.printf("    %-22s worst click %6.3f (%5.1f%% FS) | natural %6.3f | ratio %6.1fx%n",
				label, worstClick, worstClick * 100.0, natAt, worstRatio);
	}

	/** ① 增益从 1.0 → 0.3：smooth=false 一步写，true 按 0.15s 平滑。 */
	static double[] renderGain(int n, int sw, boolean smooth) {
		double[] y = new double[n];
		double k = 1.0 - Math.exp(-(1.0 / SR) / TAU);
		double g = 1.0;
		for (int i = 0; i < n; i++) {
			double x = signal(i);
			if (i < sw) {
				g = 1.0;
			} else if (smooth) {
				g += (0.3 - g) * k;
			} else {
				g = 0.3;
			}
			y[i] = x * g;
		}
		return y;
	}

	/**
	 * ② 400Hz 一阶低通在 sw 处"接通"：clearState=true 模拟旧实现（状态清 0、接通前原样输出）；
	 * false 模拟 0.12.7（链路全程接通，状态一直是活的）。
	 */
	static double[] renderFilter(int n, int sw, boolean clearState) {
		double[] y = new double[n];
		double a = 1.0 - Math.exp(-2 * Math.PI * 400.0 / SR);
		double state = 0.0;
		for (int i = 0; i < n; i++) {
			double x = signal(i);
			if (i < sw && clearState) {
				y[i] = x; // 旁路：原样输出
				continue;
			}
			if (i == sw && clearState) {
				state = 0.0; // 旧实现：把滤波器状态清成 0
			}
			state += a * (x - state);
			y[i] = state;
		}
		return y;
	}

	public static void main(String[] args) {
		int n = (int) (SR * DUR);
		System.out.println("sample rate = " + SR + " Hz, duration = " + DUR + "s, switch around 0.200s");
		System.out.println("click detector = |y[n]-2y[n-1]+y[n-2]|, worst case over " + PHASES + " switch phases");
		System.out.println("0.12.7 smoothing time constant tau = " + TAU + "s (k = 1-exp(-dt/tau))");
		System.out.println();

		System.out.println("(1) direct gain 1.0 -> 0.3");
		report("instant write (old)", sw -> renderGain(n, sw, false), n);
		report("time smoothed (new)", sw -> renderGain(n, sw, true), n);
		System.out.println();

		System.out.println("(2) DSP fallback chain: bypass -> engaged (400Hz one-pole lowpass)");
		report("state cleared (old)", sw -> renderFilter(n, sw, true), n);
		report("state kept (new)", sw -> renderFilter(n, sw, false), n);
		System.out.println();
		System.out.println("read: 'worst click' is the discontinuity the ear can hear; 'natural' is what");
		System.out.println("the steady signal alone produces. Whether the user's ear issue is gone must");
		System.out.println("still be judged by the user - this tool only measures the maths.");
	}
}
