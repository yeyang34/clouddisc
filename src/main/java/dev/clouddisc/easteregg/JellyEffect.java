package dev.clouddisc.easteregg;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * 【彩蛋 · 果冻】灵感与效果说明来自社区项目 bydalc（邦友的酒 ✓ NeoForge 1.21.1 ✗ 与本项目平台不同 ✓）。
 *
 * <p>本文件是【独立重新实现】✗ 不是移植它的代码 ✓（对方 GPL-3.0 ✓ 我们只借用"效果描述"✓
 * 变换数学是自己写的三行 ✓ 因此不产生 GPL 传染 ✓）。
 *
 * <h2>效果</h2>
 * 当 CloudDisc 正在播放【指定歌曲】时（默认 id = 3438848556 ✓），
 * 以唱片机为中心 radius 格内的所有生物：
 * <ul>
 *   <li>垂直方向 65% ~ 135% 周期性拉伸/压扁 ✓ 水平反向轻微收缩（体积守恒感 ✓）</li>
 *   <li>拉伸时最多向上弹起 0.15 格 ✓ 落脚点不变 ✓</li>
 *   <li>绕自身视觉中心持续旋转（每 1.2 秒一圈 ✓）</li>
 *   <li>每只生物的相位按实体 id 错开 ✓ 不会整齐划一 ✓</li>
 * </ul>
 *
 * <h2>何时消失</h2>
 * 拔碟 / 换碟 / 放完不循环 / 唱片机被破坏 / 距离超过 radius ⇒ <b>立即消失</b> ✓
 * （因为它只依赖"当前是否正在播放那首歌"这一个状态 ✓ 状态一没就自动停 ✓）
 *
 * <h2>安全性</h2>
 * 纯客户端渲染变换 ✓ 不动实体坐标 ✓ 不动碰撞箱 ✓ 不影响存档与战斗判定 ✓ 视角也不受影响 ✓
 */
public final class JellyEffect {
	/** 触发彩蛋的歌曲 id（唱片名写 @3438848556 ✓）。 */
	public static final String TRIGGER_TRACK_ID = "3438848556";

	private static volatile boolean active = false;
	private static volatile double centerX;
	private static volatile double centerY;
	private static volatile double centerZ;
	private static volatile double radius = 10.0;

	private JellyEffect() {
	}

	/** 由播放控制每刻调用：告诉它"现在是否正在放触发曲 + 唱片机在哪"。 */
	public static void update(boolean playingTrigger, double x, double y, double z, double r) {
		active = playingTrigger;
		if (playingTrigger) {
			centerX = x;
			centerY = y;
			centerZ = z;
			radius = r > 0 ? r : 10.0;
		}
	}

	/** 播放停止/换碟/拔碟时叫一下 ✓ 立刻消失 ✓。 */
	public static void deactivate() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}

	/** 实体是否在影响范围内（按碰撞箱最近点算 ✓ 大体型也能被纳入 ✓）。 */
	public static boolean inRange(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (!active) {
			return false;
		}
		double dx = Math.max(0.0, Math.max(centerX - maxX, minX - centerX));
		double dy = Math.max(0.0, Math.max(centerY - maxY, minY - centerY));
		double dz = Math.max(0.0, Math.max(centerZ - maxZ, minZ - centerZ));
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}

	/**
	 * 把这一帧的果冻变换写进 pose ✓
	 *
	 * @param pose     当前实体的姿态栈（调用前应已 translate 到实体脚底 ✓）
	 * @param entityId 实体 id（用来错开相位 ✓）
	 * @param height   实体视觉高度（旋转轴心取 y = height/2 ✓）
	 */
	public static void apply(PoseStack pose, int entityId, float height) {
		if (!active) {
			return;
		}
		double t = (System.nanoTime() / 1_000_000_000.0) + (entityId % 20) * 0.15;
		// 果冻伸缩：0.65 ~ 1.35 ✓ 水平反向（体积感 ✓）
		double sy = 1.0 + 0.35 * Math.sin(t * 3.0);
		double sxz = 1.0 - 0.18 * Math.sin(t * 3.0);
		float bounce = (float) Math.max(0.0, 0.15 * Math.sin(t * 3.0));
		// 旋转：每 1.2 秒一圈 ✓（围绕视觉中心 ✓）
		float angle = (float) ((t / 1.2) % 1.0 * Math.PI * 2.0);
		float pivotY = Math.max(0.1f, height * 0.5f);

		pose.translate(0.0f, bounce, 0.0f);
		pose.translate(0.0f, pivotY, 0.0f);
		pose.mulPose(new Quaternionf().rotateY(angle));
		pose.scale((float) sxz, (float) sy, (float) sxz);
		pose.translate(0.0f, -pivotY, 0.0f);
	}
}