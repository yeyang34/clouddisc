package dev.clouddisc.audio;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * "沿射线逐格累加遮挡" —— 把"世界查询 / 材质取值"与"累加逻辑"解耦的纯逻辑层，
 * <b>可以离线单测</b>（不需要 Minecraft 运行时，见 {@code tools/OcclusionWalkTest.java}）。
 *
 * <p>0.12.7 的教训：那时只有"给定遮挡值 → 下发参数"的公式复算，<b>没有测"遮挡值是怎么来的"</b>，
 * 结果累加路径一旦坏掉就整条失效（现场表现为"遮挡永远是 0、效果全无"）。
 * 现在累加逻辑搬到这里，由自检直接喂"假世界"的格子判定函数来验证。
 */
public final class OcclusionWalk {

	/**
	 * 单格判定：这一格对这条射线贡献多少遮挡。
	 *
	 * @return {@code > 0} = 挡住（累加这个值）；{@code 0} = 这一格没有实体（空气/空碰撞形状）；
	 *         {@code < 0} = 有实体但射线没被它打到（例如从楼梯的缝里过）——不累加，但计入"实心格"统计
	 */
	public interface Cell {
		double occlusionAt(BlockPos pos, Vec3d from, Vec3d to);
	}

	/** 一次累加的结果（含诊断计数：走格 / 实心格 / 判定为挡）。 */
	public static final class Result {
		public double occlusion;
		/** 射线一共走过多少格（DDA 访问数）。 */
		public int walkedCells;
		/** 其中有碰撞体积的格数（判定函数返回非 0）。 */
		public int solidCells;
		/** 其中真的被判成"挡"的格数（返回 > 0）。 */
		public int hitCells;

		@Override
		public String toString() {
			return "走格=" + walkedCells + " 实心=" + solidCells + " 判定挡=" + hitCells
					+ " 遮挡=" + String.format(java.util.Locale.ROOT, "%.3f", occlusion);
		}
	}

	private OcclusionWalk() {
	}

	/**
	 * 沿 from → to 逐格调用 {@code cell}，累加遮挡值。
	 *
	 * @param maxSteps DDA 最多访问多少格（性能上限）
	 * @param maxOcc   累加上限（到了就停，避免超厚的地基把增益压到听不见）
	 */
	public static Result accumulate(Vec3d from, Vec3d to, int maxSteps, double maxOcc, Cell cell) {
		Result out = new Result();
		if (from == null || to == null || cell == null) {
			return out;
		}
		final double[] acc = {0.0};
		RayWalk.walk(from.x, from.y, from.z, to.x, to.y, to.z, maxSteps,
				(x, y, z, t, nx, ny, nz) -> {
					BlockPos p = new BlockPos(x, y, z);
					out.walkedCells++;
					double v;
					try {
						v = cell.occlusionAt(p, from, to);
					} catch (Throwable e) {
						return true; // 单格判定出错：跳过这一格，绝不让整条声学失效
					}
					if (v < 0.0) {
						out.solidCells++;
						return true; // 有实体但没被打到
					}
					if (v <= 0.0) {
						return true; // 空气 / 空碰撞形状
					}
					out.solidCells++;
					out.hitCells++;
					acc[0] += v;
					return acc[0] < maxOcc;
				});
		out.occlusion = Math.min(acc[0], maxOcc);
		return out;
	}
}
