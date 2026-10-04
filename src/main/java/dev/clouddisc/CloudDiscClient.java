package dev.clouddisc;

import dev.clouddisc.gui.CloudDiscConfigScreen;
import dev.clouddisc.sync.SyncService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CloudDisc 客户端入口。
 *
 * <p>纯客户端 Mod：{@code fabric.mod.json} 里 {@code environment=client}，
 * Mixin 挂在 client 段，专用服务端完全不加载本 Mod，连原版服务器不需要装任何东西。
 */
@Environment(EnvType.CLIENT)
public final class CloudDiscClient implements ClientModInitializer {
	public static final String MOD_ID = "clouddisc";
	/** 标语（界面、元数据、文档、启动日志都引这一个常量，改一处即可）。 */
	public static final String SLOGAN = "歪歪网易云唱片mod——忠于原版，高于原版";
	/** 一句话定位：只用陈述句，不加感叹号。 */
	public static final String TAGLINE = "让 Minecraft 自己唱你的网易云：全程客户端，全图同步。";
	public static final Logger LOGGER = LoggerFactory.getLogger("CloudDisc");

	private static CloudDiscConfig config;
	private static SyncService sync;

	@Override
	public void onInitializeClient() {
		config = CloudDiscConfig.get();
		sync = new SyncService(config);
		sync.init();
		// 物理声效的总开关：把配置里的值同步给声学模块（同时也用于 DSP 兜底路径的判断）
		dev.clouddisc.audio.Acoustics.setEnabled(config.physicsSound);
		LOGGER.info("[CloudDisc] 物理声效: 总开关={} 强度={} 隔墙闷度k={} 漏音通路数={}/8 放宽上限={} 射线数={} 严格遮挡={} 方向性={} 调试日志={}",
				config.physicsSound, config.physicsSoundLevel, config.physicsAbsorption,
				config.physicsOcclusionPaths, config.physicsOcclusionRelax, config.physicsRays,
				config.physicsStrictOcclusion, config.physicsSoundDirection, config.physicsSoundDebug);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (sync != null) {
				sync.tick(client);
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sync.onDisconnect());
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
				sync.onUseBlock(player, world, hand, hitResult));

		// 配置界面的三个入口（都不依赖 ModMenu）：
		//   ① 快捷键（默认 K，可在"选项 → 控制"里改）
		//   ② 客户端命令 /clouddisc —— 纯客户端命令，服务器没装任何东西也能用
		//   ③ ModMenu 的模组列表按钮（装了 ModMenu 才会出现，见 ModMenuIntegration）
		configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.clouddisc.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.clouddisc"));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (configKey.wasPressed()) {
				openConfigScreen(null);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("clouddisc").executes(context -> {
					MinecraftClient client = context.getSource().getClient();
					client.execute(() -> openConfigScreen(null));
					return 1;
				})));

		LOGGER.info("[CloudDisc] ===== 诊断信息（遇到问题请把这一段连同后续报错一起发给我）=====");
		LOGGER.info("[CloudDisc] 版本     : {}", dev.clouddisc.audio.Acoustics.version());
		LOGGER.info("[CloudDisc] 游戏目录 : {}", FabricLoader.getInstance().getGameDir());
		LOGGER.info("[CloudDisc] 音乐目录 : {}  ← 把 .ogg / .wav 放在这里", config.resolveLocalMusicDir());
		LOGGER.info("[CloudDisc] 缓存目录 : {}", config.resolveCacheDir());
		LOGGER.info("[CloudDisc] 配置文件 : {}", FabricLoader.getInstance().getConfigDir().resolve("clouddisc.json"));
		LOGGER.info("[CloudDisc] 默认音源 : {}   改名前缀: \"{}\"", config.defaultProvider, config.discNamePrefix);
		LOGGER.info("[CloudDisc] UDP 端口 : {}   局域网发现: {}   聊天兜底: {}",
				config.udpPort, config.enableLanDiscovery, config.enableChatRelay);
		LOGGER.info("[CloudDisc] 播放用法 : 用铁砧把唱片改名为 \"{}<网易云歌曲id>\"，例如 \"{}186016\"；"
				+ "后面可以跟备注，如 \"{}186016（晴天）\"，备注会被忽略", config.discNamePrefix, config.discNamePrefix, config.discNamePrefix);
		LOGGER.info("[CloudDisc] ==========================================================");
		LOGGER.info("[CloudDisc] {}", SLOGAN);
		LOGGER.info("[CloudDisc] {}", TAGLINE);
		LOGGER.info("[CloudDisc] ==========================================================");
	}

	public static SyncService sync() {
		return sync;
	}

	public static CloudDiscConfig config() {
		return config;
	}

	private static KeyBinding configKey;

	/** 打开配置界面。{@code parent} 为 null 时返回上一级由屏幕自己处理（回到游戏）。 */
	public static void openConfigScreen(Screen parent) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.setScreen(new CloudDiscConfigScreen(parent));
	}
}
