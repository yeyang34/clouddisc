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



	/** 上一次选中的显示名（状态行用 ✓）。 */
	@Unique
	private String clouddisc$pickedName = null;

	/** ★ 待回填的名字：选歌时先记下 ✓ 等铁砧界面重建完成后再落地 ✓（选歌时写会被重建冲掉 ✗ 实测） */
	@Unique
	private String clouddisc$pending = null;

	/** 按钮引用：用来按"输入槽有没有物品"控制显隐 ✓ */
	@Unique
	private Button clouddisc$btn = null;

	/**
	 * ★ 复刻【玩家手打名字】那条原版路径 ✓
	 *
	 * <p>{@code EditBox.setValue()} <b>不会触发 responder</b> ✗（实测：名字看着改了 ✓ 但服务端不知道 ✗
	 * 结果槽不重算 ⇒ 拿到的还是旧名字 ✓ 这就是"选好了但名字没改"的原因 ✓）。
	 *
	 * <p>原版链路是：{@code EditBox responder → AnvilScreen.onNameChanged(String)} ✓
	 * 由它更新菜单并把改名发给服务端 ✓ 所以这里反射调那个私有方法 ✓
	 */
	@Unique
	private boolean clouddisc$notifyNameChanged(String value) {
		try {
			for (Class<?> k = AnvilScreen.class; k != null; k = k.getSuperclass()) {
				for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
					if ("onNameChanged".equals(m.getName()) && m.getParameterCount() == 1
							&& m.getParameterTypes()[0] == String.class) {
						m.setAccessible(true);
						m.invoke(this, value);
						return true;
					}
				}
			}
			CloudDiscClient.LOGGER.warn("[CloudDisc] 没找到 onNameChanged ✗ 退回直接改菜单 ✓");
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 调 onNameChanged 失败: {}", t.toString());
		}
		return false;
	}

	/** 按"铁砧输入槽里有没有物品"控制按钮显隐 ✓（没放东西就不该出现 ✓）。 */
	@Unique
	private void clouddisc$syncButton() {
		try {
			if (clouddisc$btn == null) {
				return;
			}
			Object menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
			boolean hasItem = false;
			if (menu instanceof AnvilMenu anvil) {
				hasItem = !anvil.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty();
			}
			clouddisc$btn.visible = hasItem;
		} catch (Throwable t) {
			// 显隐失败保持默认（可见 ✓）不影响功能 ✓
		}
	}

	/** ★ 落地待回填的名字 ✓ 只在铁砧界面已经重建好之后调用 ✓。 */
	@Unique
	private void clouddisc$applyPending() {
		String value = clouddisc$pending;
		if (value == null) {
			return;
		}
		try {
			if (this.name != null) {
				this.name.setValue(value);
			}
			boolean ok = clouddisc$notifyNameChanged(value);
			if (!ok) {
				Object menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
				if (menu instanceof AnvilMenu anvil) {
					anvil.setItemName(value);
				}
			}
			clouddisc$pending = null;
			CloudDiscClient.LOGGER.info("[CloudDisc] 铁砧回填落地 ✓「{}」改名框现在={} 原版路径={}",
					value, this.name == null ? "?" : this.name.getValue(), ok);
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 回填落地失败: {}", t.toString());
		}
	}

	private static final int CD_GUI_W = 176;

	@Unique
	private static final int CD_GUI_H = 166;

	/**
	 * 用反射往界面里加按钮 ✓
	 *
	 * <p>为什么不用 {@code @Shadow} ✗：26.2 的
	 * {@code protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T)}
	 * 是泛型方法 ✓ Mixin 没认出来（实测报 "was not located in the target class" ✗ "No refMap loaded" ✓）
	 * ⇒ 改用反射，按名字在父类链上找 ✓ 擦除后的参数类型是 GuiEventListener ✓ Button 实现了它 ✓
	 */
	@Unique
	private boolean clouddisc$addWidgetReflect(Object widget) {
		try {
			for (Class<?> k = AnvilScreen.class; k != null; k = k.getSuperclass()) {
				for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
					if ("addRenderableWidget".equals(m.getName()) && m.getParameterCount() == 1
							&& !m.isBridge() && !m.isSynthetic()) {
						m.setAccessible(true);
						m.invoke(this, widget);
						return true;
					}
				}
			}
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 反射加控件失败: {}", t.toString());
		}
		return false;
	}

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
			boolean added = clouddisc$addWidgetReflect(btn);
			CloudDiscClient.LOGGER.info("[CloudDisc] 铁砧按钮: 反射加控件={} 位置=({},{})", added, guiX, guiY + CD_GUI_H + 5);
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
			NeteasePickerScreen.Pick pick = (provider, trackId, display) -> {
				try {
					// B 站要带前缀（@bili:BV...）；网易云保持 @<数字> 不变 ✓
					String value = (provider == null || provider.isBlank())
							? prefix + trackId
							: prefix + provider + ":" + trackId;
					// 选歌界面还开着 ⇒ 此刻写会被"返回时的界面重建"冲掉 ✗（实测 ✓）
					// 所以只记下 ✓ 等回到铁砧、界面重建完成后再落地 ✓（clouddisc$applyPending ✓）
					clouddisc$pending = value;
					// ② ★ 必须走原版路径：更新菜单 + 把改名请求发给服务端 ✓
					//    只 setValue 不触发 responder ✗ 服务端收不到 ⇒ 结果槽不重算 ⇒ 名字没变 ✓（实测症状 ✓）
					boolean notified = clouddisc$notifyNameChanged(value);
					if (!notified) {
						Object menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
						if (menu instanceof AnvilMenu anvil) {
							anvil.setItemName(value);
						}
					}
					clouddisc$pickedName = display;
					CloudDiscClient.LOGGER.info("[CloudDisc] 铁砧已填入「{}」（{}）原版改名路径={} ✓ 现在从铁砧拿取唱片即完成 ✓",
							value, display, notified);
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

	/** 每刻同步：按钮显隐跟着输入槽 ✓ 待填值一旦出现就落地 ✓。 */
	@Inject(method = "containerTick", at = @At("TAIL"))
	private void clouddisc$tick(CallbackInfo ci) {
		try {
			clouddisc$syncButton();
			clouddisc$applyPending();
		} catch (Throwable ignored) {
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
