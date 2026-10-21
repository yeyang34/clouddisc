package dev.clouddisc.gui;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * CloudDisc 配置界面（5 节：播放 / 物理声效 / 网络与音源 / 教程 / 更新日志）。
 *
 * <p><b>设计原则：零硬依赖。</b>只用原版控件（{@link Button} / {@link EditBox}），
 * 所以没装 Cloth Config / YACL / ModMenu 也照样能打开。
 * 三个入口：ModMenu 的模组列表按钮（装了才有）、快捷键（默认 K）、客户端命令 {@code /clouddisc}。
 *
 * <p>后两节是只读文本页，内容放在 jar 里的
 * {@code assets/clouddisc/text/guide.txt} 与 {@code changelog.txt} ——
 * 改文案不用动 Java。
 */
public class CloudDiscConfigScreen extends Screen {
	private static final int SECTION_PLAYBACK = 0;
	private static final int SECTION_PHYSICS = 1;
	private static final int SECTION_NETWORK = 2;
	private static final int SECTION_GUIDE = 3;
	private static final int SECTION_CHANGELOG = 4;
	private static final int SECTION_COUNT = 5;
	private static final String[] SECTION_NAMES = {"播放设置", "物理声效", "网络与音源", "使用教程", "更新日志"};

	/** 控件离背景块右边缘的内缩量。 */
	private static final int CONTROL_INSET = 6;

	private static final int ROWS = 11;
	private static final int ROW_START = 74;

	/** 控件列最大宽度（窄窗口会自动收窄到可用宽度的 40%）。 */
	private static final int FIELD_WIDTH = 168;
	private static final int TEXT_TOP = 76;
	// ---- 设计配色（深色卡片风）----
	private static final int C_BG_BAND   = dev.clouddisc.gui.Palette.SURFACE_ALT;
	private static final int C_ACCENT    = dev.clouddisc.gui.Palette.ACCENT;
	private static final int C_CARD      = dev.clouddisc.gui.Palette.SURFACE;
	private static final int C_CARD_EDGE = dev.clouddisc.gui.Palette.BORDER;
	private static final int C_DIVIDER   = dev.clouddisc.gui.Palette.DIVIDER;
	private static final int TEXT_LINE_HEIGHT = 11;
	private static final int TEXT_BOTTOM_MARGIN = 36;

	private record Label(Component text, int x, int y) {
	}

	private final Screen parent;
	private final CloudDiscConfig cfg;
	private final List<Label> labels = new ArrayList<>();
	/** 行内说明（悬停显示）：整行都是热区，标签因此可以写得很短。 */
	private final List<Tip> tips = new ArrayList<>();

	// ---- 选项页的"内容坐标 + 视口偏移"布局（滚动/分组的基础）----
	/** 已占用多少内容高度（构建期累加）。 */
	private int rowCursor;
	/** 选项页的滚动量（像素）。注意和纯文本页的 scroll 是两套。 */
	private int rowScroll;
	private int contentH;
	private final List<RowBind> rowBinds = new ArrayList<>();

	private record Tip(String text, int x, int y, int w, int h) {
	}

	/** 一行：控件 + 内容坐标上的 y + 文字 + 说明。 */
	private record RowBind(AbstractWidget widget, int contentY, String label, String tip) {
	}

	/** 分组小标题（占用 CAPTION_H 高度）。 */
	private record Caption(String text, int contentY) {
	}

	private final List<Caption> captions = new ArrayList<>();
	private static final int CAPTION_H = 16;
	private int viewTop;
	private int viewBottom;

	private int section;
	/** 换壁纸按钮（每帧按 loading 状态换字/禁用）。 */
	private Button wallpaperBtn;
	// ---- 自适应布局（笔记本小窗口也不会重叠）----
	private int layLeft;
	private int layRight;
	private int layFieldX;
	private int layFieldW;
	private int layRowStep = 18;
	// 控件高度：跟着行距走。行距被窗口高度压小时，控件也变矮，保证"每行的字和控件严丝合缝"。
	private int layRowH = 20;

	private void computeLayout() {
		int pad = 14;
		layLeft = pad;
		layRight = Math.max(pad + 120, this.width - pad);
		int avail = layRight - layLeft;
		// 控件列：只占右侧 40%，这样输入框/开关靠右，与底部按钮对齐成一条"控件列"
		layFieldW = Math.max(76, Math.min(FIELD_WIDTH, (int) (avail * 0.40)));
		// 控件列整体左移一点，留出"块内右边距" —— 否则控件边框正好压在背景块右边缘上，
		// 看起来就是"按钮探出去了"。6px 足够避开圆角与 1px 边框。
		layFieldX = layRight - layFieldW - CONTROL_INSET;
		// 行距：必须保证 ROWS 行都落在底部按钮带之上（按钮带从 height-40 开始）。
		// 原来用 (height-118)/ROWS —— 窗口矮的时候算出来仍可能压到底部（11 行时更明显）✗
		// 【固定行距】以前按窗口高度压缩行距，矮窗口会压到 step=10，
		// 而卡片高度是 layRowH+4=14 ⇒ 卡片互相重叠、控件压到下一行（"背景块包不住"）✗
		// 现在有滚动了，就不该压缩：固定成舒适尺寸，放不下就滚。
		layRowStep = 18;
		layRowH = 12;
		// 选项列表的视口：从 ROW_START 到"底部按钮带"之上
		viewTop = ROW_START;
		viewBottom = Math.max(viewTop + 40, this.height - 44);
	}
	private int scroll;
	private List<String> textLines = List.of();

