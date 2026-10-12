package dev.clouddisc.audio;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * 体素射线遍历（Amanatides &amp; Woo 的 DDA）。
 *
 * <p><b>为什么不用 {@code World#raycast}</b>：
 * <ol>
 *   <li>遮挡要的是"<b>沿连线一共穿过哪些方块</b>"（逐块累加材质遮挡值），
 *       而 {@code raycast} 只给"第一个命中"，要拿全部就得在命中点上反复续射 ——
 *       而命中点正好落在方块表面，续射会把同一个方块<b>再算一遍</b>（1 格厚的墙会算成 2 格）。</li>
 *   <li>DDA 直接给出"进入每个格子时穿的是哪一个面"，于是<b>命中点和面法线都是精确值</b>，
 *       不需要靠 0.001 这种拍脑袋的 epsilon（反射射线要用法线）。</li>
 * </ol>
 *
 * <p>不分配任何中间对象，只有 {@link Hit} 一个结果对象。
 */
public final class RayWalk {
	/** 遍历回调。返回 false 立即结束整条射线。单位法线是"进入该格子时穿过的那个面"。 */
	public interface Visitor {
		boolean visit(int x, int y, int z, double t, int nx, int ny, int nz);
	}

	/** 一次命中：方块坐标 + 精确命中点 + 面法线 + 归一化距离（t ∈ [0,1]）。 */
	public static final class Hit {
		public final BlockPos pos;
		public final double x;
		public final double y;
		public final double z;
		public final int nx;
		public final int ny;
		public final int nz;
		public final double t;

		Hit(BlockPos pos, double x, double y, double z, int nx, int ny, int nz, double t) {
			this.pos = pos;
			this.x = x;
			this.y = y;
			this.z = z;
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.t = t;
		}

		public Vec3d point() {
			return new Vec3d(x, y, z);
		}

		public Vec3d normal() {
			return new Vec3d(nx, ny, nz);
		}
	}

	private RayWalk() {
	}

	/**
	 * 沿 from → to 依次访问经过的方块（<b>不含起点所在的那一格</b>，因为"从某物内部出发"不该算它挡自己）。
	 *
	 * @param maxSteps 最多访问多少格（性能上限）
	 */
	public static void walk(double x0, double y0, double z0, double x1, double y1, double z1,
			int maxSteps, Visitor visitor) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double dz = z1 - z0;

		int x = floor(x0);
		int y = floor(y0);
		int z = floor(z0);
		final int ex = floor(x1);
		final int ey = floor(y1);
		final int ez = floor(z1);

		final int sx = dx > 0.0 ? 1 : (dx < 0.0 ? -1 : 0);
		final int sy = dy > 0.0 ? 1 : (dy < 0.0 ? -1 : 0);
		final int sz = dz > 0.0 ? 1 : (dz < 0.0 ? -1 : 0);

		double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
		double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
		double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

		double tx = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? (x + 1 - x0) : (x - x0)) / dx);
		double ty = sy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? (y + 1 - y0) : (y - y0)) / dy);
		double tz = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? (z + 1 - z0) : (z - z0)) / dz);

		int steps = 0;
		while (true) {
			if (x == ex && y == ey && z == ez) {
				return; // 到达终点所在格子
			}
			if (steps++ >= maxSteps) {
				return;
			}
			final double t;
			final int nx;
			final int ny;
			final int nz;
			if (tx <= ty && tx <= tz) {
				t = tx;
				tx += tdx;
				x += sx;
				nx = -sx;
				ny = 0;
				nz = 0;
			} else if (ty <= tz) {
				t = ty;
				ty += tdy;
				y += sy;
				nx = 0;
				ny = -sy;
				nz = 0;
			} else {
				t = tz;
				tz += tdz;
				z += sz;
				nx = 0;
				ny = 0;
				nz = -sz;
			}
			if (t > 1.0 + 1.0e-9) {
				return;
			}
			if (!visitor.visit(x, y, z, t, nx, ny, nz)) {
				return;
			}
		}
	}

	/**
	 * 找 from → to 上第一个"有碰撞体积"的方块（起点所在格子不算）。
	 *
	 * @return 没打中返回 {@code null}（说明这条线一路通畅）
	 */
	public static Hit cast(BlockView world, Vec3d from, Vec3d to, int maxSteps) {
		final double dx = to.x - from.x;
		final double dy = to.y - from.y;
		final double dz = to.z - from.z;
		final Hit[] out = new Hit[1];
		walk(from.x, from.y, from.z, to.x, to.y, to.z, maxSteps, (x, y, z, t, nx, ny, nz) -> {
			BlockPos p = new BlockPos(x, y, z);
			BlockState st;
			try {
				st = world.getBlockState(p);
			} catch (Throwable e) {
				return false;
			}
			if (st.isAir()) {
				return true;
			}
			try {
				VoxelShape shape = st.getCollisionShape(world, p);
				if (shape.isEmpty()) {
					return true;
				}
			} catch (Throwable e) {
				return true;
			}
			out[0] = new Hit(p, from.x + dx * t, from.y + dy * t, from.z + dz * t, nx, ny, nz, t);
			return false;
		});
		return out[0];
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < (double) i ? i - 1 : i;
	}
}
