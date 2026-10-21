package dev.clouddisc.gui;

/**
 * 界面设计令牌（唯一的颜色/间距来源）。
 *
 * <h2>为什么要有这个类</h2>
 * 之前每个界面各自硬编码 ARGB（`C_CARD`、`C_SEL`、`hover ? 0xE0282830 : ...`…），
 * 于是"同一个东西在两个界面里颜色不一样"，看起来就不像一套东西。
 * 这里把语义收成 10 来个 token，所有界面只准引用它们。
 *
 * <h2>数值出处（不是凭空设计的）</h2>
 * <ul>
 *   <li><b>Sodium</b> {@code Colors.java}：面板/悬停层用<b>黑色叠透明度</b>
 *       （{@code BACKGROUND_DEFAULT 0x90000000}、{@code BACKGROUND_HOVER 0xE0000000}、
 *       {@code BACKGROUND_DARKER 0xB0000000}），文字 {@code 0xFFFFFFFF} / 禁用 {@code 0xFFAAAAAA}</li>
 *   <li><b>YACL</b> {@code AbstractWidget.inactiveColor 0xFFA0A0A0}、描边阴影 {@code 0xFF404040}</li>
 *   <li><b>Sodium</b> {@code Layout.java}：文字左内边距 8、标签尾部留白 20、行高 = {@code font.lineHeight × 2}、
 *       滚动条宽 7、选中竖条宽 3</li>
 *   <li>主色沿用本模组原来的蓝 {@code 0xFF4C8DFF}（Sodium 用青绿，这里只学它的<b>结构</b>）</li>
 * </ul>
 *
 * <p><b>为什么用黑色叠透明度</b>：背景是随机壁纸，叠上去会自然透出底图 →
 * 比不透明灰卡片"融"得多（这正是 Sodium 那种耐看的来源）。
 */
public final class Palette {

	private Palette() {
	}

	// ---------------- 面（Sodium 结构：全黑，靠 alpha 分层）

	/** 卡片/行底色（56% 黑）。 */
	public static final int SURFACE = 0x8C1A1C22; // 55%：让壁纸能透出来（原来 80% 会把整屏糊成纯色）
	/** 悬停（88% 黑，比底色更实，读起来像"被选中"）。 */
	public static final int SURFACE_HOVER = 0xC42E3138; // 悬停：同色系提亮，仍透背景
	/** 更实的面（70% 黑）：顶部标题带、底部操作带。 */
	public static final int SURFACE_ALT = 0xA814161A; // 顶部/底部带（别做成不透明，否则小块窗口里壁纸全被盖住）
	/** 模态/整屏遮罩（92% 黑）：壁纸之上先压这一层，保证文字可读。 */
	public static final int OVERLAY = 0xC0101114; // 整屏薄纱：统一色调，避免亮壁纸刺眼

	// ---------------- 线

	/** 常规描边（1px，很淡；深色面上 0xFF404040 会显脏，这里改用淡白）。 */
	public static final int BORDER = 0x22FFFFFF;
	/** 更明显一点的分隔线。 */
	public static final int DIVIDER = 0x14FFFFFF;
	/** 聚焦环（YACL 用纯白 outline）。 */
	public static final int BORDER_FOCUS = 0xFFFFFFFF;

	// ---------------- 字

	public static final int TEXT = 0xFFFFFFFF;
	/** 次要文字（Sodium FOREGROUND_DISABLED 的同款灰）。 */
	public static final int TEXT_SECONDARY = 0xFFAAAAAA;
	/** 最暗一档（YACL inactiveColor）：分组标题、状态行。 */
	public static final int TEXT_DIM = 0xFFA0A0A0;

	// ---------------- 主色

	/** 本模组的主色（原来的蓝）。 */
	public static final int ACCENT = 0xFF4C8DFF;
	/** 主色压暗：左侧竖条、进度条轨道。 */
	public static final int ACCENT_MUTED = 0xFF2E5FAF;
	/** 主色提亮：选中态的细边。 */
	public static final int ACCENT_LIGHT = 0xFF9CC0FF;

	// ---------------- 间距（Sodium Layout.java 的同名量）

	/** 文字离行左边框的距离。 */
	public static final int TEXT_LEFT_PADDING = 8;
	/** 标签与控件之间至少留这么多，防止贴在一起。 */
	public static final int LABEL_END_PADDING = 20;
	/** 左侧选中竖条宽度（Sodium PAGE_ENTRY_SELECTION_BAR_WIDTH）。 */
	public static final int SELECTION_BAR = 3;
	/** 滚动条宽度（Sodium SCROLLBAR_WIDTH = 7；我们收窄到 3，因为列表不长）。 */
	public static final int SCROLLBAR = 3;

	/** 按比例混合两个 ARGB（做悬停过渡/压暗用）。 */
	public static int mix(int from, int to, float t) {
		float k = Math.max(0f, Math.min(1f, t));
		int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
		int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
		return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
	}

	/** 把颜色按比例压暗（Sodium 的 DARKEN_FACTOR 思路）。 */
	public static int darken(int argb, float factor) {
		return mix(argb, 0xFF000000, Math.max(0f, Math.min(1f, factor)));
	}

	/**
	 * 圆角矩形（扫描线实现）。
	 *
	 * <p>为什么要它：MC 的界面 API 只有 {@code fill}（直角），而"方角套方角 + 描边"
	 * 就是廉价感的来源。圆角可以用<b>逐行内缩</b>画出来，不需要贴图、不需要 shader ✓
	 *
	 * @param radius 圆角半径；给 {@code h/2} 就是胶囊形（开关轨道/圆点都用这个）
	 */
	public static void roundRect(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
			int x, int y, int w, int h, int radius, int argb) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			ctx.fill(x, y, x + w, y + h, argb);
			return;
		}
		for (int i = 0; i < h; i++) {
			// dy = 距离最近的上/下直边有多远
			int dy = (i < r) ? (r - i) : (i >= h - r ? i - (h - r) + 1 : 0);
			int inset = 0;
			if (dy > 0) {
				double k = Math.sqrt(Math.max(0.0, (double) r * r - (double) dy * dy));
				inset = (int) Math.round(r - k);
			}
			ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
		}
	}
}
