package dev.clouddisc.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成。
 *
 * <p>这个类<b>只被 ModMenu 加载</b>（通过 {@code fabric.mod.json} 里的 {@code modmenu} 入口点），
 * 所以没装 ModMenu 时它永远不会被类加载器碰到 —— 也就是说本 Mod 对 ModMenu
 * <b>没有硬依赖</b>，照样能启动、照样能用快捷键和 {@code /clouddisc} 打开配置界面。
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CloudDiscConfigScreen::new;
	}
}
