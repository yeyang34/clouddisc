package dev.clouddisc.jukeboxlib;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CloudDisc 的可选附加组件：解除"原版唱片自带时长"对长曲的限制。
 *
 * <p><b>它做什么</b>：装在服务端时，只对自定义名以 {@link JukeboxLibConfig#DISC_PREFIX}
 * 开头的唱片，让原版不再提前判定"放完了" —— 于是长歌能完整播完，
 * 而"玩家拔碟 / 换碟"仍然完全走原版逻辑（因此干净、没有猜测）。
 *
 * <p><b>它不做什么</b>：不解析任何同步数据、不注册命令、不写文件；
 * 不带这个 lib 时音乐 mod 照常工作（只是长歌会被唱片时长限制）。
 *
 * <p><b>重要</b>：类上**不能**加 {@code @Environment}。
 * {@code main} 入口点在物理客户端上也会被执行，而 {@code @Environment} 的语义是
 * "环境不匹配时让这个类无法加载" —— 用错就会像 0.4.0 那样**启动直接崩溃**。
 * 正确的做法就是下面这样：类上不加注解，在 {@link #onInitialize()} 里运行时判断。
 */
public final class JukeboxLib implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("CloudDisc-JukeboxLib");

	@Override
	public void onInitialize() {
		EnvType env = FabricLoader.getInstance().getEnvironmentType();
		if (env == EnvType.SERVER) {
			LOGGER.info("[CloudDisc-JukeboxLib] 服务端侧已就绪：仅对自定义名以 \"{}\" 开头的唱片解除时长限制，上限 {} 分钟；原版唱片行为不变",
					JukeboxLibConfig.DISC_PREFIX, JukeboxLibConfig.MAX_EXTEND_MINUTES);
		} else {
			// 客户端侧的策略需要与音乐 mod 协作（音乐 mod 才知道我们的会话），
			// 因此这里只做提示；真正的判断在音乐 mod 里通过"可选的策略接口"接入。
			LOGGER.info("[CloudDisc-JukeboxLib] 客户端侧已就绪：将由 CloudDisc 在收到唱片机停止事件时询问本组件是否忽略（前缀 \"{}\"）",
					JukeboxLibConfig.DISC_PREFIX);
		}
	}
}
