# UI 重构方案（26.2 · 基于实测 API）

> 目的：把"按钮对不上文字""看着廉价"这类问题**从根上**解决。
> 所有 API 签名都是 **javap 反查 26.2 映射后的 jar 得到的**，不是猜的。
> 调研对象：Sodium（`dev` 分支）、Litematica/malilib、YACL —— 见 §1。

---

## 1. 别人在守的规则（调研结论）

### 1.1 布局（Sodium / malilib 一致的部分）

1. **一行一选项：左标签 + 右控件，控件右边界对齐到行右边界**，两栏绝不在同一行换位。
2. **名字永不换行**：放不下就截断 + 省略号（Sodium `...`、malilib ` ...`）；
   截断要在样式（斜体/星号）**之后**做，否则 `*` 会被省略号吃掉。
3. **控件宽度固定**，不随名字长度变；左列宽 = 行宽 − 控件宽 − 固定间距。
4. 分组靠**标题行 + 行距**，不靠缩进；整列滚动，滚动条独立于内容。
5. 状态用**样式**表达（禁用=灰+删除线、已改=斜体+`*`），行高恒定，不因状态跳动。

### 1.2 三家实现（实测源码，2026-10-20）

| 库 | 结构 | 视觉手法 | 动画 |
|---|---|---|---|
| **Sodium**（`dev`） | 四区：搜索条 → 左页列表(125px) → 中选项列(210px) → 右 tooltip 区(200px)，整体居中；行高 `font.lineHeight*2` | **极简 flat：纯矩形 + 1px 描边，无圆角、无渐变、无阴影**。颜色是 ARGB 常量 + HSV 提亮/压暗（5 套主题） | 无缓动（滚动条停止 1 秒后整条消失） |
| **YACL**（`main`） | 三栏：顶部 tab 条 → 左 2/3 选项列表 → 右 1/3 说明面板；三层数据 Category→Group→Option | **不自己画圆角**，复用原版按钮九宫格贴图 `WidgetSprites`（normal/disabled/highlighted/slider 四态）；勾选框用"双描边 + 假阴影" | 仅右栏长文用 `Mth.lerp` 缓慢滚动 |
| **Cloth Config**（`v21.11`） | 顶部 tab + 单列列表；行高 24；tab 切换后 `init()` 重建 | 基本全用原版控件 + 一张 9×9 图标图集，**用 u/v 偏移切 normal/hover/disabled 三态** | **唯一有完整动画框架的**：`ValueAnimator`（700ms 过渡）+ `expoEase` 平滑滚动 |

**最重要的一条结论（推翻了我的假设）**：
> **"看起来专业"不靠圆角/渐变/模糊，靠的是"一套颜色 + 严格间距 + 状态一致"。**
> Sodium 一个圆角都没有，却是公认最耐看的；YACL 的圆角其实是**原版贴图**，Cloth 只多一张 9×9 图集。

所以我们的路线应该是：**先把 flat + 调色板 + 状态做对，再考虑贴图**，而不是上手就做圆角/模糊。

---

## 2. 26.2 可用的绘制原语（javap 实测）

`net.minecraft.client.gui.GuiGraphicsExtractor`：

| 方法 | 用途 |
|---|---|
| `fill(x1,y1,x2,y2,argb)` | 实心矩形（所有卡片/轨道/滚动条） |
| `fillGradient(x1,y1,x2,y2,argbTop,argbBottom)` | **渐变**（悬停/卡片渐变） |
| `outline(x1,y1,x2,y2,argb)` | **描边**（卡片边、聚焦环） |
| `horizontalLine / verticalLine(x1,y1,x2,argb)` | 分隔线 |
| `enableScissor(l,t,r,b)` / `disableScissor()` / `containsPointInScissor(x,y)` | **裁剪**（滚动、避免溢出） |
| `blurBeforeThisStratum()` | **背景模糊**（真·高级感，Sodium/YACL 那种） |
| `nextStratum()` | 分层（先把底画完再画内容） |
| `pose()` → `Matrix3x2fStack` | **缩放/位移动画**（按压缩小、弹出） |
| `text(font,comp,x,y,color,shadow)` | 文字，**带阴影开关** |
| `textWithBackdrop(font,comp,x,y,color,bg)` | **自带底板的文字**（不用手画黑条） |
| `textWithWordWrap(font,text,x,y,color,width)` | 自动换行（tooltip/说明） |
| `centeredText(font,comp,cx,y,color)` | 居中 |
| `setTooltipForNextFrame(comp,x,y)` | **每帧 tooltip**（行内说明） |
| `requestCursor(CursorType)` | **手型/禁止光标**（`CursorTypes.POINTING_HAND` / `NOT_ALLOWED`，26.2 实测存在） |
| `blitSprite(pipeline,sprite,x,y,w,h)` + `GuiSpriteScaling$NineSlice` | **九宫格贴图 ⇒ 圆角/描边**（vanilla 自己就是这么画按钮的） |
| `item(stack,x,y)` / `fakeItem(...)` | 物品预览（以后做唱片列表能用） |

**关键结论**：
- **圆角不用写 shader** —— 走九宫格：一张小 PNG + 同名 `.mcmeta` 里写
  `{"gui":{"scaling":{"type":"nine_slice","width":..,"height":..,"border":..}}}`，然后 `blitSprite`。