	public CloudDiscConfigScreen(Screen parent) {
		super(Component.literal("CloudDisc 云唱片"));
		this.parent = parent;
		CloudDiscConfig c = CloudDiscClient.config();
		this.cfg = c != null ? c : CloudDiscConfig.get();
	}

	@Override
	protected void init() {
		computeLayout();
		clearWidgets();
		labels.clear();
		tips.clear();
		// 【两种页面都要重置】这些是"选项页"的行状态；切到教程/日志页时也必须清掉，
		// 否则 drawTips 还拿上一页的热区做判定 ⇒ 会出现"跟着鼠标飘的一行说明" ✗
		rowCursor = 0;
		rowScroll = 0;
		contentH = 0;
		rowBinds.clear();
		captions.clear();
		// 背景壁纸：第一次开界面时异步拉一张（失败就保持深色底）
		dev.clouddisc.gui.Wallpaper.ensure();

		boolean textSection = section >= SECTION_GUIDE;
		if (textSection) {
			textLines = loadText(section == SECTION_GUIDE ? "guide.txt" : "changelog.txt");
			clampScroll();
		} else {
			textLines = List.of();
			if (section == SECTION_PLAYBACK) {
				pagePlayback();
			} else if (section == SECTION_PHYSICS) {
				pagePhysics();
			} else {
				pageNetwork();
			}
			finishRows();
		}

		// 【换壁纸】放在标题带右侧（那块是空的），不占底部按钮带
		wallpaperBtn = Button.builder(Component.literal("换壁纸"), b -> {
			b.setMessage(Component.literal("换壁纸中…"));
			b.active = false;
			dev.clouddisc.gui.Wallpaper.refresh();
			dev.clouddisc.gui.HudStatus.show("正在换壁纸…");
		}).bounds(this.width - 14 - 60 - CONTROL_INSET, 12, 60, 18).build();
		addRenderableWidget(wallpaperBtn);
		int y = this.height - 28;
		// 底部按钮：右对齐到内容右边界，并保证每个按钮至少 44px（够放"◀ 上一节"）。
		// 原来固定从 layFieldX 起排、宽度按 layFieldW/4 算 ⇒ 窄窗口时 4 个按钮会往右溢出屏幕 ✗
		int bw = Math.max(44, (layFieldW - 3 * 4) / 4);
		int bx = layRight - (4 * bw + 3 * 4) - CONTROL_INSET;
		addRenderableWidget(Button.builder(Component.literal("◀ 上一节"), b -> {
			section = (section + SECTION_COUNT - 1) % SECTION_COUNT;
			scroll = 0;
			rebuildWidgets();
		}).bounds(bx, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("下一节 ▶"), b -> {
			section = (section + 1) % SECTION_COUNT;
			scroll = 0;
			rebuildWidgets();
		}).bounds(bx + bw + 4, y, bw, 20).build());

		Button save = Button.builder(Component.literal("保存"), b -> {
			cfg.save();
			CloudDiscClient.LOGGER.info("[CloudDisc] 配置已保存到磁盘");
			onClose();
		}).bounds(bx + 2 * (bw + 4), y, bw, 20).build();
		save.visible = !textSection; // 只读页没有可保存的东西
		addRenderableWidget(save);

		addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
				.bounds(bx + 3 * (bw + 4), y, bw, 20).build());
	}

	// ------------------------------------------------------------ 第 1 节：播放

	private void pagePlayback() {
		caption("播放与音量");
		row(0, "唱片改名前缀", textField(0, cfg.discNamePrefix, v -> cfg.discNamePrefix = v));
		row(1, "默认音源", cycleString(1, new String[] { "163", "local", "url" },
				() -> cfg.defaultProvider, v -> cfg.defaultProvider = v));
		row(2, "总音量", "1.0 = 原样；4.0 ≈ 原版唱片的响度。调大会更容易削顶", cycleFloat(2, new float[] { 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f },
				() -> cfg.jukeboxVolume, v -> cfg.jukeboxVolume = (float) v));
		row(3, "输出余量", "给峰值留的余量。有爆音/削顶就把这个调小", cycleFloat(3, new float[] { 0.5f, 0.75f, 1.0f, 1.25f, 1.5f },
				() -> cfg.outputGain, v -> cfg.outputGain = (float) v));
		row(4, "开始提前量", "所有人统一开声的提前量，20 刻 = 1 秒。网络差/文件大就调大", cycleInt(4, new int[] { 0, 10, 20, 40, 60, 100 },
				() -> cfg.startLeadTicks, v -> cfg.startLeadTicks = v));
		row(5, "预缓冲", "起播前先缓冲多少毫秒。经常卡顿就调大", cycleInt(5, new int[] { 500, 1000, 1500, 2000, 3000, 5000 },
				() -> cfg.prebufferMs, v -> cfg.prebufferMs = v));
		caption("音源与缓存");
		row(6, "本地音乐目录", "⚠ 改完要重启游戏。相对路径相对游戏目录", textField(6, cfg.localMusicDir, v -> cfg.localMusicDir = v));
		row(7, "缓存上限 MB", "只占你自己的磁盘，不占服务器。0 = 不限。超了按最久没用删除", cycleInt(7, new int[] { 128, 256, 512, 1024, 2048, 0 },
				() -> cfg.cacheMaxMb, v -> cfg.cacheMaxMb = v));
		// 【状态栏歌词】借用"正在播放"那行显示当前这句歌词（只对网易云纯数字 id 有效）
		caption("歌词");
		row(8, "状态栏歌词", "播放时在物品栏上方那行显示当前这句歌词。只对网易云（纯数字 id）曲目有效",
				toggle(8, () -> cfg.hudLyrics, v -> cfg.hudLyrics = v));
		// 歌词颜色：一个循环色板同时覆盖"彩虹渐变"和"固定颜色"两种需求
		row(9, "歌词颜色", "彩虹渐变是逐字符换色 + 随时间流动；也可以选固定色", cycleString(9, LYRIC_STYLES,
				() -> lyricStyleName(cfg.lyricStyle), v -> cfg.lyricStyle = lyricStyleValue(v)));
		// 动作栏只有一行，所以"原文 / 译文"只能三选一
		row(10, "歌词显示", "动作栏只有一行：原文 / 译文 / 两者拼一行。没有译文时自动显示原文", cycleString(10, LYRIC_DISPLAYS,
				() -> LYRIC_DISPLAYS[Math.max(0, Math.min(2, cfg.lyricDisplay))],
				v -> cfg.lyricDisplay = lyricDisplayValue(v)));
	}

	/** 歌词显示模式：0 原文 / 1 译文 / 2 两者。 */
	private static final String[] LYRIC_DISPLAYS = {"原文", "译文", "原文 ｜ 译文"};

	private static int lyricDisplayValue(String name) {
		for (int i = 0; i < LYRIC_DISPLAYS.length; i++) {
			if (LYRIC_DISPLAYS[i].equals(name)) {
				return i;
			}
		}
		return 0;
	}

	/** 歌词颜色色板：0 = 白，-1 = 彩虹，其余是固定 RGB。 */
	private static final String[] LYRIC_STYLES = {"默认白", "彩虹渐变", "青蓝", "橙", "紫", "粉", "绿"};
	private static final int[] LYRIC_STYLE_VALUES = {0, -1, 0x4C8DFF, 0xFFA640, 0xB98CFF, 0xFF8CC8, 0x66E08A};

	private static String lyricStyleName(int v) {
		for (int i = 0; i < LYRIC_STYLE_VALUES.length; i++) {
			if (LYRIC_STYLE_VALUES[i] == v) {
				return LYRIC_STYLES[i];
			}
		}
		return LYRIC_STYLES[0];
	}

	private static int lyricStyleValue(String name) {
		for (int i = 0; i < LYRIC_STYLES.length; i++) {
			if (LYRIC_STYLES[i].equals(name)) {
				return LYRIC_STYLE_VALUES[i];
			}
		}
		return 0;
	}

	// ------------------------------------------------------- 第 2 节：物理声效

	/**
	 * 物理声效页（纯客户端：只作用于我们自己的那条声源，原版唱片与其它声音完全不受影响）。
	 *
	 * <p>三组开关对应计划里的"总开关 / 强度 / 精度"，另外几个是调参与排障用的：
	 * 遮挡陡度 k、漏音通路数、严格遮挡、方向性、调试日志。
	 */
	private void pagePhysics() {
		caption("遮挡与强度");
		row(0, "物理声效", "关掉 = 完全不做遮挡/混响（干净直通），用来 A/B 对比", toggle(0, () -> cfg.physicsSound, v -> {
			cfg.physicsSound = v;
			dev.clouddisc.audio.Acoustics.setEnabled(v);
		}));
		row(1, "声效强度", "同时缩放「闷度」和「余响」。0=关，1=默认，2=最激进",
				cycleFloat(1, new float[] {0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f},
						() -> cfg.physicsSoundLevel, v -> cfg.physicsSoundLevel = (float) v));
		row(2, "隔墙闷度 k", "遮挡陡度。越大越闷，4.5 = 默认",
				cycleFloat(2, new float[] {2.0f, 3.0f, 4.0f, 4.5f, 5.0f, 6.0f, 7.0f, 9.0f},
						() -> cfg.physicsAbsorption, v -> cfg.physicsAbsorption = (float) v));
		row(3, "漏音通路数", "有几条缝才算「明显透声」。3 = 默认（仅旧算法用）",
				cycleInt(3, new int[] {1, 2, 3, 4, 5, 6, 8}, () -> cfg.physicsOcclusionPaths,
						v -> cfg.physicsOcclusionPaths = v));
		row(4, "混响射线数", "越大越准、也越吃 CPU。32 = 默认", cycleInt(4, new int[] {16, 24, 32, 48, 64},
				() -> cfg.physicsRays, v -> cfg.physicsRays = v));
		row(5, "严格遮挡", "开 = 只看一条直线，墙上有缝也当墙。一般不推荐", toggle(5, () -> cfg.physicsStrictOcclusion, v -> cfg.physicsStrictOcclusion = v));
		row(6, "方向性", "把声源位置沿反射来向偏移，听感是「从拐角传来」而不是穿墙而出", toggle(6, () -> cfg.physicsSoundDirection, v -> cfg.physicsSoundDirection = v));
		caption("调试与排障");
		row(7, "调试日志", "每轮打印命中的方块材质，排障用。平时关掉免得刷日志", toggle(7, () -> cfg.physicsSoundDebug, v -> cfg.physicsSoundDebug = v));
		// 【门道模型】穿几道门就闷几档（0 道门=清亮 / 1 道=略暗 / 2 道=明显闷）。关掉即回到旧行为。
		row(8, "门道模型", "按「穿过了几道门」算闷度：0 道不闷 / 1 道略暗 / 2 道明显闷 / 3 道更闷。关掉即回到 1.1.1 的旧算法",
				toggle(8, () -> cfg.physicsDoorwayModel, v -> cfg.physicsDoorwayModel = v));
		// 只读状态行（EFX 到底可不可用，排障第一眼看这个）。
		// 【必须走 caption 走内容坐标】原来是按 ROW_START + 9*行距 的绝对 y 画的，
		// 改成游标布局后那行会夹在列表中间 ✗（实测就是这个）
		String efx = dev.clouddisc.audio.EfxEngine.isAvailable()
				? "EFX 可用（" + dev.clouddisc.audio.EfxEngine.bands() + " 段混响）"
				: "EFX 不可用 → 已回退自研 DSP：" + dev.clouddisc.audio.EfxEngine.status();
		caption("状态：" + efx);
	}

	// ------------------------------------------------------- 第 3 节：网络与音源

	private void pageNetwork() {
		caption("同步通道");
		row(0, "UDP 端口", "⚠ 改完要重启。同一台电脑双开时，两个客户端要填不同端口", textField(0, String.valueOf(cfg.udpPort), v -> {
			try {
				cfg.udpPort = Integer.parseInt(v.trim());
			} catch (NumberFormatException ignored) {
				// 输入到一半不是数字，先忽略
			}
		}));
		row(1, "局域网自动发现", toggle(1, () -> cfg.enableLanDiscovery, v -> cfg.enableLanDiscovery = v));
		row(2, "启用聊天兜底通道", toggle(2, () -> cfg.enableChatRelay, v -> cfg.enableChatRelay = v));
		row(3, "聊天仅作兜底", "开 = 中继或 UDP 任一可用时，聊天通道自动静音（不刷屏）", toggle(3, () -> cfg.chatRelayOnlyWhenNoUdp, v -> cfg.chatRelayOnlyWhenNoUdp = v));
		caption("网易云与解析服务");
		row(4, "网易云识别", "关掉就不再识别分享链接与歌曲 id", toggle(4, () -> cfg.neteaseEnabled, v -> cfg.neteaseEnabled = v));
		row(5, "网易云外链模板", "{id} 会替换成歌曲 id。默认值能放免费曲，VIP 曲会跳到 404 页面", textField(5, cfg.neteaseUrlTemplate, v -> cfg.neteaseUrlTemplate = v));
		row(6, "自建解析服务", "填你自己的解析服务地址，最可靠（VIP/独家曲也靠它）", textField(6, cfg.neteaseEndpoint, v -> cfg.neteaseEndpoint = v));
		row(7, "服务端中继", "服主把同一个 jar 放进服务端 mods/ 后，跨公网也能同步", toggle(7, () -> cfg.enableServerRelay, v -> cfg.enableServerRelay = v));
		row(8, "解析服务令牌", "会作为 X-Token 请求头发给解析服务", textField(8, tokenOf(cfg), v -> setToken(cfg, v)));
	}

	// ------------------------------------------------------------ 控件构造

	private void row(int index, String label, AbstractWidget widget) {
		row(index, label, null, widget);
	}

	/**
	 * 一行：左标签 + 右控件，可选<b>悬停说明</b>。
	 * <p>Sodium / YACL 都把长解释放进 tooltip，标签只留短名字 —— 这样既不会被
	 * 截断成"…"，信息也没丢（鼠标移上去就有）。整行都是说明热区，不用精确对准文字 ✓
	 */
	private void row(int index, String label, String tip, AbstractWidget widget) {
		int contentY = rowCursor;
		rowBinds.add(new RowBind(widget, contentY, label, tip));
		addRenderableWidget(widget);
		rowCursor += layRowStep;
	}

	/** 插一个分组小标题（占 {@link #CAPTION_H} 高度）。必须在它后面的 row() 之前调用。 */
	private void caption(String text) {
		captions.add(new Caption(text, rowCursor));
		rowCursor += CAPTION_H;
	}

	/** 一页的行都加完了：收尾算总高度 + 应用滚动。 */
	private void finishRows() {
		contentH = rowCursor;
		clampRowScroll();
		applyRowScroll();
	}

	private int viewH() {
		return Math.max(1, viewBottom - viewTop);
	}

	private void clampRowScroll() {
		int max = Math.max(0, contentH - viewH());
		if (rowScroll < 0) {
			rowScroll = 0;
		}
		if (rowScroll > max) {
			rowScroll = max;
		}
	}

	/** 把每个控件按滚动量挪到屏幕位置；完整在视口外的直接隐藏（MC 的控件没法被裁剪）。 */
	private void applyRowScroll() {
		for (RowBind b : rowBinds) {
			int y = viewTop + b.contentY() - rowScroll;
			AbstractWidget w = b.widget();
			w.setY(y);
			// 【只在完全可见时显示】之前允许"部分可见"，于是被滚到视口上方的控件
			// 仍会画出来、压在顶部分类按钮上 —— 控件是 super 画的，我没法给它设纵向裁剪 ✗
			w.visible = (y >= viewTop) && (y + layRowH <= viewBottom);
		}
	}

	/** 一行当前在屏幕上的 y。 */
	private int screenY(RowBind b) {
		return viewTop + b.contentY() - rowScroll;
	}

	/** 该行是否完整/部分可见。 */
	private boolean rowVisible(int y) {
		return (y + layRowH >= viewTop) && (y <= viewBottom);
	}

	/** 悬停到某一行就弹说明（在 super 之前调用，保证画在最上层）。 */
	private void drawTips(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		try {
			for (RowBind b : rowBinds) {
				if (b.tip() == null || b.tip().isBlank()) {
					continue;
				}
				int y = screenY(b);
				if (mouseX >= layLeft && mouseX < layRight && mouseY >= y && mouseY < y + layRowH) {
					context.setTooltipForNextFrame(Component.literal(b.tip()), mouseX, mouseY);
					return;
				}
			}
		} catch (Throwable ignored) {
			// 说明画不出来不影响使用
		}
	}

	/** 按像素宽度截断并加省略号（二分找最长可容纳前缀，避免逐字测量）。 */
	private String ellipsize(String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String dots = "...";
		int budget = Math.max(0, maxWidth - this.font.width(dots));
		int lo = 0;
		int hi = text.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (this.font.width(text.substring(0, mid)) <= budget) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return text.substring(0, lo).trim() + dots;
	}

	private int rowY(int index) {
		// 【内容坐标】构建期 rowScroll 恒为 0，所以控件先落在未滚动的绝对位置；
		// 之后 applyRowScroll() 会把它们整体按滚动量平移。index 已不用来算位置
		// （分组标题会占用额外高度，用累加游标更准）。
		return viewTop + rowCursor;
	}

	private EditBox textField(int index, String initial, Consumer<String> set) {
		// 【对齐的关键】宽度/高度必须和 toggle / cycle* 用同一组值。
		// 原来这里写死 FIELD_WIDTH(168) 且高度写死 20，窄窗口下 layFieldW 会小于 168
		// ⇒ 输入框比别的控件更宽、左边更靠左、还高出一截 ⇒ 看起来"按钮对不上文字" ✗
		EditBox field = new EditBox(this.font, layFieldX, rowY(index),
				layFieldW, layRowH, Component.literal(""));
		field.setMaxLength(200);
		field.setValue(initial == null ? "" : initial);
		field.setResponder(set);
		return field;
	}

	private AbstractWidget toggle(int index, BooleanSupplier get, Consumer<Boolean> set) {
		// 自绘开关：和输入框/下拉同高、右边缘对齐控件列。
		// 原来那个 Button("开/关") 是在灰盒子里写两个汉字，混在一排控件里很突兀。
		// Sodium 风格：20×20 方勾选框，右对齐到控件列右边界
		int size = Math.max(10, Math.min(18, layRowH));
		int tx = layFieldX + Math.max(0, layFieldW - size);
		return new SodiumControls.Checkbox(tx, rowY(index), size, get, set);
	}

		private AbstractWidget cycleInt(int index, int[] values, IntSupplier get, IntConsumer set) {
		String[] labels = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			labels[i] = String.valueOf(values[i]);
		}
		return cycleOf(index, labels,
				() -> {
					int cur = 0;
					for (int i = 0; i < values.length; i++) {
						if (values[i] == get.getAsInt()) {
							cur = i;
						}
					}
					return cur;
				},
				i -> set.accept(values[Math.max(0, Math.min(values.length - 1, i))]));
	}

	private AbstractWidget cycleFloat(int index, float[] values, DoubleSupplier get, DoubleConsumer set) {
		String[] labels = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			labels[i] = fmt(values[i]);
		}
		return cycleOf(index, labels,
				() -> {
					int cur = 0;
					for (int i = 0; i < values.length; i++) {
						if (Math.abs(values[i] - get.getAsDouble()) < 0.001) {
							cur = i;
						}
					}
					return cur;
				},
				i -> set.accept(values[Math.max(0, Math.min(values.length - 1, i))]));
	}

	private AbstractWidget cycleString(int index, String[] values, Supplier<String> get, Consumer<String> set) {
		return cycleOf(index, values,
				() -> {
					int cur = 0;
					for (int i = 0; i < values.length; i++) {
						if (values[i].equalsIgnoreCase(get.get())) {
							cur = i;
						}
					}
					return cur;
				},
				i -> set.accept(values[Math.max(0, Math.min(values.length - 1, i))]));
	}

	/** Sodium 的 ‹ 值 › 控件：整列同宽、右对齐，点一下切下一个。 */
	private AbstractWidget cycleOf(int index, String[] labels, IntSupplier getIndex, IntConsumer setIndex) {
		return new SodiumControls.Cycle(layFieldX, rowY(index), layFieldW, Math.max(12, layRowH),
				labels, getIndex, setIndex);
	}
	/** 从 httpHeaders 里取出 X-Token 的值（界面上显示用）。 */
	private static String tokenOf(CloudDiscConfig cfg) {
		if (cfg.httpHeaders != null) {
			for (String h : cfg.httpHeaders) {
				if (h != null && h.regionMatches(true, 0, "X-Token:", 0, 8)) {
					return h.substring(8).trim();
				}
			}
		}
		return "";
	}

	/** 把界面上的令牌写回 httpHeaders（空值就不带该请求头）。 */
	private static void setToken(CloudDiscConfig cfg, String token) {
		String t = token == null ? "" : token.trim();
		cfg.httpHeaders = t.isEmpty() ? new String[0] : new String[] { "X-Token: " + t };
	}

	private static String fmt(float v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}

	// ------------------------------------------------------------ 只读文本页

	/** 从 jar 里读文本（{@code assets/clouddisc/text/xxx.txt}），按当前宽度自动折行。 */
	private List<String> loadText(String fileName) {
		StringBuilder sb = new StringBuilder();
		try (InputStream in = CloudDiscConfigScreen.class.getResourceAsStream("/assets/clouddisc/text/" + fileName)) {
			if (in == null) {
				return List.of("（缺少资源文件 " + fileName + "）");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line).append('\n');
				}
			}
		} catch (Exception e) {
			return List.of("（读取 " + fileName + " 失败: " + e + "）");
		}
		return wrap(sb.toString(), this.width - 40);
	}

	/** 自己按像素宽度折行：只用 font.getWidth，避免依赖 OrderedText 那套 API。 */
	private List<String> wrap(String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			if (paragraph.isEmpty()) {
				out.add("");
				continue;
			}
			StringBuilder line = new StringBuilder();
			for (int i = 0; i < paragraph.length(); i++) {
				char c = paragraph.charAt(i);
				if (line.length() > 0 && this.font.width(line.toString() + c) > maxWidth) {
					out.add(line.toString());
					line.setLength(0);
				}
				line.append(c);
			}
			out.add(line.toString());
		}
		return out;
	}

	private int linesPerPage() {
		return Math.max(1, (this.height - TEXT_TOP - TEXT_BOTTOM_MARGIN) / TEXT_LINE_HEIGHT);
	}

	private int maxScroll() {
		return Math.max(0, textLines.size() - linesPerPage());
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	// 【26.2】旧版  签名已移除（26.2 改用 KeyEvent / 交给控件自处理 ✓）

	// 【26.2】旧版 mouseScrolled 签名已移除（26.2 改用 KeyEvent / 交给控件自处理 ✓）

	// 【26.2】旧版  签名已移除（26.2 改用 KeyEvent / 交给控件自处理 ✓）

	// ------------------------------------------------------------ 渲染

	// ===== 【26.2 关键修复】界面文字必须走 ActiveTextCollector（textRenderer()）=====
	// 26.2 的 GuiGraphicsExtractor.text(...)/centeredText(...) 只登记"可交互/悬停文字"✗
	// 所以之前界面上一个字都看不到 ✗ 正门是 textRenderer().accept(...) ✓
	// 这两个 helper 的参数形状与旧调用【完全一致】（只是省掉了 font ✓）→ 调用点可以整体替换 ✓
	private void drawText(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
			net.minecraft.network.chat.Component text, int x, int y, int color) {
		ctx.textRenderer().accept(x, y, text);
	}

	private void drawText(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
			String text, int x, int y, int color) {
		ctx.textRenderer().accept(x, y, net.minecraft.network.chat.Component.literal(text));
	}

	private void drawCentered(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
			net.minecraft.network.chat.Component text, int centerX, int y, int color) {
		ctx.textRenderer().accept(net.minecraft.client.gui.TextAlignment.CENTER, centerX, y, text);
	}

	private void drawCentered(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
			String text, int centerX, int y, int color) {
		ctx.textRenderer().accept(net.minecraft.client.gui.TextAlignment.CENTER, centerX, y,
				net.minecraft.network.chat.Component.literal(text));
	}
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// 【26.2】先铺一层半透明底（等价于旧 renderBackground ✓ 但必须画在最前面）
		if (wallpaperBtn != null) {
			boolean busy = dev.clouddisc.gui.Wallpaper.isLoading();
			wallpaperBtn.setMessage(Component.literal(busy ? "换壁纸中…" : "换壁纸"));
			wallpaperBtn.active = !busy;
		}
		dev.clouddisc.gui.Wallpaper.render(context, this.width, this.height);
		// ---------------- 设计层（只画装饰，不碰任何功能） ----------------
		int cx = this.width / 2;
		// 顶部标题带 + 主色细线
		context.fill(0, 0, this.width, 46, C_BG_BAND);
		context.fill(0, 45, this.width, 47, C_ACCENT);
		// 内容区：每行一张卡片（左侧一条主色/灰色竖条做层次）+ 分组标题 + 标签
		if (section < SECTION_GUIDE) {
			int left = layLeft;
			int right = layRight;
			int maxLabelW = Math.max(24, layFieldX - 8 - (layLeft + 6));
			try {
				context.enableScissor(left, viewTop - 4, right, viewBottom + 2);
			} catch (Throwable ignored) {
			}
			for (Caption c : captions) {
				int y = viewTop + c.contentY() - rowScroll;
				if (!rowVisible(y) && !(y + CAPTION_H >= viewTop && y <= viewBottom)) {
					continue;
				}
				// 分组标题：一条细线 + 小字，不用大色块（色块会压住内容）
				context.fill(left, y + CAPTION_H - 5, right, y + CAPTION_H - 4, C_DIVIDER);
				context.text(this.font, Component.literal(c.text()), left + 2, y + 2, dev.clouddisc.gui.Palette.TEXT_DIM);
			}
			for (RowBind b : rowBinds) {
				int y = screenY(b);
				if (!rowVisible(y)) {
					continue;
				}
				// 【Sodium 规则】悬停只改一层背景透明度（0xB0 → 0xE0），不缩放、不位移。
				// 【每行一块柔和底色】纯文字浮在壁纸上太费眼（实测反馈），
				// 但也不能回到"贴一圈描边的卡片"（那是另一个极端）。
				// 现在：圆角 3px、无描边、暖调石板灰；悬停同色系提亮。
				int bandTop = y - 2;
				int bandBottom = y + layRowH + 2;
				boolean hover = mouseX >= left && mouseX < right && mouseY >= bandTop && mouseY < bandBottom;
				dev.clouddisc.gui.Palette.roundRect(context, left, bandTop, right - left, bandBottom - bandTop, 3,
						hover ? dev.clouddisc.gui.Palette.SURFACE_HOVER : dev.clouddisc.gui.Palette.SURFACE);
				// 标签（放不下才截断加省略号；两者都不换行、也不缩控件）
				context.text(this.font, Component.literal(ellipsize(b.label(), maxLabelW)),
						left + 6, y + Math.max(1, (layRowH - 9) / 2), dev.clouddisc.gui.Palette.TEXT);
			}
			// 滚动条：贴屏幕最右侧的留白里。
			// 【别放在 right-2】right = layRight = 内容右边界，控件也止于那里 ⇒ 会压住勾选框／箭头 ✗
			if (contentH > viewH()) {
				int barW = dev.clouddisc.gui.Palette.SCROLLBAR;
				int barX = this.width - barW - 3;
				int trackTop = viewTop;
				int trackH = viewH();
				int barH = Math.max(14, (int) ((long) trackH * trackH / Math.max(1, contentH)));
				int barY = trackTop + (int) ((long) (trackH - barH) * rowScroll / Math.max(1, contentH - viewH()));
				dev.clouddisc.gui.Palette.roundRect(context, barX, trackTop, barW, trackH, barW / 2, 0x33000000);
				dev.clouddisc.gui.Palette.roundRect(context, barX, barY, barW, barH, barW / 2, 0x66A0C0FF);
			}
			try {
				context.disableScissor();
			} catch (Throwable ignored) {
			}
		}
		// 底部操作带（按钮就落在这一带里）
		context.fill(0, this.height - 40, this.width, this.height, C_BG_BAND);
		context.fill(0, this.height - 41, this.width, this.height - 40, C_DIVIDER);
		// ---------------- 顶部胶囊标签栏（YACL 风格：选中项高亮 + 底部指示条） ----------------
		{
			int tabH = 18;
			int gap = 6;
			int pad = 12;
			int avail = layRight - layLeft;
			int[] w = new int[SECTION_COUNT];
			for (int i = 0; i < SECTION_COUNT; i++) {
				w[i] = Math.max(24, (avail - gap * (SECTION_COUNT - 1)) / SECTION_COUNT);
			}
			int x = layLeft;
			int y = 52;
			for (int i = 0; i < SECTION_COUNT; i++) {
				boolean active = i == section;
				boolean hover = mouseX >= x && mouseX < x + w[i] && mouseY >= y && mouseY < y + tabH;
				int bg = active ? 0xFF2B4A82 : (hover ? 0x8A2A2A32 : 0x60202026);
				context.fill(x, y, x + w[i], y + tabH, bg);
				if (active) {
					context.fill(x, y + tabH - 2, x + w[i], y + tabH, C_ACCENT);
				} else {
					context.fill(x, y + tabH - 1, x + w[i], y + tabH, 0x503A3A40);
				}
				int tc = active ? 0xFFFFFFFF : (hover ? 0xFFE6E6EA : 0xFF9A9AA5);
				// 窄窗口下 tab 只有几十像素宽 —— 必须截断，否则文字会压到相邻 tab 上
				drawCentered(context, Component.literal(ellipsize(SECTION_NAMES[i], Math.max(12, w[i] - 8))),
						x + w[i] / 2, y + 5, tc);
				x += w[i] + gap;
			}
		}
		// 【26.2】控件要画在装饰之上 ⇒ super 必须在所有装饰画完之后调用一次。
		// 之前它被插进了上面的 tab 循环里，等于每个 tab 都重绘一遍控件 ✗
		drawTips(context, mouseX, mouseY);
		// 控件是 super 画的，没法单独给它设边界 —— 这里用水平裁剪兜住：
		// 任何越过内容右边界（layRight）的部分都被切掉，不会"漏到外面去"。
		boolean clipped = false;
		try {
			context.enableScissor(0, 0, layRight, this.height);
			clipped = true;
		} catch (Throwable ignored) {
		}
		super.extractRenderState(context, mouseX, mouseY, delta);
		if (clipped) {
			try {
				context.disableScissor();
			} catch (Throwable ignored) {
			}
		}
		// ---------------- 装饰到此为止，下面全是原有绘制 ----------------
		// 【26.2 修复】原来这行不透明遮罩画在所有文字之后 → 把 UI 盖住（表现为「界面没有文字」）✗ 已挪到方法最前面 ✓
		drawCentered(context, this.title, this.width / 2, 8, 0xFFFFFF);
		// 标语 + 一句话定位
		drawCentered(context, Component.literal(CloudDiscClient.SLOGAN),
				this.width / 2, 18, 0xFFC840);
		drawCentered(context, Component.literal(CloudDiscClient.TAGLINE),
				this.width / 2, 28, 0x909090);
		String header = "第 " + (section + 1) + " / " + SECTION_COUNT + " 节 · " + SECTION_NAMES[section];
		drawCentered(context, Component.literal(header), this.width / 2, 39, 0xA0E0A0);

		if (section >= SECTION_GUIDE) {
			int perPage = linesPerPage();
			int end = Math.min(textLines.size(), scroll + perPage);
			// 【给正文加底板】教程/更新日志是大段文字，直接压在壁纸上根本看不清。
			// 用比行底色更实一点的面（0xD8），并把左右各留 8px 内边距。
			int panelTop = TEXT_TOP - 6;
			int panelBottom = this.height - TEXT_BOTTOM_MARGIN + 2;
			dev.clouddisc.gui.Palette.roundRect(context, 12, panelTop, this.width - 24,
					Math.max(24, panelBottom - panelTop), 4, 0xD81A1C22);
			int y = TEXT_TOP;
			for (int i = scroll; i < end; i++) {
				String line = textLines.get(i);
				// 小节标题（用 ═ 或 ─ 包起来的行）高亮一下，便于扫读
				int color = line.startsWith("═") || line.startsWith("─") ? 0x9FE08A : 0xE0E0E0;
				drawText(context, line, 20, y, color);
				y += TEXT_LINE_HEIGHT;
			}
			String hint = (scroll > 0 || maxScroll() > 0)
					? "滚轮 / ↑↓ / PgUp PgDn 翻阅　（" + (scroll + 1) + " - " + end + " / " + textLines.size() + " 行）"
					: "";
			drawCentered(context, Component.literal(hint), this.width / 2, this.height - 40, 0x909090);
		} else {
			for (Label label : labels) {
				drawText(context, label.text(), label.x(), label.y(), 0xE0E0E0);
			}
		}
		// 【26.2】super 必须最先调用（它画界面底色），否则会把我们画的文字全盖住 ✗
	}

	/**
	 * 【26.2】教程页 / 更新日志页：滚轮翻页。
	 * 签名来自 javap 实测：mouseScrolled(double,double,double,double) ✓
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		try {
			if (section >= SECTION_GUIDE) {
				int perPage = linesPerPage();
				int max = maxScroll();
				if (scrollY < 0) {
					scroll = Math.min(max, scroll + 1);
				} else if (scrollY > 0) {
					scroll = Math.max(0, scroll - 1);
				}
				CloudDiscClient.LOGGER.info("[CloudDisc] 配置界面滚轮: scrollY={} → scroll={}/{}（共 {} 行，每页 {}）",
						scrollY, scroll, max, textLines.size(), perPage);
				return true;
			}
			// 选项页：内容超出视口才滚动（滚一格 = 一行）
			if (contentH > viewH()) {
				rowScroll -= (int) Math.round(scrollY * 18.0);
				clampRowScroll();
				applyRowScroll();
				return true;
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 滚轮处理异常: {}", t.toString());
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/**
	 * 【26.2】教程页 / 更新日志页：↑↓ / PgUp / PgDn 翻页。
	 * 签名来自 javap 实测：keyPressed(net.minecraft.client.input.KeyEvent) ✓
	 * KeyEvent 访问器：key() / scancode() / modifiers() ✓
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		try {
			if (section >= SECTION_GUIDE) {
				int k = event.key();
				int perPage = linesPerPage();
				int max = maxScroll();
				boolean changed = true;
				if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
					scroll = Math.min(max, scroll + 1);
				} else if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
					scroll = Math.max(0, scroll - 1);
				} else if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) {
					scroll = Math.min(max, scroll + Math.max(1, perPage - 1));
				} else if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
					scroll = Math.max(0, scroll - Math.max(1, perPage - 1));
				} else if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME) {
					scroll = 0;
				} else if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_END) {
					scroll = max;
				} else {
					changed = false;
				}
				if (changed) {
					CloudDiscClient.LOGGER.info("[CloudDisc] 配置界面按键翻页: key={} → scroll={}/{}", k, scroll, max);
					return true;
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 按键处理异常: {}", t.toString());
		}
		return super.keyPressed(event);
	}
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		// 【26.2】顶部胶囊标签栏是手绘的（不是控件）→ 必须在这里手动命中判定 ✓
		try {
			int tabH = 18, gap = 6;
			int avail = layRight - layLeft;
			int[] w = new int[SECTION_COUNT];
			for (int i = 0; i < SECTION_COUNT; i++) {
				w[i] = Math.max(24, (avail - gap * (SECTION_COUNT - 1)) / SECTION_COUNT);
			}
			int x = layLeft;
			int y = 52;
			double mx = event.x();
			double my = event.y();
			for (int i = 0; i < SECTION_COUNT; i++) {
				if (mx >= x && mx < x + w[i] && my >= y && my < y + tabH) {
					section = i;
					scroll = 0;
					rebuildWidgets();
					return true;
				}
				x += w[i] + gap;
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] tab 命中判定异常（已忽略）: {}", t.toString());
		}
		return super.mouseClicked(event, doubleClick);
	}


	public void onClose() {
		if (this.minecraft != null) {
			Minecraft.getInstance().setScreenAndShow(this.parent);
		}
	}
}
