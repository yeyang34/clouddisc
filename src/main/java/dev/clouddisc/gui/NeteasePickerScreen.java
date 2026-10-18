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
import java.util.Locale;
import java.util.Map;

/**
 * 游戏内「网易云选歌」界面。
 *
 * <p><b>一次搜索拿全部类型</b>：单曲 / 声音（播客单集）/ 电台（整档）由服务端一次返回
 * （{@code types=1,2000,1009}），底部那排是<b>本地筛选</b>，切换不发请求——
 * 这样"单曲里没找到还得去电台再搜一遍"的问题就没了。
 *
 * <p>排版沿用配置文件界面那套暗色卡片风格：顶部标题带 + 主色细线、
 * 卡片带描边、选中行左侧主色条、底部操作带。
 */
@Environment(EnvType.CLIENT)
public class NeteasePickerScreen extends Screen {
	/** 选中后的回调。 */
	public interface Pick {
		void onPick(long id, String name, boolean voice);
	}

	// ---------------- 配色（与 CloudDiscConfigScreen 一致） ----------------
	private static final int C_BG_BAND = 0xE6121215;
	private static final int C_ACCENT = 0xFF4C8DFF;
	private static final int C_CARD = 0xB01E1E22;
	private static final int C_CARD_EDGE = 0x603A3A40;
	private static final int C_SEL = 0x664C8DFF;
	private static final int C_TEXT = 0xFFE8E8F0;
	private static final int C_DIM = 0xFF9A9AA8;
	private static final int C_WARN = 0xFFFFC46B;

	private static final String[] FILTERS = {"全部", "单曲", "声音", "电台"};

	private final Screen parent;
	private final Pick onPick;
	private final CloudDiscConfig cfg;

	private int filter = 0;
	private EditBox searchBox;
	private Button confirmBtn;

	private List<NeteaseSearch.Item> all = List.of();
	private List<NeteaseSearch.Item> view = List.of();
	private int selected = -1;
	private int scroll = 0;
	private String status = "输入歌名 / 歌手 / 播客名，回车或点【搜索】";
	private boolean loading = false;
	private long seq = 0L;
	private String keyword = "";

	private final Map<Long, List<String>> lyricCache = new HashMap<>();
	private List<String> lyricLines = List.of();
	private int lyricScroll = 0;
	private long lyricShownFor = -1L;

	// ---------------- 自适应布局 ----------------
	private int headerH = 40;
	private int searchY = 48;
	private int chipsY = 72;
	private int chipsH = 16;
	private int bodyY = 94;
	private int listX = 14;
	private int listW = 300;
	private int detailX = 320;
	private int detailW = 200;
	private int bodyH = 150;
	private int rowH = 15;
	private int visibleRows = 9;

	public NeteasePickerScreen(Screen parent, Pick onPick) {
		super(Component.literal("网易云选歌"));
		this.parent = parent;
		this.onPick = onPick;
		CloudDiscConfig c = CloudDiscClient.config();
		this.cfg = c != null ? c : CloudDiscConfig.get();
	}

	private void computeLayout() {
		int pad = 14;
		int avail = Math.max(280, this.width - pad * 2);
		headerH = 40;
		searchY = 48;
		chipsY = 72;
		chipsH = 16;
		bodyY = 94;
		int bottom = 40;
		bodyH = Math.max(54, this.height - bodyY - bottom - 6);
		listX = pad;
		listW = Math.max(140, (int) (avail * 0.58));
		detailX = listX + listW + 8;
		detailW = Math.max(90, this.width - pad - detailX);
		rowH = 15;
		visibleRows = Math.max(1, bodyH / rowH);
	}

