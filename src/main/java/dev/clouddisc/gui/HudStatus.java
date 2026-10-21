package dev.clouddisc.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * 屏幕下方的状态行（动作栏：无前缀、可随时改字、不进聊天栏）。
 *
 * <h2>为什么不用原版那行「正在播放」</h2>
 * 原版那个位置渲染的是翻译键 {@code record.nowPlaying}（「正在播放: %s」）——
 * <b>前缀是原版加的，我们改不掉</b>，于是会出现「正在播放: 正在解析…」这种自相矛盾的话 ✗
 * <p>改用<b>动作栏</b>（26.2 里是 {@code LocalPlayer.sendOverlayMessage}，旧版叫
 * {@code displayClientMessage(comp, true)}）：没有前缀、位置就在物品栏上方、不进聊天栏 ✓
 *
 * <h2>颜色样式（只作用于歌词）</h2>
 * <ul>
 *   <li>{@code 0} = 默认白</li>
 *   <li>{@code -1} = <b>彩虹渐变</b>：逐字符上色，色相同时随时间推移 ⇒ 真的在流动</li>
 *   <li>{@code >0} = 固定颜色（{@code 0xRRGGBB}）</li>
 * </ul>
 * 原版没有"彩虹"这个格式，所谓彩虹都是逐字符换色模拟出来的 —— 这里也一样。
 *
 * <p>全部 try/catch：这只是提示，任何异常都不该影响播放 ✓
 */
@Environment(EnvType.CLIENT)
public final class HudStatus {

	private HudStatus() {
	}

	/** 写一行纯白状态（解析中 / 缓冲中 / 即将开始 这些用）。 */
	public static void show(String text) {
		showStyled(text, 0);
	}

	/**
	 * 写一行带样式的状态。
	 *
	 * @param style 0 = 白，-1 = 彩虹渐变，&gt;0 = 固定 RGB
	 */
	public static void showStyled(String text, int style) {
		if (text == null || text.isBlank()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.player == null) {
				return;
			}
			String t = text.length() > 64 ? text.substring(0, 63) + "…" : text;
			// 【26.2】动作栏方法改名了：旧版 displayClientMessage(comp, true) → sendOverlayMessage(comp)（javap 实测 ✓）
			mc.player.sendOverlayMessage(style <= 0 && style != -1 ? Component.literal(t) : colorize(t, style));
		} catch (Throwable ignored) {
			// 提示失败不影响播放
		}
	}

	/** 逐字符上色。{@code style == -1} 时是流动的彩虹。 */
	private static Component colorize(String text, int style) {
		MutableComponent out = Component.literal("");
		long now = System.currentTimeMillis();
		for (int i = 0; i < text.length(); i++) {
			int rgb;
			if (style == -1) {
				// 色相 = 时间（让它流动） + 字符序（形成渐变）
				float hue = (((now / 14L) % 360L) + i * 12L) % 360L / 360f;
				rgb = hsbToRgb(hue, 0.72f, 1.0f);
			} else if (style > 0) {
				rgb = style & 0xFFFFFF;
			} else {
				rgb = 0xFFFFFF;
			}
			out.append(Component.literal(String.valueOf(text.charAt(i)))
					.withStyle(s -> s.withColor(TextColor.fromRgb(rgb & 0xFFFFFF))));
		}
		return out;
	}

	/** HSB→RGB（自己实现，避免依赖 java.desktop）。 */
	private static int hsbToRgb(float h, float s, float v) {
		float hh = (h % 1f) * 6f;
		int i = (int) Math.floor(hh);
		float f = hh - i;
		float p = v * (1 - s);
		float q = v * (1 - s * f);
		float t = v * (1 - s * (1 - f));
		float r;
		float g;
		float b;
		switch (i % 6) {
			case 0 -> {
				r = v;
				g = t;
				b = p;
			}
			case 1 -> {
				r = q;
				g = v;
				b = p;
			}
			case 2 -> {
				r = p;
				g = v;
				b = t;
			}
			case 3 -> {
				r = p;
				g = q;
				b = v;
			}
			case 4 -> {
				r = t;
				g = p;
				b = v;
			}
			default -> {
				r = v;
				g = p;
				b = q;
			}
		}
		int ri = Math.max(0, Math.min(255, (int) (r * 255)));
		int gi = Math.max(0, Math.min(255, (int) (g * 255)));
		int bi = Math.max(0, Math.min(255, (int) (b * 255)));
		return (ri << 16) | (gi << 8) | bi;
	}
}
