package dev.clouddisc.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.music.NeteaseSearch;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏内「网易云选歌」界面 ✓。
 *
 * <p>为什么不是真浏览器 ✗：嵌 Chromium 需要上百 MB 的 natives，26.2 上没有可用构建 ✓
 * 于是用原生界面做同一件事 ✓ 数据来自我们自己的解析服务 ✓（它背后是 NeteaseCloudMusicApi ✓）
 *
 * <p>功能：
 * <ul>
 *   <li>两个页签：<b>单曲</b> / <b>声音</b>（播客 / 电台节目 ✓）</li>
 *   <li>结果列表：滚轮 / ↑↓ / PgUp·PgDn / Home·End 都能翻 ✓ 鼠标点击直接选 ✓</li>
 *   <li>右侧详情：单曲显示<b>歌词</b>（客户端直连网易云歌词接口 ✓ 实测是明文 ✓）；
 *       声音显示主播 / 电台 / 时长 ✓</li>
 *   <li>底部 [确认] [取消]：确认把 id 回传给调用方 ✓（铁砧那边写成 {@code @<id>} ✓）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class NeteasePickerScreen extends Screen {
	/** 选中后的回调 ✓ voice 表示它是不是"声音"（播客）✓。 */
	public interface Pick {
		void onPick(long id, String name, boolean voice);
	}

	/** 三个页签 ✓ 第三个是"整档电台"（type=1009 ✓ 选中后写的是电台 id ✓ 播放时从第一期开始 ✓）。 */
	private static final int TYPE_RADIO = 1009;
	private static final int[] TYPES = {NeteaseSearch.TYPE_SONG, NeteaseSearch.TYPE_VOICE, TYPE_RADIO};
	private static final String[] TAB_NAMES = {"单曲", "声音(播客单集)", "电台(整档)"};

	private static final int C_BG_BAND = 0xE6121215;
	private static final int C_ACCENT = 0xFF4C8DFF;
	private static final int C_CARD = 0xB01E1E22;
	private static final int C_CARD_EDGE = 0x603A3A40;
	private static final int C_SEL = 0x804C8DFF;
	private static final int C_TEXT = 0xFFE8E8F0;
	private static final int C_DIM = 0xFF9A9AA8;

	private final Screen parent;
	private final Pick onPick;
	private final CloudDiscConfig cfg;

	private int tab = 0;
	private EditBox searchBox;
	private Button confirmBtn;

	private List<NeteaseSearch.Item> items = List.of();
	private int selected = -1;
	private int scroll = 0;
	private String status = "输入歌名 / 歌手 / 播客名，回车或点【搜索】";
	private boolean loading = false;
	private long seq = 0L;

	private final Map<Long, List<String>> lyricCache = new HashMap<>();
	private List<String> lyricLines = List.of();
	private int lyricScroll = 0;
	private long lyricShownFor = -1L;

	// ---- 自适应布局 ----
	private int tabY = 46;
	private int tabH = 18;
	private int searchY = 70;
	private int listX = 14;
	private int listY = 96;
	private int listW = 200;
	private int listH = 120;
	private int detailX = 220;
	private int detailY = 96;
	private int detailW = 200;
	private int detailH = 120;
	private int rowH = 14;
	private int visibleRows = 8;

	public NeteasePickerScreen(Screen parent, Pick onPick) {
		super(Component.literal("网易云选歌"));
		this.parent = parent;
		this.onPick = onPick;
		CloudDiscConfig c = CloudDiscClient.config();
		this.cfg = c != null ? c : CloudDiscConfig.get();
	}

	private void computeLayout() {
		int pad = 14;
		int avail = Math.max(240, this.width - pad * 2);
		tabY = 46;
		searchY = 70;
		listY = 96;
		int bottomBand = 40;
		listH = Math.max(40, this.height - listY - bottomBand - 6);
		detailH = listH;
		listW = Math.max(120, (int) (avail * 0.46));
		listX = pad;
		detailX = listX + listW + 8;
		detailW = Math.max(100, this.width - pad - detailX);
		detailY = listY;
		rowH = 14;
		visibleRows = Math.max(1, listH / rowH);
	}

	@Override
	protected void init() {
		computeLayout();
		clearWidgets();
		int fieldW = Math.max(80, listW - 60);
		searchBox = new EditBox(this.font, listX, searchY, fieldW, 18, Component.literal(""));
		searchBox.setMaxLength(60);
		searchBox.setValue(lastKeyword());
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal("搜索"), b -> doSearch())
				.bounds(listX + fieldW + 4, searchY, 52, 18).build());

		int bw = 72;
		int by = this.height - 28;
		confirmBtn = Button.builder(Component.literal("确认"), b -> confirm()).bounds(
				this.width - 14 - (bw * 2 + 6), by, bw, 20).build();
		confirmBtn.visible = selected >= 0 && selected < items.size();
		addRenderableWidget(confirmBtn);
		addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
				.bounds(this.width - 14 - bw, by, bw, 20).build());
	}

	private String lastKeyword = "";

	private String lastKeyword() {
		return lastKeyword;
	}

	private void doSearch() {
		if (searchBox != null) {
			lastKeyword = searchBox.getValue();
		}
		loading = true;
		status = "搜索中…";
		items = List.of();
		selected = -1;
		scroll = 0;
		lyricLines = List.of();
		lyricScroll = 0;
		lyricShownFor = -1L;
		rebuildConfirm();
		final long mySeq = ++seq;
		final int type = TYPES[Math.max(0, Math.min(TYPES.length - 1, tab))];
		NeteaseSearch.search(cfg, lastKeyword, type, 20).thenAccept(r -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null) {
				return;
			}
			mc.execute(() -> {
				if (mySeq != seq) {
					return;
				}
				loading = false;
				if (r.ok()) {
					items = r.items();
					selected = items.isEmpty() ? -1 : 0;
					scroll = 0;
					status = "共 " + items.size() + " 条（" + (r.via() == null ? "?" : r.via()) + "）"
							+ (items.isEmpty() ? "" : "  回车确认 / 点选");
					showLyricFor(0);
				} else {
					items = List.of();
					selected = -1;
					status = r.error();
				}
				rebuildConfirm();
			});
		});
	}

	private void rebuildConfirm() {
		if (confirmBtn != null) {
			confirmBtn.visible = selected >= 0 && selected < items.size();
		}
	}

	private void confirm() {
		if (selected < 0 || selected >= items.size()) {
			return;
		}
		NeteaseSearch.Item it = items.get(selected);
		if (onPick != null) {
			onPick.onPick(it.id(), it.display(), it.isVoice());
		}
		onClose();
	}

	// ---------------------------------------------------------------- 歌词

	private void showLyricFor(int idx) {
		if (idx < 0 || idx >= items.size()) {
			return;
		}
		NeteaseSearch.Item it = items.get(idx);
		if ("radio".equals(it.kind())) {
			lyricLines = List.of(
					"【电台 · 整档】",
					"",
					"电台：" + it.name(),
					it.artist().isBlank() ? "主播：（未知）" : ("主播：" + it.artist()),
					"",
					"确认后会写入  @" + it.id(),
					"播放时从这档电台的【第一期】开始 ✓",
					"（解析服务会自动把它落到第一期节目 ✓）",
					"",
					"提示：想听某一集，请切到【声音】页签搜那一集 ✓");
			lyricScroll = 0;
			lyricShownFor = it.id();
			return;
		}
		if (it.isVoice()) {
			lyricLines = List.of(
					"【声音 · 播客/电台节目】",
					"",
					"标题：" + it.name(),
					it.artist().isBlank() ? "主播：（未知）" : ("主播：" + it.artist()),
					it.album().isBlank() ? "" : ("电台：" + it.album()),
					"",
					"确认后会写入  @" + it.id(),
					"游戏内可直接播放 ✓（解析服务会把它落到对应的音频 ✓）",
					"",
					"提示：播客通常没有歌词，这里是节目信息 ✓");
			lyricScroll = 0;
			lyricShownFor = it.id();
			return;
		}
		List<String> cached = lyricCache.get(it.id());
		if (cached != null) {
			lyricLines = cached;
			lyricScroll = 0;
			lyricShownFor = it.id();
			return;
		}
		lyricLines = List.of("（歌词加载中…）");
		lyricScroll = 0;
		lyricShownFor = it.id();
		final long id = it.id();
		Thread t = new Thread(() -> {
			List<String> lines = fetchLyric(id);
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.execute(() -> {
					lyricCache.put(id, lines);
					if (lyricShownFor == id) {
						lyricLines = lines;
						lyricScroll = 0;
					}
				});
			}
		}, "CloudDisc-Lyric");
		t.setDaemon(true);
		t.start();
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	/** 直连网易云歌词接口（实测是明文 JSON ✓ 无需签名 ✓）。 */
	private static List<String> fetchLyric(long id) {
		try {
			HttpRequest req = HttpRequest.newBuilder(
							URI.create("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1"))
					.timeout(Duration.ofSeconds(8))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
					.header("Referer", "https://music.163.com/")
					.GET().build();
			HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (resp.statusCode() / 100 != 2) {
				return List.of("（歌词获取失败：HTTP " + resp.statusCode() + "）");
			}
			String body = new String(resp.body(), StandardCharsets.UTF_8);
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			String raw = "";
			if (o.has("lrc") && o.get("lrc").isJsonObject()) {
				JsonObject lrc = o.getAsJsonObject("lrc");
				if (lrc.has("lyric") && !lrc.get("lyric").isJsonNull()) {
					raw = lrc.get("lyric").getAsString();
				}
			}
			if (raw.isBlank()) {
				return List.of("（这首歌没有歌词 ✓ 不影响播放）");
			}
			List<String> out = new ArrayList<>();
			for (String line : raw.split("\n")) {
				String s = line.replaceAll("\\[[0-9:.]+\\]", "").trim();
				if (!s.isEmpty()) {
					out.add(s);
				}
				if (out.size() >= 400) {
					break;
				}
			}
			return out.isEmpty() ? List.of("（歌词为空）") : out;
		} catch (Throwable t) {
			return List.of("（歌词获取失败：" + t.getMessage() + "）");
		}
	}

	// ---------------------------------------------------------------- 绘制

	private void drawText(GuiGraphicsExtractor ctx, String text, int x, int y, int color) {
		try {
			ctx.textRenderer().accept(x, y, Component.literal(text == null ? "" : text));
		} catch (Throwable ignored) {
		}
	}

	private void drawTextC(GuiGraphicsExtractor ctx, String text, int cx, int y) {
		try {
			ctx.textRenderer().accept(TextAlignment.CENTER, cx, y, Component.literal(text == null ? "" : text));
		} catch (Throwable ignored) {
		}
	}

	private static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
	}

	private static String mmss(long ms) {
		if (ms <= 0) {
			return "--:--";
		}
		long s = ms / 1000L;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0x40000000);
		context.fill(0, 0, this.width, 40, C_BG_BAND);
		context.fill(0, 39, this.width, 41, C_ACCENT);

		// 页签
		int avail = this.width - 28;
		int tw = Math.max(60, (avail - 8) / TAB_NAMES.length);
		for (int i = 0; i < TAB_NAMES.length; i++) {
			int x = 14 + i * (tw + 8);
			boolean on = i == tab;
			context.fill(x, tabY, x + tw, tabY + tabH, on ? C_SEL : C_CARD);
			context.fill(x, tabY, x + tw, tabY + 1, C_CARD_EDGE);
			context.fill(x, tabY + tabH - 2, x + tw, tabY + tabH, on ? C_ACCENT : C_CARD_EDGE);
			drawText(context, TAB_NAMES[i], x + 6, tabY + 5, on ? C_TEXT : C_DIM);
		}
		drawText(context, clip("网易云选歌 · " + status, Math.max(20, (this.width - 28) / 6)),
				this.width - 14 - Math.min(360, (this.width - 28) / 2), 56 - 40 + 30, C_DIM);

		// 结果列表卡片
		context.fill(listX - 3, listY - 3, listX + listW + 3, listY + listH + 3, C_CARD);
		context.fill(listX - 3, listY - 3, listX + listW + 3, listY - 2, C_CARD_EDGE);
		context.fill(listX - 3, listY + listH + 2, listX + listW + 3, listY + listH + 3, C_CARD_EDGE);
		if (loading) {
			drawText(context, "搜索中…", listX + 6, listY + 6, C_DIM);
		} else if (items.isEmpty()) {
			drawText(context, "（没有结果）", listX + 6, listY + 6, C_DIM);
		}
		for (int r = 0; r < visibleRows; r++) {
			int idx = scroll + r;
			if (idx >= items.size()) {
				break;
			}
			int y = listY + r * rowH;
			boolean sel = idx == selected;
			if (sel) {
				context.fill(listX - 3, y - 1, listX + listW + 3, y + rowH - 2, C_SEL);
			}
			NeteaseSearch.Item it = items.get(idx);
			String mark = "radio".equals(it.kind()) ? "📻电台 "
					: (it.isVoice() ? "♪声音 " : "♫ ");
			String right = it.durationMs() > 0 ? mmss(it.durationMs()) : "";
			int rightW = right.isEmpty() ? 0 : (right.length() * 5 + 8);
			drawText(context, clip(mark + it.name(), Math.max(8, (listW - 12 - rightW) / 5)),
					listX + 3, y, sel ? 0xFFFFFFFF : C_TEXT);
			if (!right.isEmpty()) {
				drawText(context, right, listX + listW - rightW, y, C_DIM);
			}
		}
		// 滚动条
		if (items.size() > visibleRows) {
			int barH = Math.max(10, listH * visibleRows / items.size());
			int barY = listY + (listH - barH) * scroll / Math.max(1, items.size() - visibleRows);
			context.fill(listX + listW, barY, listX + listW + 2, barY + barH, C_ACCENT);
		}

		// 详情卡片
		context.fill(detailX - 3, detailY - 3, detailX + detailW + 3, detailY + detailH + 3, C_CARD);
		context.fill(detailX - 3, detailY - 3, detailX + detailW + 3, detailY - 2, C_CARD_EDGE);
		context.fill(detailX - 3, detailY + detailH + 2, detailX + detailW + 3, detailY + detailH + 3, C_CARD_EDGE);
		int chars = Math.max(8, (detailW - 8) / 5);
		int lines = Math.max(1, detailH / 11);
		for (int i = 0; i < lines; i++) {
			int li = lyricScroll + i;
			if (li >= lyricLines.size()) {
				break;
			}
			drawText(context, clip(lyricLines.get(li), chars), detailX + 3, detailY + 2 + i * 11, C_TEXT);
		}
		if (lyricLines.size() > lines) {
			drawText(context, (lyricScroll + 1) + "/" + Math.max(1, lyricLines.size() - lines + 1),
					detailX + detailW - 40, detailY + detailH - 12, C_DIM);
		}

		// 底部带
		context.fill(0, this.height - 40, this.width, this.height, C_BG_BAND);
		context.fill(0, this.height - 41, this.width, this.height - 40, C_CARD_EDGE);
		drawText(context, "选中的 id 会写成  @<id>  ✓  铁砧里拿取唱片即完成", 14, this.height - 24, C_DIM);
		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	// ---------------------------------------------------------------- 输入

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		try {
			boolean inDetail = mouseX >= detailX - 3 && mouseX <= detailX + detailW + 3
					&& mouseY >= detailY - 3 && mouseY <= detailY + detailH + 3;
			if (inDetail) {
				int lines = Math.max(1, detailH / 11);
				int max = Math.max(0, lyricLines.size() - lines);
				lyricScroll = scrollY < 0 ? Math.min(max, lyricScroll + 1) : Math.max(0, lyricScroll - 1);
				return true;
			}
			int max = Math.max(0, items.size() - visibleRows);
			scroll = scrollY < 0 ? Math.min(max, scroll + 1) : Math.max(0, scroll - 1);
			return true;
		} catch (Throwable t) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int k = event.key();
		try {
			if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
				if (selected >= 0 && selected < items.size()) {
					confirm();
				} else {
					doSearch();
				}
				return true;
			}
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				onClose();
				return true;
			}
			if (searchBox == null || !searchBox.isFocused()) {
				if (k == GLFW.GLFW_KEY_DOWN) {
					move(1);
					return true;
				}
				if (k == GLFW.GLFW_KEY_UP) {
					move(-1);
					return true;
				}
				if (k == GLFW.GLFW_KEY_PAGE_DOWN) {
					move(Math.max(1, visibleRows - 1));
					return true;
				}
				if (k == GLFW.GLFW_KEY_PAGE_UP) {
					move(-Math.max(1, visibleRows - 1));
					return true;
				}
				if (k == GLFW.GLFW_KEY_HOME) {
					select(0);
					return true;
				}
				if (k == GLFW.GLFW_KEY_END) {
					select(items.size() - 1);
					return true;
				}
				if (k == GLFW.GLFW_KEY_TAB) {
					tab = (tab + 1) % TAB_NAMES.length;
					doSearch();
					return true;
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 选歌界面按键异常: {}", t.toString());
		}
		return super.keyPressed(event);
	}

	private void move(int d) {
		if (items.isEmpty()) {
			return;
		}
		int n = Math.max(0, Math.min(items.size() - 1, selected + d));
		select(n);
	}

	private void select(int idx) {
		if (items.isEmpty()) {
			return;
		}
		int n = Math.max(0, Math.min(items.size() - 1, idx));
		boolean changed = n != selected;
		selected = n;
		if (selected < scroll) {
			scroll = selected;
		}
		if (selected >= scroll + visibleRows) {
			scroll = selected - visibleRows + 1;
		}
		rebuildConfirm();
		if (changed) {
			showLyricFor(selected);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		try {
			double mx = event.x();
			double my = event.y();
			// 页签
			int avail = this.width - 28;
			int tw = Math.max(60, (avail - 8) / TAB_NAMES.length);
			for (int i = 0; i < TAB_NAMES.length; i++) {
				int x = 14 + i * (tw + 8);
				if (mx >= x && mx < x + tw && my >= tabY && my < tabY + tabH) {
					if (tab != i) {
						tab = i;
						doSearch();
					}
					return true;
				}
			}
			// 列表行
			if (mx >= listX - 3 && mx <= listX + listW + 3 && my >= listY && my <= listY + listH) {
				int r = (int) ((my - listY) / rowH);
				int idx = scroll + r;
				if (idx >= 0 && idx < items.size()) {
					select(idx);
					if (doubleClick) {
						confirm();
					}
					return true;
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 选歌界面点击异常: {}", t.toString());
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			Minecraft.getInstance().setScreenAndShow(this.parent);
		}
	}
}