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
 * CloudDisc 配置界面（4 节：播放 / 网络与音源 / 教程 / 更新日志）。
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
	private static final int SECTION_NETWORK = 1;
	private static final int SECTION_GUIDE = 2;
	private static final int SECTION_CHANGELOG = 3;
	private static final int SECTION_COUNT = 4;
	private static final String[] SECTION_NAMES = {"播放设置", "网络与音源", "使用教程", "更新日志"};

	private static final int ROWS = 7;
	private static final int ROW_START = 48;
	private static final int ROW_STEP = 20;
	private static final int LABEL_X_OFFSET = -158;
	private static final int FIELD_X_OFFSET = -5;
	private static final int FIELD_WIDTH = 168;
	private static final int TEXT_TOP = 50;
	private static final int TEXT_LINE_HEIGHT = 11;
	private static final int TEXT_BOTTOM_MARGIN = 36;

	private record Label(Text text, int x, int y) {
	}

	private final Screen parent;
	private final CloudDiscConfig cfg;
	private final List<Label> labels = new ArrayList<>();

	private int section;
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
			} else {
				pageNetwork();
			}
		}

		int y = this.height - 28;
		addDrawableChild(ButtonWidget.builder(Text.literal("◀ 上一节"), b -> {
			section = (section + SECTION_COUNT - 1) % SECTION_COUNT;
			scroll = 0;
			clearAndInit();
		}).dimensions(this.width / 2 - 155, y, 76, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("下一节 ▶"), b -> {
			section = (section + 1) % SECTION_COUNT;
			scroll = 0;
			clearAndInit();
		}).dimensions(this.width / 2 - 77, y, 76, 20).build());

		ButtonWidget save = ButtonWidget.builder(Text.literal("保存"), b -> {
			cfg.save();
			CloudDiscClient.LOGGER.info("[CloudDisc] 配置已保存到磁盘");
			close();
		}).dimensions(this.width / 2 + 1, y, 76, 20).build();
		save.visible = !textSection; // 只读页没有可保存的东西
		addDrawableChild(save);

		addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> close())
				.dimensions(this.width / 2 + 79, y, 76, 20).build());
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

	// ------------------------------------------------------- 第 2 节：网络与音源

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
	}

	// ------------------------------------------------------------ 控件构造

	private void row(int index, String label, ClickableWidget widget) {
		int y = ROW_START + index * ROW_STEP;
		labels.add(new Label(Text.literal(label), this.width / 2 + LABEL_X_OFFSET, y + 6));
		addDrawableChild(widget);
	}

	private int rowY(int index) {
		return ROW_START + index * ROW_STEP;
	}

	private TextFieldWidget textField(int index, String initial, Consumer<String> set) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, this.width / 2 + FIELD_X_OFFSET, rowY(index),
				FIELD_WIDTH, 20, Text.literal(""));
		field.setMaxLength(200);
		field.setText(initial == null ? "" : initial);
		field.setChangedListener(set);
		return field;
	}

	private ButtonWidget toggle(int index, BooleanSupplier get, Consumer<Boolean> set) {
		return ButtonWidget.builder(Text.literal(get.getAsBoolean() ? "开" : "关"), b -> {
			boolean next = !get.getAsBoolean();
			set.accept(next);
			b.setMessage(Text.literal(next ? "开" : "关"));
		}).dimensions(this.width / 2 + FIELD_X_OFFSET, rowY(index), FIELD_WIDTH, 20).build();
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
		}).dimensions(this.width / 2 + FIELD_X_OFFSET, rowY(index), FIELD_WIDTH, 20).build();
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
		}).dimensions(this.width / 2 + FIELD_X_OFFSET, rowY(index), FIELD_WIDTH, 20).build();
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
		}).dimensions(this.width / 2 + FIELD_X_OFFSET, rowY(index), FIELD_WIDTH, 20).build();
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
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("改完记得点『保存』　·　带 ⚠ 的需重启游戏"), this.width / 2, this.height - 40, 0xA0A0A0);
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
