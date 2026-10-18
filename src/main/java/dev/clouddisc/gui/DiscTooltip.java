package dev.clouddisc.gui;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.disc.DiscName;
import dev.clouddisc.music.MusicProvider;
import dev.clouddisc.music.Providers;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;


import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【鼠标悬停显示网易云曲名】✓
 *
 * <p>铁砧里改名【仍然是曲目 id】（例如 {@code @186016}）—— 这点不变 ✓
 * 因为名字是"跨玩家、跨箱子、跨服务器"唯一能携带的信息 ✓
 * 但 id 对人没意义 ✗ 所以悬停时补一行真名 ✓
 *
 * <p>解析是异步的（要联网 ✓）：
 * <ul>
 *   <li>已缓存 ⇒ 直接显示「网易云：歌名 - 歌手」✓</li>
 *   <li>没缓存 ⇒ 显示「网易云：解析中…」✓ 同时后台解析 ✓ 解析完下次悬停就能看到 ✓</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DiscTooltip {
	/** key = provider|trackId ⇒ 真名 ✓ */
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	/** 正在解析中的 key ✓（避免重复发请求 ✓） */
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

	private DiscTooltip() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
			try {
				addLines(stack, lines);
			} catch (Throwable t) {
				// 提示出错绝不影响游戏
			}
		});
	}

	private static void addLines(ItemStack stack, java.util.List<Component> lines) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		CloudDiscConfig cfg = CloudDiscConfig.get();
		if (cfg != null && !cfg.showDiscTooltip) {
			return;
		}
		// 只要物品带自定义名字 ✓ 就尝试解析（不用枚举所有唱片名 ✗ 26.2 里唱片 id 各异 ✓）
		if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
			return;
		}
		Component name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		String prefix = cfg == null ? "@" : cfg.discNamePrefix;
		DiscName.SongQuery q = DiscName.parse(name, prefix);
		if (q == null) {
			return;
		}
		// 纯数字 id ⇒ 一定是网易云曲目 ✓（defaultProvider 可能是 local ✗ 那样解析不出来 ✓）
		String provider;
		if (q.provider() != null) {
			provider = q.provider();
		} else if (q.trackId().trim().matches("\\d{1,12}")) {
			provider = "netease";
		} else {
			provider = cfg == null ? "netease" : cfg.defaultProvider;
		}
		String key = provider + "|" + q.trackId();

		// 用户自己写的备注（@186016（晴天）⇒ 晴天 ✓）
		String note = DiscName.annotation(q.trackId());
		if (note != null && !note.isBlank()) {
			lines.add(Component.literal("备注：" + note).withStyle(ChatFormatting.GRAY));
		}

		String cached = CACHE.get(key);
		if (cached != null) {
			lines.add(Component.literal("网易云：" + cached).withStyle(ChatFormatting.AQUA));
		} else {
			lines.add(Component.literal("网易云：解析中…").withStyle(ChatFormatting.DARK_GRAY));
			startResolve(provider, q.trackId(), key, cfg);
		}
	}

	private static boolean isDisc(ItemStack stack) {
		try {
			return stack.getItem().toString().contains("music_disc");
		} catch (Throwable t) {
			return false;
		}
	}

	private static void startResolve(String provider, String trackId, String key, CloudDiscConfig cfg) {
		if (!PENDING.add(key)) {
			return;
		}
		MusicProvider p = Providers.byId(provider);
		if (p == null) {
			PENDING.remove(key);
			CACHE.put(key, "（未知音源 " + provider + "）");
			return;
		}
		p.resolve(trackId, cfg).whenComplete((track, err) -> {
			PENDING.remove(key);
			if (err != null || track == null) {
				CACHE.put(key, "（解析失败，游戏内仍可正常播放）");
				return;
			}
			String title = track.title() == null || track.title().isBlank() ? trackId : track.title();
			CACHE.put(key, title);
			CloudDiscClient.LOGGER.info("[CloudDisc] 唱片提示: {} ⇒ {}", key, title);
		});
	}
}
