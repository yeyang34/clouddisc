package dev.clouddisc.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 自绘开关（滑动圆点），替掉原版那个写着「开 / 关」的按钮。
 *
 * <h2>为什么值得自绘</h2>
 * 原版按钮是一个灰盒子，里面写两个汉字 —— 在一堆"下拉值 / 输入框"里显得很突兀。
 * 换成轨道 + 圆点的开关后：
 * <ul>
 *   <li>状态<b>一眼可读</b>（位置而不是文字）</li>
 *   <li>和滑条/输入框同高同宽，整列右边缘对齐，观感统一</li>
 *   <li>圆点有<b>滑动过渡</b>（每帧向目标插值），不跳变</li>
 * </ul>
 *
 * <p>配色全部走 {@link Palette}；悬停只提亮 12%（Sodium 的"只改透明度"原则）。
 */
@Environment(EnvType.CLIENT)
public final class PillToggle extends AbstractWidget {

	private final BooleanSupplier get;
	private final Consumer<Boolean> set;
	/** 圆点位置 0..1（-1 = 还没初始化，首帧直接落到目标位置，避免开场滑动）。 */
	private float knob = -1f;

	public PillToggle(int x, int y, int width, int height, BooleanSupplier get, Consumer<Boolean> set) {
		super(x, y, width, height, Component.empty());
		this.get = get;
		this.set = set;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
		boolean on = get.getAsBoolean();
		float target = on ? 1f : 0f;
		knob = (knob < 0f) ? target : knob + (target - knob) * 0.35f;

		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		// 轨道：圆角 = 高的一半 ⇒ 胶囊形。**不画描边**（方角+描边就是廉价感的来源）
		int track = on ? Palette.ACCENT : 0xFF3A3A44;
		if (isHovered()) {
			track = Palette.mix(track, 0xFFFFFFFF, 0.10f);
		}
		Palette.roundRect(ctx, x, y, w, h, h / 2, track);

		// 圆点：同样是圆的（半径 = 尺寸/2），纯白 + 一圈极淡的内阴影（不是外描边）
		int size = Math.max(8, h - 6);
		int travel = Math.max(1, w - size - 6);
		int kx = x + 3 + (int) (travel * knob);
		int ky = y + (h - size) / 2;
		Palette.roundRect(ctx, kx, ky, size, size, size / 2, 0xF2FFFFFF);
		Palette.roundRect(ctx, kx, ky + size - 2, size, 2, 1, 0x18000000);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		boolean next = !get.getAsBoolean();
		set.accept(next);
		PillToggle.playButtonClickSound(Minecraft.getInstance().getSoundManager());
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		try {
			defaultButtonNarrationText(output);
		} catch (Throwable ignored) {
			// 无障碍朗读失败不影响功能
		}
	}
}
