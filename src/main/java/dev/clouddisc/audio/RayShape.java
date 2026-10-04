package dev.clouddisc.audio;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.List;

/**
 * "这条射线有没有被这一格的碰撞形状挡住" —— <b>纯几何，可离线单测</b>
 * （见 {@code tools/OcclusionWalkTest.java}）。
 *
 * <h2>坐标约定（0.12.7 排障时用探针实测过，别再猜）</h2>
 * <ul>
 *   <li>{@code from}/{@code to} 必须是<b>世界坐标</b>；{@code pos} 是这一格的方块坐标。</li>
 *   <li>{@code VoxelShape.raycast(from, to, pos)} 内部做的是
 *       {@code Box.raycast(getBoundingBoxes(), from, to, pos)}，而后者对每个盒子
 *       {@code box.offset(pos)} 之后再与世界坐标求交 —— 也就是<b>世界坐标</b>是对的。
 *       实测：满格盒子 + 世界坐标 → 命中；同一组数传"局部坐标" → null。
 *   <li>{@link VoxelShape#getBoundingBoxes()} 返回的盒子是<b>方块局部坐标</b>（0..1），
 *       所以自己遍历盒子时必须先 {@code offset(pos)}。</li>
 * </ul>
 *
 * <h2>为什么不用 VoxelShape.raycast 一条路走到底（0.12.7 的教训）</h2>
 * 0.12.7 只调了 {@code VoxelShape.raycast}，结果现场"遮挡恒为 0"，而同一份代码在离线单测里
 * 是通的 —— 这种"依赖引擎内部某条分支"的写法一旦不成立就<b>静默变成"不挡"</b>。
 * 这里改成两条腿走路：
 * <ol>
 *   <li><b>满格体素直接判挡</b>：碰撞形状就是"一个盒子且填满整格"（石头/玻璃/原木/木板/关着的门）
 *       → 任何穿过这一格的射线都被挡，<b>根本不需要求交</b>。这条对"一格厚墙/关着的门"是铁律。</li>
 *   <li>否则（楼梯/栅栏/半砖/活板门/玻璃板这类<b>多盒或不满格</b>的形状）才逐盒精确求交，
 *       让"射线从缝里过"判成不挡。</li>
 * </ol>
 */
public final class RayShape {
	/** 盒子贴合格子边界的容差（VoxelShape 全格盒子就是精确的 0/1，这里只防浮点误差）。 */
	private static final double EPS = 1.0E-7;

	private RayShape() {
	}

	/** 这个盒子的局部坐标是不是"塞满整格"（0,0,0 → 1,1,1）。 */
	public static boolean coversFullCell(Box box) {
		return box != null
				&& box.minX <= EPS && box.minY <= EPS && box.minZ <= EPS
				&& box.maxX >= 1.0 - EPS && box.maxY >= 1.0 - EPS && box.maxZ >= 1.0 - EPS;
	}

	/**
	 * 射线是否被这一格的碰撞形状挡住。
	 *
	 * @param shape 该格的碰撞形状（可以为 {@code null} / 空）
	 * @param pos   该格的方块坐标
	 * @param from  世界坐标起点
	 * @param to    世界坐标终点
	 */
	public static boolean hits(VoxelShape shape, BlockPos pos, Vec3d from, Vec3d to) {
		if (shape == null || shape.isEmpty() || pos == null || from == null || to == null) {
			return false;
		}
		List<Box> boxes = null;
		try {
			boxes = shape.getBoundingBoxes();
		} catch (Throwable ignored) {
			// 拿不到盒子列表就走下面的兜底
		}
		if (boxes != null && boxes.size() == 1 && coversFullCell(boxes.get(0))) {
			// ① 满格：穿过这一格就是被挡（石头/玻璃/关着的门…）—— 不依赖任何求交实现
			return true;
		}
		if (boxes != null && !boxes.isEmpty()) {
			// ② 不满格/多盒：逐盒精确求交（楼梯、栅栏、半砖、活板门、玻璃板…）
			for (int i = 0; i < boxes.size(); i++) {
				try {
					Box world = boxes.get(i).offset(pos.getX(), pos.getY(), pos.getZ());
					if (world.contains(from) || world.raycast(from, to).isPresent()) {
						return true;
					}
				} catch (Throwable ignored) {
					// 单个盒子出错不影响其它盒子
				}
			}
			return false;
		}
		// ③ 兜底：盒子列表不可用时，用引擎自己的求交（世界坐标 + pos，实测语义正确）
		try {
			return shape.raycast(from, to, pos) != null;
		} catch (Throwable t) {
			return true; // 连兜底都失败：宁可闷一点，也不要静默地"什么都不挡"
		}
	}
}