- **模糊是真的有** —— `blurBeforeThisStratum()`，不需要自己渲染到 FBO。
- **文字底板是现成的** —— `textWithBackdrop`，我们之前手画黑条属于重复造轮子。

---

## 3. 效果 → 实现对照表

| 想要的效果 | 怎么实现 | 备注 |
|---|---|---|
| 卡片 / 圆角块 | 九宫格 PNG + `blitSprite`；过渡期用 `fill + outline` | 九宫格需要美术资源；先用矩形+描边也能看 |
| 背景模糊 | `blurBeforeThisStratum()` | 只在打开界面时调一次，每帧调太贵 |
| 悬停高亮 | `fillGradient` 叠一层半透明白（`0x1AFFFFFF`） | 别用纯色高亮，渐变更"稳" |
| 按压缩小 | `pose().scale(0.98f)` 包住绘制 | 记得 push/pop，别污染后续 |
| 开关滑块 | 轨道 `fill` 圆角化 + 圆点用九宫格 | 比原版 `Button("开/关")` 高级一档 |
| 滚动 | `enableScissor` + 独立滚动条（`fill` 一条竖线） | 滚动条宽 3~4px，颜色用主色 |
| 行内说明 | `setTooltipForNextFrame` | 标签因此可以短 ⇒ 密度提升 |
| 禁用态 | 颜色 ×0.5 + `outline` 用灰 | 不要隐藏，隐藏会让布局跳动 |
| 分组标题 | 小号字 + 一条 1px 横线 | 不要用大色块，压不住内容 |
| 文字阴影 | `text(..., shadow=true)` | 亮背景下必须开 |

---

## 3.5 可直接照抄的 12 条（三家取并集，按性价比排序）

1. **行背景只用一层 `fill`**，hover 时**只改透明度**（`0x90000000` → `0xE0000000`）—— 不缩放不位移（Sodium）
2. **手型光标**：`graphics.requestCursor(CursorTypes.POINTING_HAND)`；禁用时 `NOT_ALLOWED`
3. **禁用态不是单纯变灰**：文字 `GRAY + STRIKETHROUGH`（Sodium）；勾选框换成四角括号
4. **焦点用独立 1px 描边**（`outline`），与 hover 的底色变化区分开
5. **已修改项双重标记**：名字加 `*` + `ITALIC`（两家都这么做）
6. **重置做 dirty check**：只有"值 ≠ 默认"才亮（Cloth），或用 Shift+hover 才浮现（Sodium）
7. **文字必截断加 `...`**（`font.width` 二分），截断要在样式之后做
8. **每个选项配 tooltip**：长说明别塞进标签（`setTooltipForNextFrame`）
9. **长说明自动换行 + 裁剪**（`font.split` + `enableScissor`）
10. **滚动条 1 秒后自动隐藏**（Sodium）：轨道 `0x40000000` + 滑块 `0x80000000`
11. **统一调色板**：一个 `Palette` 类集中管主色/前景/背景，派生色用 HSV 提亮压暗，**禁止各处硬编码 ARGB**
12. **数值/开关过渡做 300~700ms 缓动**（Cloth 的 `ValueAnimator` 思路；我们可自己 `Mth.lerp`）

> 注意第 1 条：**先别做圆角/模糊**。Sodium 一个都没有，照样好看。
> 圆角只有在"有贴图资源"时才值得做（YACL 是复用原版按钮图，Cloth 是多一张 9×9 图集）。

---

## 4. 我们当前实现 vs 规则（诊断）

已修（2026-10-20）：

| 问题 | 后果 | 修法 |
|---|---|---|
| `textField` 宽高写死 `168×20`，其它控件用 `layFieldW×layRowH` | 窄窗口下输入框更宽更高更靠左 ⇒ **"按钮对不上文字"** | 统一用同一组布局变量 |
| 标签 `plainSubstrByWidth` 硬切 | 文字被砍得莫名 | 二分 + `...`（同 Sodium） |
| 行距 `(height-118)/ROWS` | 11 行时最下面一行压到底部按钮带 | 改成按"可用高度/ROWS"算 |
| 两个从未使用的遗留常量 | 干扰阅读 | 删掉 |

**仍未做**（需要你点头才动）：

1. **滚动**：现在靠"压缩行距"硬塞，行数一多就挤。应改成固定行高 + 滚轮滚动 + 滚动条。
2. **分组标题**：物理声效那页 11 项应分「遮挡 / 门道 / 调试」三组。
3. **行内 tooltip**：把 `（⚠重启生效；双开请不同）` 这类长括号说明挪进 tooltip。

---

## 5. 分三步落地（建议）

**第 1 步（低风险，半天）**
- 固定行高 20px + `enableScissor` 滚动 + 滚动条
- 长说明挪进 `setTooltipForNextFrame`
- 分组标题行

**第 2 步（中风险）**
- 自绘开关（圆角轨道 + 圆点），替掉 `Button("开/关")`
- 悬停渐变、按压缩小
- 九宫格卡片（要一张 12×12 的 PNG + mcmeta）

**第 3 步（可选）**
- 打开界面时 `blurBeforeThisStratum()`
- 搜索框过滤选项

> 每一步都能单独编译、单独回退，不做大爆炸式重写。
