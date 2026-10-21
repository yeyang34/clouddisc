package dev.clouddisc.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 界面背景：随机二次元壁纸（UApiPro 的 {@code /api/v1/random/image}）。
 *
 * <h2>核心决策：<b>把图缩放到目标尺寸，再 1:1 绘制</b></h2>
 * 这个版本刻意<b>不用</b> UV 缩放那条路。原因：26.2 的 {@code blit} 有两个容易混的重载，实测下来
 * <ul>
 *   <li>{@code blit(id, x1,y1,x2,y2, u0,v0,u1,v1)}：UV 传 {@code 1,1} 得到纯色、传像素值得到条纹，
 *       两种解释都不对，语义始终没对上</li>
 *   <li>{@code blit(pipeline, id, x, y, u, v, w, h, texW, texH)}：1:1 采样，<b>能正确出图</b>，
 *       只是不缩放（这一条被实测确认过）</li>
 * </ul>
 * 所以：把<b>纹理本身</b>缩放到窗口尺寸，让 {@code w == 纹理宽}，1:1 采样画出来就是整张图 ⇒ 结果确定。
 * 代价是窗口尺寸变化时要重建纹理（几百毫秒，防抖 500ms）。
 *
 * <h2>其它约束（都是踩过的坑）</h2>
 * <ul>
 *   <li>接口返回 JPEG，而 {@code NativeImage.read()} 只认 PNG（实测报 {@code Bad PNG Signature}）⇒ 必须 ImageIO</li>
 *   <li>ImageIO → NativeImage 用 {@code setPixel(x,y,argb)}；手写 {@code setPixelABGR} 会把颜色写灰</li>
 *   <li>接口给的是 16:9，先居中裁一次再缩放</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class Wallpaper {

	private static final Identifier TEX = Identifier.fromNamespaceAndPath("clouddisc", "acg_wallpaper");
	private static final long TTL_MS = 6L * 60L * 60L * 1000L;
	private static final long RESCALE_DEBOUNCE_MS = 500L;

	private static volatile boolean started;
	private static volatile boolean ready;
	private static volatile boolean loading;
	/** 原始 JPEG 字节（重建纹理时要用）。 */
	private static volatile byte[] rawBytes;
	private static volatile int texW;
	private static volatile int texH;
	private static volatile DynamicTexture dynTex;
	private static volatile long lastRescaleAt;

	private Wallpaper() {
	}

	public static boolean isLoading() {
		return loading;
	}

	/** 开界面时调一次；没开开关/已加载过就直接返回。 */
	public static void ensure() {
		if (started) {
			return;
		}
		started = true;
		try {
			CloudDiscConfig cfg = CloudDiscClient.config();
			if (cfg == null || !cfg.acgWallpaper) {
				return;
			}
			loading = true;
			Thread t = new Thread(() -> load(cfg), "CloudDisc-Wallpaper");
			t.setDaemon(true);
			t.start();
		} catch (Throwable ignored) {
			loading = false;
		}
	}

	/** 手动换一张：删缓存并强制重新下载。旧图会一直显示到新图就绪（不闪白）。 */
	public static void refresh() {
		try {
			loading = true;
			started = false;
			try {
				Files.deleteIfExists(cacheFile());
			} catch (Throwable ignored) {
			}
			ensure();
		} catch (Throwable ignored) {
			loading = false;
		}
	}

	private static Path cacheFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("clouddisc").resolve("wallpaper.img");
	}

	// ------------------------------------------------------------------ 下载

	private static void load(CloudDiscConfig cfg) {
		try {
			Path f = cacheFile();
			byte[] bytes = null;
			boolean fresh = false;
			try {
				fresh = Files.isRegularFile(f)
						&& System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < TTL_MS;
			} catch (Throwable ignored) {
			}
			if (fresh) {
				try {
					bytes = Files.readAllBytes(f);
				} catch (Throwable ignored) {
					bytes = null;
				}
			}
			if (bytes == null) {
				bytes = download(cfg.acgWallpaperUrl);
				if (bytes != null && bytes.length > 1024) {
					try {
						Files.createDirectories(f.getParent());
						Files.write(f, bytes);
					} catch (Throwable ignored) {
					}
				} else {
					try {
						if (Files.isRegularFile(f)) {
							bytes = Files.readAllBytes(f);
						}
					} catch (Throwable ignored) {
					}
				}
			}
			if (bytes == null || bytes.length < 1024) {
				return;
			}
			rawBytes = bytes;
			CloudDiscClient.LOGGER.info("[CloudDisc] 壁纸已下载/命中缓存: {} 字节", bytes.length);
			int dw = Math.max(320, Minecraft.getInstance().getWindow().getGuiScaledWidth());
			int dh = Math.max(200, Minecraft.getInstance().getWindow().getGuiScaledHeight());
			buildTexture(dw, dh);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 壁纸加载失败（忽略）: {}", t.toString());
		} finally {
			loading = false;
		}
	}

	private static byte[] download(String url) {
		try {
			if (url == null || url.isBlank()) {
				return null;
			}
			HttpClient http = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(Duration.ofSeconds(8))
					.build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(20))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CloudDisc")
					.GET().build();
			HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (resp.statusCode() / 100 != 2) {
				return null;
			}
			String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
			if (!ct.isEmpty() && !ct.startsWith("image/")) {
				return null;
			}
			byte[] body = resp.body();
			return body == null || body.length < 1024 ? null : body;
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------ 纹理

	/** 裁成 16:9 并缩放到 {@code dw×dh}，再注册成纹理（这样绘制时 w == 纹理宽）。 */
	private static void buildTexture(int dw, int dh) {
		try {
			byte[] src = rawBytes;
			if (src == null) {
				return;
			}
			java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(src));
			if (bi == null) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 壁纸解码失败（ImageIO 认不出）");
				return;
			}
			bi = cropTo16x9(bi);
			java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
					dw, dh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
					java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(bi, 0, 0, dw, dh, null);
			g.dispose();

			NativeImage ni = new NativeImage(NativeImage.Format.RGBA, dw, dh, false);
			for (int y = 0; y < dh; y++) {
				for (int x = 0; x < dw; x++) {
					ni.setPixel(x, y, scaled.getRGB(x, y));
				}
			}
			final int fw = dw;
			final int fh = dh;
			Minecraft.getInstance().execute(() -> {
				try {
					DynamicTexture tex = new DynamicTexture(() -> "clouddisc-wallpaper", ni);
					dynTex = tex;
					Minecraft.getInstance().getTextureManager().register(TEX, tex);
					texW = fw;
					texH = fh;
					ready = true;
					CloudDiscClient.LOGGER.info("[CloudDisc] 壁纸纹理就绪 {}x{}", fw, fh);
				} catch (Throwable t) {
					CloudDiscClient.LOGGER.warn("[CloudDisc] 壁纸纹理注册失败: {}", t.toString());
				}
			});
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 壁纸纹理生成失败: {}", t.toString());
		}
	}

	/** 居中裁成 16:9（偏差 &lt; 2% 原样返回）。 */
	private static java.awt.image.BufferedImage cropTo16x9(java.awt.image.BufferedImage src) {
		try {
			double target = 16.0 / 9.0;
			int w = src.getWidth();
			int h = src.getHeight();
			double cur = (double) w / h;
			if (Math.abs(cur - target) <= 0.02) {
				return src;
			}
			if (cur > target) {
				int nw = (int) Math.round(h * target);
				return src.getSubimage(Math.max(0, (w - nw) / 2), 0, Math.min(nw, w), h);
			}
			int nh = (int) Math.round(w / target);
			return src.getSubimage(0, Math.max(0, (h - nh) / 2), w, Math.min(nh, h));
		} catch (Throwable t) {
			return src;
		}
	}

	// ------------------------------------------------------------------ 绘制

	/** 画背景：整张图铺满屏幕（纹理已按窗口尺寸生成 ⇒ 1:1 绘制）。 */
	public static void render(GuiGraphicsExtractor ctx, int sw, int sh) {
		if (!ready || texW <= 0 || texH <= 0) {
			requestRescale(sw, sh);
			ctx.fill(0, 0, sw, sh, 0x40000000);
			return;
		}
		try {
			ctx.enableScissor(0, 0, sw, sh);
			// 【关键】w/h 传屏幕尺寸，texW/texH 传纹理尺寸：1:1 重载，画出来是整张纹理。
			// 因为纹理就是按屏幕尺寸生成的，所以两者相等 ⇒ 不会出现"只显示一部分"。
			ctx.blit(RenderPipelines.GUI_TEXTURED, TEX, 0, 0, 0f, 0f, sw, sh, texW, texH);
			ctx.disableScissor();
			ctx.fill(0, 0, sw, sh, 0x3C141A22);
			if (Math.abs(sw - texW) > 2 || Math.abs(sh - texH) > 2) {
				requestRescale(sw, sh);
			}
		} catch (Throwable t) {
			ctx.fill(0, 0, sw, sh, 0x40000000);
		}
	}

	private static void requestRescale(int sw, int sh) {
		long now = System.currentTimeMillis();
		if (now - lastRescaleAt < RESCALE_DEBOUNCE_MS || rawBytes == null) {
			return;
		}
		lastRescaleAt = now;
		final int dw = Math.max(320, Math.min(2560, sw));
		final int dh = Math.max(200, Math.min(1600, sh));
		Thread t = new Thread(() -> buildTexture(dw, dh), "CloudDisc-WallpaperScale");
		t.setDaemon(true);
		t.start();
	}
}
