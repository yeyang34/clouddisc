import dev.clouddisc.audio.OcclusionWalk;
import dev.clouddisc.audio.RayShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>"沿射线逐格累加遮挡"的离线自检</b> —— 0.12.7 就是因为只做了"给定遮挡值 → 下发参数"的公式复算、
 * <b>没有测"遮挡值是怎么来的"</b>，才让"遮挡恒为 0、效果全无"漏到了用户手里。这个文件补上那一环。
 *
 * <h2>它测什么</h2>
 * 用<b>真实的</b> {@link VoxelShape}/{@link Box}/{@link Vec3d}（Minecraft 自带的几何代码），
 * 配一个"假世界"（{@code BlockPos → 碰撞形状 + 材质遮挡值}），走<b>生产同一条</b>累加路径
 * （{@link OcclusionWalk#accumulate} + {@link RayShape#hits}），断言：
 * <ol>
 *   <li>1 格石头墙 → 遮挡累积 ≈ 1.00</li>
 *   <li>2 格石头墙 → ≈ 2.00</li>
 *   <li>关着的门（薄门板，单盒不满格）→ ≈ 0.44（走精确求交）</li>
 *   <li>关着的门（满格单盒，等价"一格厚墙"）→ ≈ 0.44（走满格快捷判定）</li>
 *   <li>开着的门（碰撞形状为空）→ 0.00</li>
 *   <li>擦边而过（射线从半砖上方过 / 旁边空气格 / 与薄板平行）→ 0.00</li>
 *   <li>超厚墙 → 撞到累加上限（MAX_OCC = 3.0）</li>
 *   <li>坐标约定自检：同一组数按<b>世界坐标</b>必须命中、按"局部坐标"必须不命中
 *       （这就是 0.12.7 排障时被怀疑错的地方，这里钉死它）</li>
 * </ol>
 *
 * <h2>怎么跑（需要 Minecraft 的 classpath，但<b>不需要启动游戏</b>）</h2>
 * <pre>
 *   # 1) 让 Gradle 把 Minecraft 完整运行期 classpath 打出来（临时 init 脚本，不改 build.gradle）
 *   $env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-gamma-snapshot"
 *   .\gradlew -I tools\printcp.init.gradle :printRuntimeCp -q   # 取 CPSTART / CPEND 之间的那一行
 *   $cp = (那一行)
 *   # 2) 编译 + 运行（把 build\classes\java\main 也放进 classpath）
 *   &amp; "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp $cp -d tools\out tools\OcclusionWalkTest.java
 *   &amp; "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -cp "$cp;tools\out" OcclusionWalkTest
 * </pre>
 * 全通过时最后一行打印 {@code 全部通过}，退出码 0。
 *
 * <p><b>注意</b>：这里不启动 Minecraft（不初始化方块注册表），所以"材质值"是自检里写死的常数
 * （与 {@code BlockAcoustics} 的表一致：石头 1.00、关着的门 0.44）；<b>几何与累加逻辑是真代码</b>。
 */
public class OcclusionWalkTest {
	static int failures;

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) {
			failures++;
		}
	}

	/** 假世界的一格：碰撞形状 + 材质遮挡值（负值表示"有实体但材质值为 0"）。 */
	record Cell(VoxelShape shape, double occ) {
	}

	static final Map<BlockPos, Cell> WORLD = new HashMap<>();

	/** 生产同一条单格判定：没有这一格 → 0（空气）；形状为空 → 0；射线没打到 → -1；否则材质值。 */
	static double cellOcclusion(BlockPos p, Vec3d from, Vec3d to) {
		Cell c = WORLD.get(p);
		if (c == null || c.shape.isEmpty()) {
			return 0.0;
		}
		return RayShape.hits(c.shape, p, from, to) ? c.occ : -1.0;
	}

	static OcclusionWalk.Result run(Vec3d from, Vec3d to) {
		return OcclusionWalk.accumulate(from, to, 96, 3.0, OcclusionWalkTest::cellOcclusion);
	}

	// ---- 几种常见形状（方块局部坐标）----
	static final VoxelShape FULL = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
	static final VoxelShape DOOR_LEAF = VoxelShapes.cuboid(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625); // 薄门板（z 方向）
	static final VoxelShape SLAB_BOTTOM = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
	static final VoxelShape THIN_HORIZONTAL = VoxelShapes.cuboid(0.0, 0.4375, 0.0, 1.0, 0.5625, 1.0);
	static final double STONE = 1.00;
	static final double DOOR = 0.44;

	public static void main(String[] args) {
		// 现场几何：唱片机中心 (0.5,0.6,0.5) → 玩家耳朵 (0.5,1.62,3.0)，距离约 2.5 格（与用户日志一致）
		Vec3d from = new Vec3d(0.5, 0.6, 0.5);
		Vec3d to = new Vec3d(0.5, 1.62, 3.0);

		System.out.println("射线 " + from + " → " + to + "（距离≈2.5格，穿过 z=1、z=2 两排格子）");
		System.out.println();

		// ① 1 格石头墙：z=1 那一排里射线真正穿过的格子
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(FULL, STONE));
		OcclusionWalk.Result r1 = run(from, to);
		System.out.println("① 1 格石头墙: " + r1);
		check("1 格石头墙 → 遮挡累积 ≈ 1.00", Math.abs(r1.occlusion - 1.0) < 1e-6);
		check("1 格石头墙 → 走格 > 0 且判定挡 = 1", r1.walkedCells > 0 && r1.hitCells == 1);

		// ② 2 格石头墙（z=1 与 z=2）
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(FULL, STONE));
		WORLD.put(new BlockPos(0, 1, 2), new Cell(FULL, STONE));
		OcclusionWalk.Result r2 = run(from, to);
		System.out.println("② 2 格石头墙: " + r2);
		check("2 格石头墙 → 遮挡累积 ≈ 2.00", Math.abs(r2.occlusion - 2.0) < 1e-6);

		// ③ 关着的门（薄门板，单盒、不满格 → 必须走精确求交）
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(DOOR_LEAF, DOOR));
		OcclusionWalk.Result r3 = run(from, to);
		System.out.println("③ 关着的门（薄门板 3/16 厚）: " + r3);
		check("薄门板 → 遮挡累积 ≈ 0.44（射线垂直穿过门板）", Math.abs(r3.occlusion - DOOR) < 1e-6);

		// ④ 关着的门（满格单盒，等价"一格厚墙" → 走满格快捷判定，不依赖求交）
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(FULL, DOOR));
		OcclusionWalk.Result r4 = run(from, to);
		System.out.println("④ 关着的门（满格单盒）: " + r4);
		check("满格单盒 → 遮挡累积 ≈ 0.44（满格快捷判定生效）", Math.abs(r4.occlusion - DOOR) < 1e-6);

		// ⑤ 开着的门（碰撞形状为空）
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(VoxelShapes.empty(), DOOR));
		OcclusionWalk.Result r5 = run(from, to);
		System.out.println("⑤ 开着的门（空形状）: " + r5);
		check("开着的门 → 遮挡累积 = 0.00", r5.occlusion == 0.0);

		// ⑥ 擦边而过之一：半砖（下半格），射线从上半格过 → 不算挡
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(SLAB_BOTTOM, STONE));
		OcclusionWalk.Result r6 = run(from, to);
		System.out.println("⑥a 半砖（射线从上方过）: " + r6);
		check("半砖上方擦过 → 遮挡累积 = 0.00（但计入实心格）", r6.occlusion == 0.0 && r6.solidCells > 0);

		// ⑥b 擦边而过之二：墙在射线旁边的格子里（DDA 根本不会访问它）
		WORLD.clear();
		WORLD.put(new BlockPos(1, 0, 1), new Cell(FULL, STONE));
		OcclusionWalk.Result r6b = run(from, to);
		System.out.println("⑥b 墙在旁边的格子 (1,0,1): " + r6b);
		check("射线旁边的墙 → 遮挡累积 = 0.00", r6b.occlusion == 0.0);

		// ⑥c 擦边而过之三：与薄板平行（薄板是水平方向，射线在它上方擦过）
		WORLD.clear();
		WORLD.put(new BlockPos(0, 0, 1), new Cell(THIN_HORIZONTAL, DOOR));
		OcclusionWalk.Result r6c = run(from, to);
		System.out.println("⑥c 水平薄板（射线在其上方擦过）: " + r6c);
		check("与薄板平行擦过 → 遮挡累积 = 0.00", r6c.occlusion == 0.0);

		// ⑦ 超厚墙 → 撞上限
		WORLD.clear();
		for (int z = 1; z <= 5; z++) {
			WORLD.put(new BlockPos(0, 0, z), new Cell(FULL, STONE));
			WORLD.put(new BlockPos(0, 1, z), new Cell(FULL, STONE));
		}
		OcclusionWalk.Result r7 = run(from, to);
		System.out.println("⑦ 5 格厚石头墙: " + r7);
		check("超厚墙 → 遮挡累积被压在上限 3.00", Math.abs(r7.occlusion - 3.0) < 1e-6);

		// ⑧ 坐标约定自检（0.12.7 排障时被怀疑错的地方）：世界坐标必须命中、局部坐标必须不命中
		BlockPos pos = new BlockPos(0, 1, 0);
		Vec3d wFrom = new Vec3d(0.5, 1.5, -2.0);
		Vec3d wTo = new Vec3d(0.5, 1.5, 3.0);
		Vec3d lFrom = wFrom.subtract(pos.getX(), pos.getY(), pos.getZ());
		Vec3d lTo = wTo.subtract(pos.getX(), pos.getY(), pos.getZ());
		boolean worldHit = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 1.0, 1.0).raycast(wFrom, wTo, pos) != null;
		boolean localHit = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 1.0, 1.0).raycast(lFrom, lTo, pos) != null;
		System.out.println("⑧ VoxelShape.raycast: 世界坐标=" + worldHit + " 局部坐标=" + localHit);
		check("引擎求交要世界坐标（世界=命中、局部=null）", worldHit && !localHit);
		check("RayShape.hits 用世界坐标也命中", RayShape.hits(VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), pos, wFrom, wTo));

		// ⑨ 满格判定辅助函数
		check("coversFullCell(满格) = true", RayShape.coversFullCell(new Box(0, 0, 0, 1, 1, 1)));
		check("coversFullCell(半砖) = false", !RayShape.coversFullCell(new Box(0, 0, 0, 1, 0.5, 1)));

		// 对照：RayShape 与引擎求交在所有形状上必须一致（只有"不满格单盒"这一种情况会走不同分支）
		List<String> diffs = new ArrayList<>();
		VoxelShape[] shapes = {FULL, DOOR_LEAF, SLAB_BOTTOM, THIN_HORIZONTAL, VoxelShapes.empty()};
		BlockPos wall = new BlockPos(0, 0, 1);
		for (VoxelShape s : shapes) {
			boolean a = RayShape.hits(s, wall, from, to);
			boolean b;
			try {
				b = !s.isEmpty() && s.raycast(from, to, wall) != null;
			} catch (Throwable t) {
				b = false;
			}
			if (a != b) {
				diffs.add(s + " RayShape=" + a + " engine=" + b);
			}
		}
		System.out.println("⑨ RayShape 与引擎求交的不一致项: " + (diffs.isEmpty() ? "（无）" : diffs));
		check("RayShape.hits 与 VoxelShape.raycast 结论一致", diffs.isEmpty());

		System.out.println();
		System.out.println(failures == 0 ? "全部通过 ✓" : "失败 " + failures + " 项 ✗");
		System.out.println("（几何与累加逻辑是真代码；材质值是自检里的常数，见文件头注释）");
		System.exit(failures == 0 ? 0 : 1);
	}
}
