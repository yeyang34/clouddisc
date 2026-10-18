package dev.clouddisc.mixin;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.gui.NeteasePickerScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【铁砧里加一个「网易云选歌」按钮】✓
 *
 * <p>痛点：铁砧改名必须写 {@code @<id>} ✓ 但没人记得住 id ✗
 * ⇒ 点按钮 → 游戏内选歌界面 → 选中 → 自动回填 ✓ 玩家只需从铁砧里拿取唱片 ✓
 *
 * <p>为什么不用 {@code extends ItemCombinerScreen} ✗：它没有无参构造（编译期直接报错 ✓）
 * 所以这里只用<b>公有</b> API：
 * <ul>
 *   <li>{@code Screens.getButtons(screen)}（Fabric ✓ 公有静态）加按钮 ✓</li>
 *   <li>{@code Minecraft.getInstance().getWindow()} 取界面尺寸 ✓</li>
 *   <li>{@code AnvilScreen.name}（私有 EditBox）用 {@link Shadow} 访问 ✓ —— 它只管<b>显示</b> ✓</li>
 *   <li>{@code AnvilMenu.setItemName(String)}（公有 ✓）才是<b>真正生效</b>的改名 ✓（两个都要写 ✓）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
	@Shadow
	private EditBox name;

	/**
	 * 往界面里加控件（26.2 实测签名 ✓ 受保护 ✓ 泛型 ✓）
	 * <pre>protected &lt;T extends GuiEventListener &amp; Renderable &amp; NarratableEntry&gt; T addRenderableWidget(T)</pre>
	 * 不继承 {@code ItemCombinerScreen}（它没有无参构造 ✗）就只能这样拿到它 ✓
	 */
	@Shadow
	protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

	/** 上一次选中的显示名（状态行用 ✓）。 */
	@Unique
	private String clouddisc$pickedName = null;

	@Unique
	private static final int CD_GUI_W = 176;

	@Unique
	private static final int CD_GUI_H = 166;

	@Inject(method = "subInit", at = @At("TAIL"))
	private void clouddisc$addPickButton(CallbackInfo ci) {
		try {
			CloudDiscConfig cfg = CloudDiscConfig.get();
			if (cfg != null && !cfg.anvilAssist) {
				return;
			}
			Screen self = (Screen) (Object) this;
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getWindow() == null) {
				return;
			}
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			int guiX = (w - CD_GUI_W) / 2;
			int guiY = (h - CD_GUI_H) / 2;
			Button btn = Button.builder(Component.literal("☁ 网易云选歌"), b -> clouddisc$openPicker())
					.bounds(guiX, guiY + CD_GUI_H + 5, CD_GUI_W, 20).build();
			this.addRenderableWidget(btn);
			CloudDiscClient.LOGGER.info("[CloudDisc] 铁砧已加「网易云选歌」按钮 ✓（位置 {},{}）", guiX, guiY + CD_GUI_H + 5);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 铁砧按钮注入失败（不影响改名）: {}", t.toString());
		}
	}

	@Unique
	private void clouddisc$openPicker() {
		try {
			Screen self = (Screen) (Object) this;
			CloudDiscConfig cfg = CloudDiscConfig.get();
			String prefix = cfg == null || cfg.discNamePrefix == null || cfg.discNamePrefix.isBlank()
					? "@" : cfg.discNamePrefix;
			NeteasePickerScreen.Pick pick = (id, display, voice) -> {
				try {
					String value = prefix + id;
					if (this.name != null) {
						this.name.setValue(value);
					}
					Object menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
					if (menu instanceof AnvilMenu anvil) {
						anvil.setItemName(value);
					}
					clouddisc$pickedName = display;
					CloudDiscClient.LOGGER.info("[CloudDisc] 铁砧已填入「{}」（{}）✓ 从铁砧拿取唱片即完成 ✓",
							value, display);
				} catch (Throwable t) {
					CloudDiscClient.LOGGER.warn("[CloudDisc] 回填改名框失败: {}", t.toString());
				}
			};
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.setScreenAndShow(new NeteasePickerScreen(self, pick));
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 打开选歌界面失败: {}", t.toString());
		}
	}

	@Inject(method = "extractLabels", at = @At("TAIL"))
	private void clouddisc$drawHint(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
		try {
			CloudDiscConfig cfg = CloudDiscConfig.get();
			if (cfg != null && !cfg.anvilAssist) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getWindow() == null) {
				return;
			}
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			int guiX = (w - CD_GUI_W) / 2;
			int guiY = (h - CD_GUI_H) / 2;
			String line1 = "① 点下面按钮选歌   ② 从铁砧拿取唱片即完成";
			String line2 = clouddisc$pickedName == null
					? "当前：未选（也可以直接手打 @<id>）"
					: ("当前：网易云：" + clouddisc$pickedName);
			g.textRenderer().accept(guiX, guiY + CD_GUI_H + 28, Component.literal(line1));
			g.textRenderer().accept(guiX, guiY + CD_GUI_H + 39, Component.literal(line2));
		} catch (Throwable ignored) {
			// 画提示失败绝不影响铁砧
		}
	}
}