	@Override
	protected void init() {
		computeLayout();
		clearWidgets();
		int btnW = 56;
		int fieldW = Math.max(90, Math.min(listW - btnW - 8, 240));
		searchBox = new EditBox(this.font, listX, searchY, fieldW, 18, Component.literal(""));
		searchBox.setMaxLength(60);
		searchBox.setValue(keyword);
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal("搜索"), b -> doSearch())
				.bounds(listX + fieldW + 6, searchY, btnW, 18).build());

		int bw = 72;
		int by = this.height - 28;
		confirmBtn = Button.builder(Component.literal("确认"), b -> confirm())
				.bounds(this.width - 14 - (bw * 2 + 6), by, bw, 20).build();
		confirmBtn.visible = selected >= 0 && selected < view.size();
		addRenderableWidget(confirmBtn);
		addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
				.bounds(this.width - 14 - bw, by, bw, 20).build());
	}

	// ---------------------------------------------------------------- 搜索

	private void doSearch() {
		if (searchBox != null) {
			keyword = searchBox.getValue();
		}
		loading = true;
		status = "搜索中…";
		all = List.of();
		view = List.of();
		selected = -1;
		scroll = 0;
		lyricLines = List.of();
		lyricScroll = 0;
		lyricShownFor = -1L;
		rebuildConfirm();
		final long mySeq = ++seq;
		NeteaseSearch.searchAll(cfg, keyword, 20).thenAccept(r -> {
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
					all = r.items();
					applyFilter(0);
					status = "共 " + all.size() + " 条 · 回车确认 / 双击选择";
				} else {
					all = List.of();
					view = List.of();
					selected = -1;
					status = r.error();
				}
				rebuildConfirm();
			});
		});
	}

	private void applyFilter(int f) {
		filter = Math.max(0, Math.min(FILTERS.length - 1, f));
		List<NeteaseSearch.Item> v = new ArrayList<>();
		for (NeteaseSearch.Item it : all) {
			if (filter == 0) {
				v.add(it);
			} else if (filter == 1 && !it.isVoice() && !it.isRadio()) {
				v.add(it);
			} else if (filter == 2 && it.isVoice()) {
				v.add(it);
			} else if (filter == 3 && it.isRadio()) {
				v.add(it);
			}
		}
		view = v;
		selected = v.isEmpty() ? -1 : 0;
		scroll = 0;
		lyricLines = List.of();
		lyricScroll = 0;
		lyricShownFor = -1L;
		if (selected >= 0) {
			showDetailFor(0);
		}
		rebuildConfirm();
	}

	private void rebuildConfirm() {
		if (confirmBtn != null) {
			confirmBtn.visible = selected >= 0 && selected < view.size();
		}
	}

	private void confirm() {
		if (selected < 0 || selected >= view.size()) {
			return;
		}
		NeteaseSearch.Item it = view.get(selected);
		if (onPick != null) {
			onPick.onPick(it.id(), it.display(), it.isVoice() || it.isRadio());
		}
		onClose();
	}

	// ---------------------------------------------------------------- 详情

	private void showDetailFor(int idx) {
		if (idx < 0 || idx >= view.size()) {
			return;
		}
		NeteaseSearch.Item it = view.get(idx);
		if (it.isRadio()) {
			lyricLines = List.of(
					"【电台 · 整档】",
					"",
					it.name(),
					it.artist().isBlank() ? "主播：（未知）" : ("主播：" + it.artist()),
					"",
					"写入：@" + it.id(),
					"播放时从这档电台的【第一期】开始",
					"",
					"想听某一集，切到【声音】筛选里找那一集",
					"");
			lyricScroll = 0;
			lyricShownFor = it.id();
			return;
		}
		if (it.isVoice()) {
			lyricLines = List.of(
					"【声音 · 播客/电台单集】",
					"",
					it.name(),
					it.artist().isBlank() ? "主播：（未知）" : ("主播：" + it.artist()),
					"",
					"写入：@" + it.id(),
					"游戏内可直接播放",
					"",
					"提示：播客通常没有歌词，这里是节目信息",
					"");
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
		List<String> head = new ArrayList<>();
		head.add(it.name());
		head.add(it.artist().isBlank() ? "（未知歌手）" : it.artist());
		if (!it.album().isBlank()) {
			head.add("专辑：" + it.album());
		}
		head.add("写入：@" + it.id());
		head.add("");
		head.add("—— 歌词 ——");
		head.add("");
		lyricLines = head;
		lyricScroll = 0;
		lyricShownFor = it.id();
		final long id = it.id();
		final List<String> headCopy = new ArrayList<>(head);
		Thread t = new Thread(() -> {
			List<String> lines = fetchLyric(id);
			List<String> merged = new ArrayList<>(headCopy);
			merged.addAll(lines);
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.execute(() -> {
					lyricCache.put(id, merged);
					if (lyricShownFor == id) {
						lyricLines = merged;
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

	/** 直连网易云歌词接口（实测明文 JSON，无需签名）。 */
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
			JsonObject o = JsonParser.parseString(new String(resp.body(), StandardCharsets.UTF_8))
					.getAsJsonObject();
			String raw = "";
			if (o.has("lrc") && o.get("lrc").isJsonObject()) {
				JsonObject lrc = o.getAsJsonObject("lrc");
				if (lrc.has("lyric") && !lrc.get("lyric").isJsonNull()) {
					raw = lrc.get("lyric").getAsString();
				}
			}
			if (raw.isBlank()) {
				return List.of("（这首歌没有歌词，不影响播放）");
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

	private void draw(GuiGraphicsExtractor ctx, String text, int x, int y) {
		try {
			ctx.textRenderer().accept(x, y, Component.literal(text == null ? "" : text));
		} catch (Throwable ignored) {
		}
	}

	private void drawC(GuiGraphicsExtractor ctx, String text, int cx, int y) {
		try {
			ctx.textRenderer().accept(TextAlignment.CENTER, cx, y,
					Component.literal(text == null ? "" : text));
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
			return "";
		}
		long s = ms / 1000L;
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	/** 按字符数裁宽度（界面里只用等宽近似 ✓ 够用）。 */
	private static String fit(String s, int px) {
		return clip(s, Math.max(4, px / 6));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0x40000000);
		// 顶部标题带
		context.fill(0, 0, this.width, headerH, C_BG_BAND);
		context.fill(0, headerH - 2, this.width, headerH - 1, C_ACCENT);
		draw(context, "☁ 网易云选歌", listX, 12);
		draw(context, fit(status, Math.max(120, this.width - 260)), this.width - 14 - Math.min(420, this.width / 2), 12);

		// 本地筛选 chips
		int x = listX;
		for (int i = 0; i < FILTERS.length; i++) {
			int count = 0;
			for (NeteaseSearch.Item it : all) {
				if (i == 0
						|| (i == 1 && !it.isVoice() && !it.isRadio())
						|| (i == 2 && it.isVoice())
						|| (i == 3 && it.isRadio())) {
					count++;
				}
			}
			String label = FILTERS[i] + (i == 0 ? "" : " " + count);
			int cw = Math.max(44, label.length() * 6 + 14);
			boolean on = i == filter;
			context.fill(x, chipsY, x + cw, chipsY + chipsH, on ? C_SEL : C_CARD);
			context.fill(x, chipsY, x + cw, chipsY + 1, C_CARD_EDGE);
			context.fill(x, chipsY + chipsH - 2, x + cw, chipsY + chipsH, on ? C_ACCENT : C_CARD_EDGE);
			draw(context, label, x + 7, chipsY + 4);
			x += cw + 6;
		}

		// 结果卡片
		context.fill(listX - 3, bodyY - 3, listX + listW + 3, bodyY + bodyH + 3, C_CARD);
		context.fill(listX - 3, bodyY - 3, listX + listW + 3, bodyY - 2, C_CARD_EDGE);
		context.fill(listX - 3, bodyY + bodyH + 2, listX + listW + 3, bodyY + bodyH + 3, C_CARD_EDGE);
		if (loading) {
			draw(context, "搜索中…", listX + 6, bodyY + 6);
		} else if (view.isEmpty()) {
			draw(context, all.isEmpty() ? "（还没有结果，先在上面搜索）" : "（这个筛选下没有结果）",
					listX + 6, bodyY + 6);
		}
		for (int r = 0; r < visibleRows; r++) {
			int idx = scroll + r;
			if (idx >= view.size()) {
				break;
			}
			int y = bodyY + r * rowH;
			boolean sel = idx == selected;
			NeteaseSearch.Item it = view.get(idx);
			if (sel) {
				context.fill(listX - 3, y - 1, listX + listW + 3, y + rowH - 2, C_SEL);
				context.fill(listX - 3, y - 1, listX - 1, y + rowH - 2, C_ACCENT);
			}
			// 类型标签（固定宽度列 ✓ 排版整齐）
			String tag = "[" + it.kindLabel() + "]";
			draw(context, tag, listX + 4, y);
			int tagW = tag.length() * 6 + 6;
			// 名字 + 歌手
			String dur = mmss(it.durationMs());
			int durW = dur.isEmpty() ? 0 : 42;
			int nameW = listW - tagW - durW - 14;
			String line = it.name();
			if (!it.artist().isBlank()) {
				line = line + "  ·  " + it.artist();
			}
			draw(context, fit(line, nameW), listX + 4 + tagW, y);
			if (!dur.isEmpty()) {
				drawC(context, dur, listX + listW - 6, y);
			}
		}
		if (view.size() > visibleRows) {
			int barH = Math.max(10, bodyH * visibleRows / view.size());
			int barY = bodyY + (bodyH - barH) * scroll / Math.max(1, view.size() - visibleRows);
			context.fill(listX + listW + 1, barY, listX + listW + 3, barY + barH, C_ACCENT);
		}

		// 详情卡片
		context.fill(detailX - 3, bodyY - 3, detailX + detailW + 3, bodyY + bodyH + 3, C_CARD);
		context.fill(detailX - 3, bodyY - 3, detailX + detailW + 3, bodyY - 2, C_CARD_EDGE);
		context.fill(detailX - 3, bodyY + bodyH + 2, detailX + detailW + 3, bodyY + bodyH + 3, C_CARD_EDGE);
		int chars = Math.max(6, (detailW - 8) / 6);
		int lines = Math.max(1, bodyH / 11);
		for (int i = 0; i < lines; i++) {
			int li = lyricScroll + i;
			if (li >= lyricLines.size()) {
				break;
			}
			draw(context, clip(lyricLines.get(li), chars), detailX + 3, bodyY + 2 + i * 11);
		}
		if (lyricLines.size() > lines) {
			drawC(context, (lyricScroll + 1) + "/" + Math.max(1, lyricLines.size() - lines + 1),
					detailX + detailW - 16, bodyY + bodyH - 11);
		}

		// 底部操作带
		context.fill(0, this.height - 40, this.width, this.height, C_BG_BAND);
		context.fill(0, this.height - 41, this.width, this.height - 40, C_CARD_EDGE);
		draw(context, "↑↓ 选择 · Enter 确认 · Tab 换筛选 · Esc 取消", listX, this.height - 24);
		if (selected >= 0 && selected < view.size()) {
			NeteaseSearch.Item it = view.get(selected);
			draw(context, fit("将写入：@" + it.id(), 220), listX, this.height - 13);
		}
		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	// ---------------------------------------------------------------- 输入

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		try {
			boolean inDetail = mouseX >= detailX - 3 && mouseX <= detailX + detailW + 3
					&& mouseY >= bodyY - 3 && mouseY <= bodyY + bodyH + 3;
			if (inDetail) {
				int lines = Math.max(1, bodyH / 11);
				int max = Math.max(0, lyricLines.size() - lines);
				lyricScroll = scrollY < 0 ? Math.min(max, lyricScroll + 1) : Math.max(0, lyricScroll - 1);
				return true;
			}
			int max = Math.max(0, view.size() - visibleRows);
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
				if (selected >= 0 && selected < view.size()) {
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
					select(view.size() - 1);
					return true;
				}
				if (k == GLFW.GLFW_KEY_TAB) {
					applyFilter((filter + 1) % FILTERS.length);
					return true;
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 选歌界面按键异常: {}", t.toString());
		}
		return super.keyPressed(event);
	}

	private void move(int d) {
		if (view.isEmpty()) {
			return;
		}
		select(Math.max(0, Math.min(view.size() - 1, selected + d)));
	}

	private void select(int idx) {
		if (view.isEmpty()) {
			return;
		}
		int n = Math.max(0, Math.min(view.size() - 1, idx));
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
			showDetailFor(selected);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		try {
			double mx = event.x();
			double my = event.y();
			// 筛选 chips
			int x = listX;
			for (int i = 0; i < FILTERS.length; i++) {
				int count = 0;
				for (NeteaseSearch.Item it : all) {
					if (i == 0
							|| (i == 1 && !it.isVoice() && !it.isRadio())
							|| (i == 2 && it.isVoice())
							|| (i == 3 && it.isRadio())) {
						count++;
					}
				}
				String label = FILTERS[i] + (i == 0 ? "" : " " + count);
				int cw = Math.max(44, label.length() * 6 + 14);
				if (mx >= x && mx < x + cw && my >= chipsY && my < chipsY + chipsH) {
					applyFilter(i);
					return true;
				}
				x += cw + 6;
			}
			// 列表行
			if (mx >= listX - 3 && mx <= listX + listW + 3 && my >= bodyY && my <= bodyY + bodyH) {
				int r = (int) ((my - bodyY) / rowH);
				int idx = scroll + r;
				if (idx >= 0 && idx < view.size()) {
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
