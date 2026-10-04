package dev.clouddisc.gui;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
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
 * <p><b>设计原则：零硬依赖。</b>只用原版控件（{@link ButtonWidget} / {@link TextFieldWidget}），
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

	private static final int ROWS = 9;
	private static final int ROW_START = 74;

	private static final int LABEL_X_OFFSET = -158;
	private static final int FIELD_X_OFFSET = -5;
	private static final int FIELD_WIDTH = 168;
	private static final int TEXT_TOP = 76;
	// ---- 设计配色（深色卡片风）----
	private static final int C_BG_BAND   = 0xE6121215; // 顶部/底部带底
	private static final int C_ACCENT    = 0xFF4C8DFF; // 主色
	private static final int C_CARD      = 0xB01E1E22; // 卡片底
	private static final int C_CARD_EDGE = 0x603A3A40; // 卡片描边
	private static final int C_DIVIDER   = 0xFF2A2A32; // 分隔线
	private static final int TEXT_LINE_HEIGHT = 11;
	private static final int TEXT_BOTTOM_MARGIN = 36;

	private record Label(Text text, int x, int y) {
	}

	private final Screen parent;
	private final CloudDiscConfig cfg;
	private final List<Label> labels = new ArrayList<>();

	private int section;
	// ---- 自适应布局（笔记本小窗口也不会重叠）----
	private int layLeft;
	private int layRight;
	private int layFieldX;
	private int layFieldW;
	private int layRowStep = 18;
	// 自绘胶囊开关：记录"哪一行是开关"及其读写方式
	private final java.util.Map<Integer, java.util.function.BooleanSupplier> toggleGetters = new java.util.LinkedHashMap<>();
	private final java.util.Map<Integer, java.util.function.Consumer<Boolean>> toggleSetters = new java.util.LinkedHashMap<>();
	private static final int CAPSULE_W = 52;
	private static final int CAPSULE_H = 16;

	private void computeLayout() {
		int pad = 14;
		layLeft = pad;
		layRight = Math.max(pad + 120, this.width - pad);
		int avail = layRight - layLeft;
		// 控件列：只占右侧 40%，这样输入框/开关靠右，与底部按钮对齐成一条"控件列"
		layFieldW = Math.max(76, Math.min(FIELD_WIDTH, (int) (avail * 0.40)));
		layFieldX = layRight - layFieldW;
		// 行距随窗口高度自适应：保证 9 行都在底部按钮带之上（按钮带从 height-40 开始）
		layRowStep = Math.max(12, Math.min(18, (this.height - 118) / ROWS));
	}
	private int scroll;
	private List<String> textLines = List.of();

	public CloudDiscConfigScreen(Screen parent) {
		super(Text.literal("CloudDisc 云唱片"));
		this.parent = parent;
		CloudDiscConfig c = CloudDiscClient.config();
		this.cfg = c != null ? c : CloudDiscConfig.get();
	}

	@Override
	protected void init() {
		computeLayout();
		toggleGetters.clear();
		toggleSetters.clear();
		computeLayout();
		clearChildren();
		labels.clear();

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
		}

		int y = this.height - 28;
		// 底部按钮与"控件列"对齐（左边缘 = 输入框左边缘），4 个等分这条列
		int bw = Math.max(36, (layFieldW - 3 * 4) / 4);
		int bx = layFieldX;
		addDrawableChild(ButtonWidget.builder(Text.literal("◀ 上一节"), b -> {
			section = (section + SECTION_COUNT - 1) % SECTION_COUNT;
			scroll = 0;
			clearAndInit();
		}).dimensions(bx, y, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("下一节 ▶"), b -> {
			section = (section + 1) % SECTION_COUNT;
			scroll = 0;
			clearAndInit();
		}).dimensions(bx + bw + 4, y, bw, 20).build());

		ButtonWidget save = ButtonWidget.builder(Text.literal("保存"), b -> {
			cfg.save();
			CloudDiscClient.LOGGER.info("[CloudDisc] 配置已保存到磁盘");
			close();
		}).dimensions(bx + 2 * (bw + 4), y, bw, 20).build();
		save.visible = !textSection; // 只读页没有可保存的东西
		addDrawableChild(save);

		addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> close())
				.dimensions(bx + 3 * (bw + 4), y, bw, 20).build());
	}

	// ------------------------------------------------------------ 第 1 节：播放

	private void pagePlayback() {
		row(0, "唱片改名前缀", textField(0, cfg.discNamePrefix, v -> cfg.discNamePrefix = v));
		row(1, "默认音源", cycleString(1, new String[] { "163", "local", "url" },
				() -> cfg.defaultProvider, v -> cfg.defaultProvider = v));
		row(2, "总音量（1.0=原样，4.0≈原版）", cycleFloat(2, new float[] { 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f },
				() -> cfg.jukeboxVolume, v -> cfg.jukeboxVolume = (float) v));
		row(3, "输出余量（越小越不易削顶）", cycleFloat(3, new float[] { 0.5f, 0.75f, 1.0f, 1.25f, 1.5f },
				() -> cfg.outputGain, v -> cfg.outputGain = (float) v));
		row(4, "统一开始刻提前量（刻，20=1 秒）", cycleInt(4, new int[] { 0, 10, 20, 40, 60, 100 },
				() -> cfg.startLeadTicks, v -> cfg.startLeadTicks = v));
		row(5, "预缓冲（毫秒）", cycleInt(5, new int[] { 500, 1000, 1500, 2000, 3000, 5000 },
				() -> cfg.prebufferMs, v -> cfg.prebufferMs = v));
		row(6, "本地音乐目录（⚠重启生效）", textField(6, cfg.localMusicDir, v -> cfg.localMusicDir = v));
		row(7, "缓存上限 MB（0=不限；只占本机）", cycleInt(7, new int[] { 128, 256, 512, 1024, 2048, 0 },
				() -> cfg.cacheMaxMb, v -> cfg.cacheMaxMb = v));
	}

	// ------------------------------------------------------- 第 2 节：物理声效

	/**
	 * 物理声效页（纯客户端：只作用于我们自己的那条声源，原版唱片与其它声音完全不受影响）。
	 *
	 * <p>三组开关对应计划里的"总开关 / 强度 / 精度"，另外几个是调参与排障用的：
	 * 遮挡陡度 k、漏音通路数、严格遮挡、方向性、调试日志。
	 */
	private void pagePhysics() {
		row(0, "物理声效总开关（关=干净直通，用于对比）", toggle(0, () -> cfg.physicsSound, v -> {
			cfg.physicsSound = v;
			dev.clouddisc.audio.Acoustics.setEnabled(v);
		}));
		row(1, "强度：0=关效果 1=默认 2=最激进（同时调闷度+余响）",
				cycleFloat(1, new float[] {0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f},
						() -> cfg.physicsSoundLevel, v -> cfg.physicsSoundLevel = (float) v));
		row(2, "隔墙闷度 k（越大越闷，4.5=默认）",
				cycleFloat(2, new float[] {2.0f, 3.0f, 4.0f, 4.5f, 5.0f, 6.0f, 7.0f, 9.0f},
						() -> cfg.physicsAbsorption, v -> cfg.physicsAbsorption = (float) v));
		row(3, "漏音通路数（几条缝才明显透声，3=默认）",
				cycleInt(3, new int[] {1, 2, 3, 4, 5, 6, 8}, () -> cfg.physicsOcclusionPaths,
						v -> cfg.physicsOcclusionPaths = v));
		row(4, "精度·混响射线数（越大越准越贵）", cycleInt(4, new int[] {16, 24, 32, 48, 64},
				() -> cfg.physicsRays, v -> cfg.physicsRays = v));
		row(5, "严格遮挡（开=墙上小缝也算墙）", toggle(5, () -> cfg.physicsStrictOcclusion, v -> cfg.physicsStrictOcclusion = v));
		row(6, "方向性（声音从拐角绕过来）", toggle(6, () -> cfg.physicsSoundDirection, v -> cfg.physicsSoundDirection = v));
		row(7, "调试日志 + 材质探针（每轮打印命中方块）", toggle(7, () -> cfg.physicsSoundDebug, v -> cfg.physicsSoundDebug = v));
		// 第 9 行是只读状态：EFX 到底可不可用（排障第一眼看这个）
		int y = ROW_START + 8 * layRowStep + 6;
		String efx = dev.clouddisc.audio.EfxEngine.isAvailable()
				? "EFX 可用（" + dev.clouddisc.audio.EfxEngine.bands() + " 段混响）"
				: "EFX 不可用 → 已回退自研 DSP：" + dev.clouddisc.audio.EfxEngine.status();
		labels.add(new Label(Text.literal("状态: " + efx), (layLeft + 6), y));
	}

	// ------------------------------------------------------- 第 3 节：网络与音源

	private void pageNetwork() {
		row(0, "UDP 端口（⚠重启生效；双开请不同）", textField(0, String.valueOf(cfg.udpPort), v -> {
			try {
				cfg.udpPort = Integer.parseInt(v.trim());
			} catch (NumberFormatException ignored) {
				// 输入到一半不是数字，先忽略
			}
		}));
		row(1, "局域网自动发现", toggle(1, () -> cfg.enableLanDiscovery, v -> cfg.enableLanDiscovery = v));
		row(2, "启用聊天兜底通道", toggle(2, () -> cfg.enableChatRelay, v -> cfg.enableChatRelay = v));
		row(3, "聊天仅作兜底（中继/UDP 可用时静音）", toggle(3, () -> cfg.chatRelayOnlyWhenNoUdp, v -> cfg.chatRelayOnlyWhenNoUdp = v));
		row(4, "网易云：识别分享链接/歌曲 id", toggle(4, () -> cfg.neteaseEnabled, v -> cfg.neteaseEnabled = v));
		row(5, "网易云：外链模板（{id} 占位）", textField(5, cfg.neteaseUrlTemplate, v -> cfg.neteaseUrlTemplate = v));
		row(6, "自建解析服务（可选，最可靠）", textField(6, cfg.neteaseEndpoint, v -> cfg.neteaseEndpoint = v));
		row(7, "服务端中继（服主装了同一个 jar 才生效）", toggle(7, () -> cfg.enableServerRelay, v -> cfg.enableServerRelay = v));
		row(8, "解析服务令牌（自动作为 X-Token 发送）", textField(8, tokenOf(cfg), v -> setToken(cfg, v)));
	}

	// ------------------------------------------------------------ 控件构造

	private void row(int index, String label, ClickableWidget widget) {
		int y = ROW_START + index * layRowStep;
		int maxLabelW = Math.max(24, layFieldX - 10 - (layLeft + 6));
		String shown = this.textRenderer.trimToWidth(label, maxLabelW);
		labels.add(new Label(Text.literal(shown), layLeft + 6, y + 6));
		addDrawableChild(widget);
	}

	private int rowY(int index) {
		return ROW_START + index * layRowStep;
	}

	private TextFieldWidget textField(int index, String initial, Consumer<String> set) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, layFieldX, rowY(index),
				FIELD_WIDTH, 20, Text.literal(""));
		field.setMaxLength(200);
		field.setText(initial == null ? "" : initial);
		field.setChangedListener(set);
		return field;
	}

	private ButtonWidget toggle(int index, BooleanSupplier get, Consumer<Boolean> set) {
		// 登记这一行是开关；真正的样子由 render() 自绘胶囊，点击由 mouseClicked 处理。
		// 保留一个不可见按钮占位（不参与绘制与点击），避免 row() 里 addDrawableChild 拿到 null。
		toggleGetters.put(index, get);
		toggleSetters.put(index, set);
		ButtonWidget placeholder = ButtonWidget.builder(Text.literal(""), b -> {
		}).dimensions(layFieldX, rowY(index), layFieldW, 20).build();
		placeholder.visible = false;
		return placeholder;
	}

	private ButtonWidget cycleInt(int index, int[] values, IntSupplier get, IntConsumer set) {
		int cur = 0;
		for (int i = 0; i < values.length; i++) {
			if (values[i] == get.getAsInt()) {
				cur = i;
			}
		}
		final int[] idx = { cur };
		return ButtonWidget.builder(Text.literal(String.valueOf(values[idx[0]])), b -> {
			idx[0] = (idx[0] + 1) % values.length;
			set.accept(values[idx[0]]);
			b.setMessage(Text.literal(String.valueOf(values[idx[0]])));
		}).dimensions(layFieldX, rowY(index), layFieldW, 20).build();
	}

	private ButtonWidget cycleFloat(int index, float[] values, DoubleSupplier get, DoubleConsumer set) {
		int cur = 0;
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(values[i] - get.getAsDouble()) < 0.001) {
				cur = i;
			}
		}
		final int[] idx = { cur };
		return ButtonWidget.builder(Text.literal(fmt(values[idx[0]])), b -> {
			idx[0] = (idx[0] + 1) % values.length;
			set.accept(values[idx[0]]);
			b.setMessage(Text.literal(fmt(values[idx[0]])));
		}).dimensions(layFieldX, rowY(index), layFieldW, 20).build();
	}

	private ButtonWidget cycleString(int index, String[] values, Supplier<String> get, Consumer<String> set) {
		int cur = 0;
		for (int i = 0; i < values.length; i++) {
			if (values[i].equalsIgnoreCase(get.get())) {
				cur = i;
			}
		}
		final int[] idx = { cur };
		return ButtonWidget.builder(Text.literal(values[idx[0]]), b -> {
			idx[0] = (idx[0] + 1) % values.length;
			set.accept(values[idx[0]]);
			b.setMessage(Text.literal(values[idx[0]]));
		}).dimensions(layFieldX, rowY(index), layFieldW, 20).build();
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

	/** 自己按像素宽度折行：只用 textRenderer.getWidth，避免依赖 OrderedText 那套 API。 */
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
				if (line.length() > 0 && this.textRenderer.getWidth(line.toString() + c) > maxWidth) {
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

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 胶囊开关：点一下就切换（0.12.23 起不再是原版按钮）
		if (button == 0 && section < SECTION_GUIDE && !toggleGetters.isEmpty()) {
			int cw = Math.min(CAPSULE_W, Math.max(28, layFieldW - 8));
			int cxp = layFieldX + layFieldW - cw;
			for (java.util.Map.Entry<Integer, java.util.function.BooleanSupplier> e : toggleGetters.entrySet()) {
				int ry = rowY(e.getKey());
				int cy = ry + (20 - CAPSULE_H) / 2;
				if (mouseX >= cxp && mouseX < cxp + cw && mouseY >= cy && mouseY < cy + CAPSULE_H) {
					java.util.function.Consumer<Boolean> setter = toggleSetters.get(e.getKey());
					if (setter != null) {
						try {
							setter.accept(!e.getValue().getAsBoolean());
						} catch (Throwable ignored) {
						}
					}
					return true;
				}
			}
		}
		// 顶部标签栏：点一下切分区（与底部 ◀/▶ 等效）
		if (button == 0 && mouseY >= 52 && mouseY < 70) {
			int tabH = 18, gap = 6;
			int avail = layRight - layLeft;
			int[] w = new int[SECTION_COUNT];
			for (int i = 0; i < SECTION_COUNT; i++) {
				w[i] = Math.max(24, (avail - gap * (SECTION_COUNT - 1)) / SECTION_COUNT);
			}
			int x = layLeft;
			for (int i = 0; i < SECTION_COUNT; i++) {
				if (mouseX >= x && mouseX < x + w[i]) {
					if (section != i) {
						section = i;
						scroll = 0;
						clearAndInit();
					}
					return true;
				}
				x += w[i] + gap;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (section >= SECTION_GUIDE) {
			scroll -= (int) Math.signum(amount) * 3;
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (section >= SECTION_GUIDE) {
			int page = linesPerPage();
			switch (keyCode) {
				case GLFW.GLFW_KEY_UP -> scroll -= 1;
				case GLFW.GLFW_KEY_DOWN -> scroll += 1;
				case GLFW.GLFW_KEY_PAGE_UP -> scroll -= page;
				case GLFW.GLFW_KEY_PAGE_DOWN -> scroll += page;
				case GLFW.GLFW_KEY_HOME -> scroll = 0;
				case GLFW.GLFW_KEY_END -> scroll = maxScroll();
				default -> {
					return super.keyPressed(keyCode, scanCode, modifiers);
				}
			}
			clampScroll();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ------------------------------------------------------------ 渲染

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// ---------------- 设计层（只画装饰，不碰任何功能） ----------------
		int cx = this.width / 2;
		// 顶部标题带 + 主色细线
		context.fill(0, 0, this.width, 46, C_BG_BAND);
		context.fill(0, 45, this.width, 47, C_ACCENT);
		// 内容区：每行一张卡片（左侧一条主色/灰色竖条做层次）
		if (section < SECTION_GUIDE) {
			int left = layLeft;
			int right = layRight;
			for (int i = 0; i < ROWS; i++) {
				int y = ROW_START + i * layRowStep - 3;
				int bottom = y + layRowStep - 2;
				if (bottom > this.height - 44) {
					break;
				}
				context.fill(left, y, right, bottom, C_CARD);
				context.fill(left, y, right, y + 1, C_CARD_EDGE);
				context.fill(left, bottom - 1, right, bottom, C_CARD_EDGE);
				context.fill(left, y, left + 2, bottom, i % 2 == 0 ? C_ACCENT : 0x604C8DFF);
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
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(SECTION_NAMES[i]),
						x + w[i] / 2, y + 5, tc);
				x += w[i] + gap;
			}
		}
		// ---------------- 自绘胶囊开关（开=绿、关=灰；右侧对齐到控件列） ----------------
		if (section < SECTION_GUIDE && !toggleGetters.isEmpty()) {
			int cw = Math.min(CAPSULE_W, Math.max(28, layFieldW - 8));
			int ch = CAPSULE_H;
			int cxp = layFieldX + layFieldW - cw;
			for (java.util.Map.Entry<Integer, java.util.function.BooleanSupplier> e : toggleGetters.entrySet()) {
				int ry = rowY(e.getKey());
				int cy = ry + (20 - ch) / 2;
				boolean on = false;
				try {
					on = e.getValue().getAsBoolean();
				} catch (Throwable ignored) {
				}
				int track = on ? 0xFF2E7D46 : 0xFF3A3A40;
				int knob = on ? 0xFF58C46A : 0xFF8A8A95;
				// 用三层 fill 模拟圆角胶囊（上下各内缩 2px、左右各内缩 1px）
				context.fill(cxp + 2, cy, cxp + cw - 2, cy + ch, track);
				context.fill(cxp + 1, cy + 1, cxp + cw - 1, cy + ch - 1, track);
				context.fill(cxp, cy + 3, cxp + cw, cy + ch - 3, track);
				// 圆点：开在右、关在左
				int kx = on ? cxp + cw - ch + 2 : cxp + 2;
				context.fill(kx, cy + 2, kx + ch - 4, cy + ch - 2, knob);
				context.fill(kx + 1, cy + 1, kx + ch - 5, cy + ch - 1, knob);
			}
		}
		// ---------------- 装饰到此为止，下面全是原有绘制 ----------------
		this.renderBackground(context);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
		// 标语 + 一句话定位
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(CloudDiscClient.SLOGAN),
				this.width / 2, 18, 0xFFC840);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(CloudDiscClient.TAGLINE),
				this.width / 2, 28, 0x909090);
		String header = "第 " + (section + 1) + " / " + SECTION_COUNT + " 节 · " + SECTION_NAMES[section];
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(header), this.width / 2, 39, 0xA0E0A0);

		if (section >= SECTION_GUIDE) {
			int perPage = linesPerPage();
			int end = Math.min(textLines.size(), scroll + perPage);
			int y = TEXT_TOP;
			for (int i = scroll; i < end; i++) {
				String line = textLines.get(i);
				// 小节标题（用 ═ 或 ─ 包起来的行）高亮一下，便于扫读
				int color = line.startsWith("═") || line.startsWith("─") ? 0x9FE08A : 0xE0E0E0;
				context.drawTextWithShadow(this.textRenderer, line, 20, y, color);
				y += TEXT_LINE_HEIGHT;
			}
			String hint = (scroll > 0 || maxScroll() > 0)
					? "滚轮 / ↑↓ / PgUp PgDn 翻阅　（" + (scroll + 1) + " - " + end + " / " + textLines.size() + " 行）"
					: "";
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), this.width / 2, this.height - 40, 0x909090);
		} else {
			for (Label label : labels) {
				context.drawTextWithShadow(this.textRenderer, label.text(), label.x(), label.y(), 0xE0E0E0);
			}
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}
}
