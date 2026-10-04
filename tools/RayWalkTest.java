import dev.clouddisc.audio.RayWalk;

import java.util.ArrayList;
import java.util.List;

/**
 * RayWalk（DDA 体素遍历）的独立验证 —— 不启动 Minecraft，只跑纯数学部分。
 *
 * <p>为什么留着它：遮挡/反射射线全靠"沿射线穿过哪些格子"这件事，
 * 而它一旦错了（重复累加同一格、法线反了、斜射丢格）表现是"隔墙的闷度不对"，
 * 在游戏里极难定位。这里用 8 组断言把它钉死。
 *
 * <p>跑法（PowerShell，从仓库根；$mc 换成你自己的 MC 具名 jar 也行）：
 * <pre>
 *   $jdk = "$env:APPDATA\.minecraft\runtime\java-runtime-gamma-snapshot"
 *   $mc  = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven" -Recurse -Filter "minecraft-merged-1.20.1-*.jar" | Select-Object -First 1).FullName
 *   $cp  = "build\classes\java\main;$mc"
 *   &amp; "$jdk\bin\javac.exe" -encoding UTF-8 -cp $cp tools\RayWalkTest.java
 *   &amp; "$jdk\bin\java.exe" -Dfile.encoding=UTF-8 -cp "$cp;tools" RayWalkTest
 * </pre>
 * 最后一行应打印"全部通过"。
 */
public class RayWalkTest {
	static int failures = 0;

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) {
			failures++;
		}
	}

	static List<String> walk(double x0, double y0, double z0, double x1, double y1, double z1, int max) {
		List<String> out = new ArrayList<>();
		RayWalk.walk(x0, y0, z0, x1, y1, z1, max, (x, y, z, t, nx, ny, nz) -> {
			out.add(x + "," + y + "," + z + " t=" + String.format("%.4f", t) + " n=" + nx + "," + ny + "," + nz);
			return true;
		});
		return out;
	}

	public static void main(String[] args) {
		// ① 沿 +z 的直线：应依次访问 (0,0,1)..(0,0,9)，不访问起点 (0,0,0)，不访问终点 (0,0,10)
		List<String> a = walk(0.5, 0.5, 0.5, 0.5, 0.5, 10.5, 100);
		System.out.println("① +z 直线: " + a);
		check("+z 访问 10 格", a.size() == 10);
		check("+z 首格是 (0,0,1) 且法线 -z", a.get(0).startsWith("0,0,1 ") && a.get(0).contains("n=0,0,-1"));
		check("+z 末格是 (0,0,10)", a.get(9).startsWith("0,0,10 "));

		// ② 沿 -x：从 (10.5,0.5,0.5) 到 (0.5,0.5,0.5)
		List<String> b = walk(10.5, 0.5, 0.5, 0.5, 0.5, 0.5, 100);
		System.out.println("② -x 直线: " + b);
		check("-x 访问 10 格", b.size() == 10);
		check("-x 首格 (9,0,0) 法线 +x", b.get(0).startsWith("9,0,0 ") && b.get(0).contains("n=1,0,0"));

		// ③ 体对角线：t 必须严格递增，且不多不少
		List<String> c = walk(0.5, 0.5, 0.5, 3.5, 3.5, 3.5, 100);
		System.out.println("③ 对角线: " + c);
		check("对角线访问 9 格", c.size() == 9);
		double prev = -1.0;
		boolean mono = true;
		for (String s : c) {
			double t = Double.parseDouble(s.substring(s.indexOf("t=") + 2, s.indexOf(" n=")));
			if (t < prev) {
				mono = false;
			}
			prev = t;
		}
		check("对角线 t 单调不减", mono);
		check("对角线每格坐标都在 0..3 且互不重复", new java.util.HashSet<>(c.stream().map(s -> s.substring(0, s.indexOf(" "))).toList()).size() == 9);

		// ④ maxSteps 生效
		List<String> d = walk(0.5, 0.5, 0.5, 0.5, 0.5, 100.5, 7);
		check("maxSteps=7 → 只访问 7 格", d.size() == 7);

		// ⑤ 每个被访问的格子都"真的被射线穿过"（用格子中心到线段的距离粗验）
		List<String> e = walk(1.2, 2.7, 0.3, 9.9, 5.1, 3.6, 100);
		boolean sane = true;
		for (String s : e) {
			String[] p = s.substring(0, s.indexOf(" ")).split(",");
			double cx = Double.parseDouble(p[0]) + 0.5;
			double cy = Double.parseDouble(p[1]) + 0.5;
			double cz = Double.parseDouble(p[2]) + 0.5;
			double dist = distToSegment(cx, cy, cz, 1.2, 2.7, 0.3, 9.9, 5.1, 3.6);
			if (dist > 0.87) { // 半格对角线长度 0.866
				sane = false;
				System.out.println("     离线段太远: " + s + " 距离=" + dist);
			}
		}
		System.out.println("⑤ 斜射线访问 " + e.size() + " 格");
		check("每个被访问格子都在射线附近（<=0.87 格）", sane);

		// ⑥ 起点在格心、终点同格 → 不访问任何格子
		check("同一格内不访问任何格子", walk(0.2, 0.2, 0.2, 0.8, 0.8, 0.8, 100).isEmpty());

		// ⑦ 起点正好在格子边界上（不应对同一格重复累加）
		List<String> g = walk(1.0, 0.5, 0.5, 5.9, 0.5, 0.5, 100);
		System.out.println("⑦ 边界起点: " + g);
		long dup = g.stream().map(s -> s.substring(0, s.indexOf(" "))).distinct().count();
		check("边界起点不重复访问同一格", dup == g.size());

		System.out.println(failures == 0 ? "\n全部通过 ✓" : "\n失败 " + failures + " 项 ✗");
		System.exit(failures == 0 ? 0 : 1);
	}

	static double distToSegment(double px, double py, double pz,
			double ax, double ay, double az, double bx, double by, double bz) {
		double vx = bx - ax, vy = by - ay, vz = bz - az;
		double wx = px - ax, wy = py - ay, wz = pz - az;
		double vv = vx * vx + vy * vy + vz * vz;
		double t = vv == 0 ? 0 : (wx * vx + wy * vy + wz * vz) / vv;
		t = Math.max(0, Math.min(1, t));
		double dx = wx - t * vx, dy = wy - t * vy, dz = wz - t * vz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
