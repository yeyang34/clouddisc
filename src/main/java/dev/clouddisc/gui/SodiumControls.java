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
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Sodium 风格的两个控件（照抄它的画法，不自己发明）。
 *
 * <h2>为什么是这两个形状</h2>
 * Sodium 的视频设置里只有三种控件，全部是<b>直角、单色、无描边、无圆角、无渐变</b>：
 * <ul>
 *   <li>布尔 → <b>小方勾选框</b>（{@code TICKBOX_CONTROL_WIDTH = 30}，方框本身 20×20）</li>
 *   <li>枚举/数值 → <b>{@code ‹ 值 ›}</b> 箭头（{@code CYCLING_CONTROL_WIDTH = 70}）</li>
 *   <li>连续值 → 滑条</li>
 * </ul>
 * 我们之前用"胶囊开关 / 灰盒子写开-关"，等于自己造了一套和界面其它部分不搭的控件 ——
 * 所以看起来杂。改成这两个之后，整页就是一套东西。
 */
@Environment(EnvType.CLIENT)
public final class SodiumControls {

	private SodiumControls() {
	}

	/** Sodium 的勾选框：20×20 方框，选中时框内画一个对勾。 */
	public static final class Checkbox extends AbstractWidget {
		private final BooleanSupplier get;
		private final Consumer<Boolean> set;
		/** 勾选动画 0..1（对勾慢慢画出来，Sodium 没有动画，但这一个不影响观感且更顺）。 */
		private float anim = -1f;

		public Checkbox(int x, int y, int size, BooleanSupplier get, Consumer<Boolean> set) {
			super(x, y, size, size, Component.empty());
			this.get = get;
			this.set = set;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
			boolean on = get.getAsBoolean();
			anim = (anim < 0f) ? (on ? 1f : 0f) : anim + ((on ? 1f : 0f) - anim) * 0.4f;

			int x = getX();
			int y = getY();
			// 尺寸跟随行高，别超过行（否则会压到上下两行）
			int s = Math.min(getWidth(), getHeight());
			int border = isHovered() ? Palette.ACCENT_LIGHT : Palette.ACCENT;
			// 【参数语义】outline(x, y, 宽, 高, 颜色) —— 不是 (x1, y1, x2, y2)！
			// 之前传 (x, y, x+20, y+20) 等于画"宽=x+20、高=y+20"的巨框，
			// 直接超出屏幕、盖住下面几行（实测就是这个"像图片左上角""边框卡在屏幕外"）
			ctx.outline(x, y, s, s, border);
			if (anim > 0.05f) {
				// 选中：整块填主色 + 中间一个白色小方块。
				// 【为什么不用对勾】手拼的 1px 斜线在小尺寸下会糊成"缺角"，看着像画错了；
				// 实心块 + 白芯的语义同样清楚，而且永远不会糊。
				int inset = Math.max(1, s / 8) + Math.round((1f - anim) * 3f);
				ctx.fill(x + inset, y + inset, x + s - inset, y + s - inset, Palette.ACCENT);
				int core = Math.max(2, s / 4);
				ctx.fill(x + (s - core) / 2, y + (s - core) / 2, x + (s + core) / 2, y + (s + core) / 2, 0xFFFFFFFF);
			}
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			boolean next = !get.getAsBoolean();
			set.accept(next);
			playButtonClickSound(Minecraft.getInstance().getSoundManager());
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			try {
				defaultButtonNarrationText(output);
			} catch (Throwable ignored) {
			}
		}
	}

	/** Sodium 的 `‹ 值 ›`：点左侧箭头减、右侧加，点中间也按"下一个"走。 */
	public static final class Cycle extends AbstractWidget {
		private final String[] values;
		private final IntSupplier getIndex;
		private final IntConsumer setIndex;

		public Cycle(int x, int y, int w, int h, String[] values, IntSupplier getIndex, IntConsumer setIndex) {
			super(x, y, w, h, Component.empty());
			this.values = values;
			this.getIndex = getIndex;
			this.setIndex = setIndex;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
			int x = getX();
			int y = getY();
			int w = getWidth();
			int h = getHeight();
			boolean hover = isHovered();
			// 背景：悬停才亮（Sodium 的 row hover 也是这一套）
			ctx.fill(x, y, x + w, y + h, hover ? Palette.SURFACE_HOVER : Palette.SURFACE);
			// 左右箭头
			int arrow = hover ? Palette.ACCENT_LIGHT : Palette.TEXT_SECONDARY;
			// 用字形画箭头：手拼的 1px 斜线在小尺寸下会糊成一坨"缺角"，字形永远干净
			var f0 = Minecraft.getInstance().font;
			ctx.text(f0, Component.literal("‹"), x + 3, y + (h - 8) / 2, arrow);
			ctx.text(f0, Component.literal("›"), x + w - 9, y + (h - 8) / 2, arrow);
			// 值：居中，左右各留 12px 给箭头
			String v = values[Math.max(0, Math.min(values.length - 1, getIndex.getAsInt()))];
			int maxW = w - 24;
			var font = Minecraft.getInstance().font;
			String shown = font.width(v) <= maxW ? v : font.plainSubstrByWidth(v, Math.max(4, maxW - 6)) + "..";
			ctx.centeredText(font, Component.literal(shown), x + w / 2, y + (h - 8) / 2,
					hover ? Palette.TEXT : 0xFFEDEDF2);
		}

		/** 用 3 行 1px 的短横拼一个 ‹ 或 ›。 */
		private void drawArrow(GuiGraphicsExtractor ctx, int x, int cy, boolean left, int color) {
			for (int i = 0; i < 3; i++) {
				int dx = left ? i : -i;
				ctx.fill(x + dx, cy - 2 + i, x + dx + 1, cy - 1 + i, color);
				ctx.fill(x + dx, cy + 2 - i, x + dx + 1, cy + 3 - i, color);
			}
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			int cur = getIndex.getAsInt();
			int next = (cur + 1) % Math.max(1, values.length);
			setIndex.accept(next);
			playButtonClickSound(Minecraft.getInstance().getSoundManager());
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			try {
				defaultButtonNarrationText(output);
			} catch (Throwable ignored) {
			}
		}
	}
}
