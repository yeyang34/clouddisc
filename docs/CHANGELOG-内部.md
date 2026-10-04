# 更新日志 · 内部详细版（里）

> **这份是给我们自己看的**：每个版本的完整变更、每个 bug 的根因与实测数据。
> 给玩家看的简版在仓库根的 [CHANGELOG.md](../CHANGELOG.md)；
> 游戏内「第 4 节 更新日志」显示的也是简版。

---

## 0.12.13

**回滚物理声效到 0.12.6 基线 + 用"真实求交"重做门遮挡**

- 回滚范围：`0.12.7`(b3b7438) / `0.12.8`(47171c6) / `0.12.9`(65fd715) 对物理声效的改动全部撤回，
  源码基线回到 `93d5af2`(0.12.6)，再叠加本次门修复。
- **根因（用户实测指出）**：`Acoustics.occlusionAt` 原先只判 `getCollisionShape(...).isEmpty()` ——
  "这一格有没有碰撞体积"。**开着的门仍然有碰撞形状**（薄板贴在侧面），所以开门也被算作遮挡；
  只有把门**挖掉**（方块变成空气）才会恢复清晰 —— 与实测完全一致。
- **改法**：新增 `Acoustics.hitsShape(shape, from, to, pos)`：
  1. 命中包围盒已填满 0..1 的**满格体素** → 直接判挡（避开"入射点落在边界"的浮点漏判）；
  2. 其余 → `VoxelShape#raycast(from,to,pos)` **真实求交**（该 API 需要世界坐标，内部 `box.offset(pos)`）；
  3. 起点/终点落在实体内部（raycast 可能返回 null）→ 用包围盒 `contains` 兜底；
  4. 任何异常 → 保守按"挡住"处理。
  调用点由 `if (shape.isEmpty()) return true;` 改为 `if (!hitsShape(shape, from, to, p)) return true;`。
- **另改**：`BlockAcoustics.occlusionOf` 删掉"非完整方块 ×0.5"这道重复打折
  （关着的门原先 = 0.55×0.8×0.5 = 0.22，几乎等于没挡）；`GLASS` 基准 0.50 → 0.25 以保持玻璃听感不变；
  `Acoustics.MAX_RELAX` 0.85 → **0.40**、`DEFAULT_OPEN_PATHS` 3 → **6**，且 `openPathsRequired()`
  对配置值取 `max(6, cfg)`（防止用户配置里的旧值 3 把放宽规则又放回来）。
- **验证**：手算 + 装机实测（用户确认"完美"）；`实心格/判定挡/遮挡累积` 三个计数在调试日志里可自证。
- 已知限制：混响发送高频未按反射次数累乘；多条唱片机共用全局 aux slot；0.19.3 实例里同时装着
  sound-physics-remastered（测听感建议先禁用）。
## 0.12.9

**修「进游戏没音效、整体又闷又小声」：混响发送高频被算成 0.001（Bug A）+ 能量预算把直通砍了一刀（Bug B）
+ 直通可听下限**

> 触发：用户 0.12.8 装机实测（0.19.5 实例）：「还是不行，没有音效，直接整体全部效果都没了」。
> 这一版**没有动 EFX 架构**：aux slot 数量与绑法、EAXReverb 类型、`AL_DIRECT_FILTER` /
> `AL_AUXILIARY_SEND_FILTER` 的拓扑与写法全部保持 0.12.7/0.12.8 的样子，
> 只改**数值公式**（两个纯函数）与**新增日志字段**。

### 现场证据（用户 0.12.8 实测，`physicsSoundDebug=true`，三行日志原文）

```
16:54:56 物理声效[M7]: ver=0.12.8 source=3 EFX=可用(4段) 遮挡累积=2.100 主射线遮挡=2.100 主射线走格=9 实心格=2 判定挡=2
        通透通路=0/8 放宽=0.000 吸收k=4.500 直通截止(GAINHF)=0.006 直通增益=0.153 开阔度=0.000 距离=5.8格
        ｜sendGain=[1.000, 0.313, 0.000, 0.000] sendCutoff=[0.001, 0.001, 0.001, 0.001]
          逐层反射率=[0.488, 0.459, 0.488, 0.431] 平均反射率=0.466 自由程=1.8格
16:54:57 … 遮挡累积=2.000 走格=5 实心格=1 判定挡=1 GAINHF=0.005 直通增益=0.220 距离=3.6格
        ｜sendGain=[1.000, 0.326,…] sendCutoff=[0.001, 0.001, 0.001, 0.001]
16:54:58 … 遮挡累积=0.000 走格=3 实心格=1 判定挡=0 GAINHF=0.965 直通增益=0.985 距离=2.0格
        ｜sendGain=[1.000, 0.337,…] sendCutoff=[0.965, 0.965, 0.965, 0.965]
```

两条读数直接指向两个 bug：
① `sendCutoff` 在"隔墙"时是 **0.001**（≈ -60 dB ⇒ 混响完全听不见），通畅时是 0.965；
② `直通增益` 是**预算之前**的值 —— 0.153 再乘预算 0.682 = **0.104**（-19.7 dB），
"又闷又小声"的后半截就是这个。

### Bug A：`sendCutoff` 的根因、旧公式、改法

**代码位置**：`Acoustics.traceReverb`（0.12.8 的 `Acoustics.java:830-834`）。

```java
// 0.12.6 ~ 0.12.8（旧）
float occCut = (float) Math.exp(-occ * k);          // k = physicsAbsorption × 强度 = 4.5
out.sendCutoff[0] = occCut * (1.0f - w0) + w0;      // w_i = "绕过来的声音"权重
out.sendCutoff[1] = occCut * (1.0f - w1) + w1;
out.sendCutoff[2] = occCut * (1.0f - w2) + w2;
out.sendCutoff[3] = occCut * (1.0f - w3) + w3;
```

`k = 4.5` 是 0.12.6 为了"隔一层石头墙**直通**一耳就闷"调的；把它复用给混响发送就是灾难：
`occ=2.1` → `occCut = exp(-9.45) = 7.9e-5`；现场 `开阔度=0.000`（`sharedAirspaces=0`，
所以 `w0..w3` 全是 0）→ **4 路发送的低通全是 7.9e-5**，现场下发值 **0.001**。
混响（aux send）走的是混音器直连，**根本不穿听者那堵墙**，拿"直通穿墙衰减"当它的低通是概念错位。
注意这不是"连乘多个 <1 因子下溢"（`fillSends` 里没有再乘反射率到 cutoff 上）——
就是这一个 `exp(-occ×k)` 自己塌掉的，`w` 混合也救不回来（它只会把值抬向 `w`，而现场 `w=0`）。

**改法（0.12.9，抽成纯函数，离线自检与生产共用）**：

```java
// Acoustics.sendCutoffFor(occ, w, 反射率)   —— 见 Acoustics.java 的新方法
occSend = 0.20 + 0.80 * exp(-occ * 0.5);          // SEND_CUTOFF_MIN / SEND_OCC_K
base    = occSend * (1-w) + w;
mat     = 0.60 + 0.40 * 反射率;                    // HF_REFL_BASE：每层反射面的高频增益
return clamp(base * mat, 0.20, 1.0);               // 硬下限 0.20
```

常数怎么选的：

| 常数 | 值 | 理由 |
|---|---|---|
| `SEND_CUTOFF_MIN` | **0.20** | 用户验收要求"不低于 0.15~0.25"。0.20 = -14 dB 高频：混响可以暗，不许消失 |
| `SEND_OCC_K` | **0.5**（原来是 4.5） | occ=2.1 → 0.35（混响跟着闷一点）；occ=0 → 1.0（通畅时一点也不压） |
| `HF_REFL_BASE` | **0.60** | 石头(0.60)→0.84、玻璃(0.90)→0.96、羊毛(0.12)→0.65、完全吸声(0)→0.60。吸声房间余响更暗，但一定还在 |

**逐层反射率做兜底**：`bandRefl[i] == 0` 的语义是"这一层一条射线都没命中"（例：射线全飞进开阔天空），
不是"全吸声"，所以这时用 `avgReflectivity` 兜底，避免凭空多压一次高频。

**水下**：原来 `×0.4` 可能低到 0.08，现在加了第二道下限 `SEND_CUTOFF_MIN_UNDERWATER = 0.10`
（水下语义是"更闷"，但不是静音）。

### Bug B：能量预算的根因、旧公式、改法

**代码位置**：`EfxEngine.applyToSource`（0.12.8 的 `EfxEngine.java:352-369`）。

```java
// 0.12.7 ~ 0.12.8（旧）
for (...) { sg[i] = sendGain[i]; sc[i] = sendCutoff[i]; if (i < bands) sumSend += sg[i]; } // ← 没乘 sc
float total  = dg + sumSend;                          // 0.153 + 1.313 = 1.464
float budget = total > 1.0f ? 1.0f / total : 1.0f;    // 0.683
dg *= budget;                                         // 0.153 → 0.104（实际下发值）
```

被低通压到 0.001、**一点声音都没送出去**的 4 路发送，照样按满能量占预算，
于是把直通增益又压掉 32%。用户听到的"又闷又小声 / 整体全部效果都没了"就是这两件事叠加。

**改法（0.12.9，同样抽成纯函数）**：

```java
// EfxEngine.energyBudget(directGain, sendGain, sendCutoff, sendBands, wetGain, out)
E = Σ (sendGain[i] × sendCutoff[i] × wetGain);        // 有效湿声能量；wetGain = EAXReverb 自己的 gain
total = directGain + E;
sendBudget   = total > 1 ? 1/total : 1;               // 发送按真实预算缩（超量的是它）
directBudget = max(sendBudget, BUDGET_MIN_DIRECT);    // 直通最多降到一半（0.5）
```

- **为什么还要乘 `wetGain`（默认 0.32）**：发进 aux slot 的信号还要经过 EAXReverb 自己的 `gain`
  才与直通相加。不乘它就会高估混响 —— 现场日志第 3 行（通畅、开阔地、距离 2 格）
  直通增益 `0.985` 被预算压到 0.428 倍（白掉 -7.4 dB），那正是"整体小声"的另一半。
- `BUDGET_MIN_DIRECT = 0.5`：用户 0.12.9 的验收要求"预算不把直通压到 0.5 倍以下"。
  发送过量时优先削发送，直通最多降到一半。

### 顺带：直通"可听下限"（用户要求"明显，不是消失"）

`Acoustics.directParams`（0.12.9 把 `evaluate` 里那一段抽成了纯函数，生产与自检共用一个实现）：

| 常量 | 值 | 理由 |
|---|---|---|
| `MIN_AUDIBLE_DIRECT_GAIN` | **0.25**（-12.0 dB） | 现场两层石头墙是 0.153（-16.3 dB）"几乎听不见"。0.25 明显变小但内容还听得见；**仍低于一层玻璃的 0.407**，材质对比不塌 |
| `MIN_AUDIBLE_DIRECT_CUTOFF` | **0.02**（-34.0 dB） | 现场是 0.006（-44 dB）。0.02 仍是"隔着厚墙"的闷，但不再闷到像被静音 |

作用位置：**只给"遮挡算出来的值"设下限**，然后才乘水下（×0.1 / ×0.3）—— 水下故意允许更低。

### 修复前后对照（真实日志输入，生产代码实跑）

完整表格（含每行三组真实输入、每段 sendCutoff、预算、断言）见
[0.12.9-复算与自检输出.txt](0.12.9-复算与自检输出.txt)。摘要：

| 量（日志第 1 行：两层石头墙，occ=2.100 k=4.500 逐层反射率=0.488/0.459/0.488/0.431） | 0.12.8 | 0.12.9 |
|---|---|---|
| `directCutoff`（GAINHF） | 0.0050（-46.0 dB） | **0.0200**（-34.0 dB） |
| `directGain`（GAIN） | 0.1511（-16.4 dB） | **0.2500**（-12.0 dB） |
| `sendCutoff[0..3]` | 0.0001 / 0.0001 / 0.0001 / 0.0001（-82 dB） | **0.382 / 0.376 / 0.382 / 0.371**（-8.4 dB 上下） |
| 预算计入的能量 `E` | 1.3130（= Σ发送增益，没乘低通） | **0.1598**（= Σ gain×cutoff×0.32） |
| 直通 + E | 1.4641 | 0.4098 |
| `budget`（直通倍率） | 0.6830 | **1.0000（不削）** |
| **实际下发直通增益**（GAIN × budget） | **0.1032**（-19.7 dB） | **0.2500**（-12.0 dB，**+7.7 dB**） |
| 送进混音器的湿声能量 | 0.00004（≈ 无声） | 0.1598 |

通畅那一行（日志第 3 行，occ=0）：预算 `0.4279 → 0.7356`（"整体小声"那一半也一起修了）。
一层石头墙那行（occ=2.000）：预算 `0.6706 → 1.0000`。

### 断言（`tools/PhysicsParamsTest.java`，全部 PASS）

- 三组真实日志输入下 **每一项 `sendCutoff` ≥ 0.15**；最极端输入
  （occ=3.0 上限 + 反射率=0 + w=0，即"完全无反射"）仍有 **0.2271 ≥ 0.20** ⇒
  **不需要"除非无反射"的例外**（只有水下用第二道下限 0.10）。
- 通畅（occ=0）不再被压低：`0.840`（旧 1.000，仅 -1.5 dB）。
- 三组输入下 **`directGain ≥ 0.25` 且 `directCutoff ≥ 0.02`**；且仍 < 一层玻璃的 0.407。
- **直通倍率 ≥ 0.5**；且"有效能量 ≤ 1.0 时完全不削"。
- 通畅那一行的直通倍率 ≥ 0.70（旧 0.4279）。

### 新增日志字段（现场怎么核对）

`物理声效[M7]: …` 行尾新增 **`能量预算(直通倍率)=? 有效发送能量=?`**
（`EfxEngine.lastBudget()` / `lastSendEnergy()`）。
**日志里的 `直通增益` 是预算之前的值，必须乘上"直通倍率"才是真正写进 OpenAL 的值。**
预算真的削了东西时还会多一行（debug + 1 秒限频）：
`物理声效·能量预算: 有效发送能量=? 直通(削后)=? → 直通倍率=? 发送倍率=?`。

### 离线自检（0.12.9 新增 / 扩展）

| 工具 | 变化 | 结果 |
|---|---|---|
| `tools/PhysicsParamsTest.java` | **新增**。`Acoustics.directParams` / `Acoustics.sendCutoffFor` / `EfxEngine.energyBudget` **直接调生产代码**（不照抄公式），输入=用户日志三行 | 9 项断言全 PASS，退出码 0（`ALL CHECKS PASSED`） |
| `tools/AcousticsMath.java` | **扩展**：新增"0.12.9 可听下限"与"混响发送高频（Bug A）"两节，含 7 组输入的 OLD/NEW 对照与下限断言 | PASS（worst 0.2271 ≥ 0.20） |
| `tools/OcclusionWalkTest.java` | 回归（本版没动遮挡/几何） | 全通过 ✓ |
| `tools/ParamSmoothTest.java` | 回归（本版没动平滑/DSP 兜底） | 全通过 ✓（比值仍 1.0×） |

顺带修了一个**工具本身的坑**：`tools/printcp.init.gradle` 之前带 UTF-8 BOM，
本机 Gradle 用 GBK 读 init 脚本 → `Unexpected character: '?' @ line 1, column 1`，
classpath 取不出来（0.12.7 的成员大概是在别的机器上跑的）。现在改成**纯 ASCII、无 BOM**，
并在文件头写明原因。

### 装机与构建

- `gradle.properties`：`mod_version` 0.12.8 → **0.12.9**；`gradlew build` 成功
  （唯一 warning 是 0.12.5 起就存在的 `SoundEngineMixin` 方法映射提示）。
- 产物 `build/libs/clouddisc-0.12.9.jar`（334,464 B），
  `fabric.mod.json` 内 `"version": "0.12.9"` 已核对。
- 装机（**装前确认没有 Minecraft 进程**：`Get-CimInstance Win32_Process` 里只有两个
  Gradle 守护进程，没有 KnotClient/Fabric 客户端）：
  - `…\.minecraft\versions\1.20.1-Fabric 0.19.3\mods\`：删 `clouddisc-0.12.8.jar` → 放 0.12.9
  - `…\.minecraft\versions\1.20.1-Fabric 0.19.5\mods\`：删 `clouddisc-0.12.8.jar` → 放 0.12.9
  - 两处 `clouddisc-jukeboxlib-1.0.1.jar` **原样保留** ✓

### 已知限制 / 未验证清单（诚实版）

1. **听感没有验证**：本机跑不了游戏。上面全部是公式复算 + 断言。
   "混响终于听得见 / 隔墙够不够闷 / 够不够响" 只能由用户实测确认。
2. **"一层石头"和"两层石头"的高频被下限抹平了**：一层石头本来 0.0111、两层 0.0050，
   现在都是 **0.0200**。总增益仍有 0.407 / 0.250 的区别。若用户觉得"厚薄分不出来"，
   可把 `MIN_AUDIBLE_DIRECT_CUTOFF` 调到 0.01（代价：两层石头又回到"几乎听不见"）。
3. **极厚墙的总增益被抬上来了**：遮挡顶到上限 3.0 时 `directGain 0.067 → 0.250`
   （GAINHF 仍是 0.02）。这是"宁可闷、不许消失"的直接代价。
4. **混响发送高频没有按反射次数累乘**：每段用的是"该段那一层的平均反射率"乘一次，
   不是 `mat^n`。晚段的额外高频损耗只体现在各段自己的 `bandRefl[i]` 与 `decayHFRatio` 里。
   这是有意的简化（累乘会把第 4 段压暗得过度）。
5. **`sendGain` 一行没改**：本版只动"混响的高频"和"预算"，没动混响的量
   （`band0` 仍被 `clamp01` 顶到 1.000）。如果用户还是觉得"余响不够大"，
   下一步该调 `SEND_BOOST` / EAXReverb `gain`（那是另一个方向，本轮没碰）。
6. **0.19.3 实例里装着 `sound-physics-remastered`**：它会给所有声音挂遮挡滤波，
   测听感时建议先禁用，否则会把两份效果混在一起。
7. **`0.19.5` 实例的 mods 目录里有一个 `错误报告-2-10-2026_下午11.29.10.zip`**（历史崩溃报告），
   与本版无关，没有动它。

---

## 0.12.8

**修「0.12.7 遮挡恒为 0、声效全无」（0.12.7 的现场回归）**

### 现场证据（用户 0.19.5 实测，clouddisc 0.12.7，EFX 可用）

```
物理声效[M7]: source=3 EFX=可用(4段) 遮挡累积=0.000 主射线遮挡=0.000 通透通路=0/8 放宽=0.000
              k=4.500 直通截止(GAINHF)=1.000 直通增益=1.000 距离=2.5格 评估耗时=0.053ms
              ｜sendGain=[1.000, 0.330, 0.000, 0.000] 平均反射率=0.466 自由程=1.8格
```
（同一首歌期间多次采样，距离 2.5 / 7.9 / 14.0 / 18.4 格，遮挡累积全部 0.000。）

从这几行能确定的事实：
- 材质表是好的（同一轮里 `材质探针: minecraft:oak_door → 遮挡=0.44`）；
- EFX 是好的、混响射线**有命中**（自由程 1.8 格 ≠ 48 格），说明"格子查询 + 碰撞形状非空"这条链是通的；
- 坏的只有"主射线的遮挡累加"：`主射线遮挡=0.000`。
- ⚠ 一个**曾经误导排障的读数**：`通透通路=0/8` 看起来像"8 条偏移射线全被挡"，
  其实 `evaluate` 里只有在 `occMain > 0` 时才去算偏移射线 —— `occMain = 0` 时这个 0/8 **只是初值**，
  根本没算过。以后看这个字段先确认 `主射线遮挡 > 0`。

### 先排除的假设：坐标约定（**不成立**，但值得记下来）

怀疑"`VoxelShape.raycast(from, to, pos)` 要局部坐标、我们传了世界坐标 → 永远 null"。两条证据推翻它：
1. **反编译字节码**（loom 的 `minecraft-merged-1.20.1-*.jar`）：
   `VoxelShape.raycast` 先做"起点是否已在体素内"的快判（把 `pos` 从坐标里**减掉**变成局部），
   再调 `Box.raycast(Iterable, from, to, pos)`，而后者对每个盒子 **`box.offset(pos)`** 之后才与世界坐标求交。
2. **探针程序实测**（`VoxelShapes.cuboid(0,0,0,1,1,1)` + 世界坐标 → HIT；同一组数减掉 pos → null）。

⇒ 0.12.7 的调用**语义是对的**；问题在于"只依赖引擎的单一分支"这种写法本身：
它一旦在任何环境/版本上返回 null，整条遮挡链路就**静默变成"什么都不挡"**，没有任何兜底。

### 这一版改了什么（旧 → 新）

| 项 | 旧（0.12.7） | 新（0.12.8） | 位置 |
|---|---|---|---|
| 几何判定 | 只有 `VoxelShape.raycast(...) != null` 一条路 | **满格体素直接判挡**（单盒且 0..1 填满整格：石头/玻璃/原木/木板/关着的门）→ **不调用求交**；不满格/多盒（楼梯、栅栏、半砖、活板门、玻璃板）才**逐盒** `box.offset(pos).raycast(from,to)`；盒子列表拿不到才退回 `VoxelShape.raycast` | 新文件 `RayShape.java` |
| 起点在材料内部 | 求交返回 null（被当成"不挡"） | `box.contains(from)` 也算挡 | `RayShape.hits` |
| 兜底 | 无（静默归零） | 形状取不到 + 方块是"不透明完整方块" → 仍按挡算 | `BlockAcoustics.occlusionOnRay` |
| 累加逻辑 | 内联在 `Acoustics.occlusionAt` 的匿名 lambda 里（**没法单测**） | 抽成 `OcclusionWalk.accumulate` + `Cell` 接口，返回走格/实心/判定挡计数 | 新文件 `OcclusionWalk.java` |
| 单格判定 | 内联 | `BlockAcoustics.occlusionOnRay`（生产与自检**共用同一个函数**） | `BlockAcoustics` |
| 日志 | `物理声效[M7]: source=…` | 增加 `ver=0.12.8` 与 `主射线走格=N 实心格=M 判定挡=K` | `Acoustics.logDiagnostics` |
| 启动日志 | 无版本号 | `[CloudDisc] 版本 : 0.12.8` | `CloudDiscClient` |

**数值结论没有变**（0.12.7 那部分仍然有效）：门关 0.44 → 截止 0.138、门开 0 → 1.000、
一层石头 1.00 → 0.011、玻璃 0.20 → 0.4066、木板 0.55 → 0.0842；
放宽规则仍是"≥6/8 才放宽、最多削 40%"（`physicsOcclusionPaths=6` / `physicsOcclusionRelax=0.40`）。

### 新增离线自检（这次必须做，0.12.7 就是漏在这里）

`tools/OcclusionWalkTest.java` —— 用**真实的** `VoxelShape`/`Box`/`Vec3d`（Minecraft 自带的几何代码）
+ 一个"假世界"（`BlockPos → 碰撞形状 + 材质遮挡值`），跑**生产同一条**累加路径
（`OcclusionWalk.accumulate` + `RayShape.hits`），**不需要启动游戏**。实际输出：

```
射线 (0.5, 0.6, 0.5) → (0.5, 1.62, 3.0)（距离≈2.5格，穿过 z=1、z=2 两排格子）

① 1 格石头墙: 走格=4 实心=1 判定挡=1 遮挡=1.000
  PASS  1 格石头墙 → 遮挡累积 ≈ 1.00
  PASS  1 格石头墙 → 走格 > 0 且判定挡 = 1
② 2 格石头墙: 走格=4 实心=2 判定挡=2 遮挡=2.000
  PASS  2 格石头墙 → 遮挡累积 ≈ 2.00
③ 关着的门（薄门板 3/16 厚）: 走格=4 实心=1 判定挡=1 遮挡=0.440
  PASS  薄门板 → 遮挡累积 ≈ 0.44（射线垂直穿过门板）
④ 关着的门（满格单盒）: 走格=4 实心=1 判定挡=1 遮挡=0.440
  PASS  满格单盒 → 遮挡累积 ≈ 0.44（满格快捷判定生效）
⑤ 开着的门（空形状）: 走格=4 实心=0 判定挡=0 遮挡=0.000
  PASS  开着的门 → 遮挡累积 = 0.00
⑥a 半砖（射线从上方过）: 走格=4 实心=1 判定挡=0 遮挡=0.000
  PASS  半砖上方擦过 → 遮挡累积 = 0.00（但计入实心格）
⑥b 墙在旁边的格子 (1,0,1): 走格=4 实心=0 判定挡=0 遮挡=0.000
  PASS  射线旁边的墙 → 遮挡累积 = 0.00
⑥c 水平薄板（射线在其上方擦过）: 走格=4 实心=1 判定挡=0 遮挡=0.000
  PASS  与薄板平行擦过 → 遮挡累积 = 0.00
⑦ 5 格厚石头墙: 走格=3 实心=3 判定挡=3 遮挡=3.000
  PASS  超厚墙 → 遮挡累积被压在上限 3.00
⑧ VoxelShape.raycast: 世界坐标=true 局部坐标=false
  PASS  引擎求交要世界坐标（世界=命中、局部=null）
  PASS  RayShape.hits 用世界坐标也命中
  PASS  coversFullCell(满格) = true
  PASS  coversFullCell(半砖) = false
⑨ RayShape 与引擎求交的不一致项: （无）
  PASS  RayShape.hits 与 VoxelShape.raycast 结论一致

全部通过 ✓
```

跑法（文件头也写了）：`gradlew -I tools\printcp.init.gradle :printRuntimeCp -q` 取出
CPSTART/CPEND 之间的完整 classpath → `javac`/`java` 跑 `OcclusionWalkTest`
（要真实的 `VoxelShape`，因为 `VoxelShapes` 的 static 初始化链会拉 `Util` 和几个库）。

### 坐标约定审计（要求 ③：所有"世界坐标 ↔ 方块局部坐标"的地方）

| 位置 | 用法 | 结论 |
|---|---|---|
| `RayWalk.walk` | 纯数学，全程世界坐标（起点/终点/命中点 `from + d*t`） | ✅ 无坐标混用 |
| `RayWalk.cast` | 只用 `getCollisionShape(world, pos).isEmpty()`；返回的 `Hit.point()` 是世界坐标 | ✅ 没有坐标 API（"格子有碰撞体积就算挡"，粗但不会坐标错） |
| `Acoustics.occlusionAt` → `OcclusionWalk` | 世界坐标 `from/to` + `BlockPos p` | ✅ |
| `BlockAcoustics.blocksRay` → `RayShape.hits` | 世界坐标 + `getBoundingBoxes()`（局部）→ 自己 `offset(pos)` | ✅（满格判定比较的是局部 0..1，正确） |
| `BlockAcoustics.isFullCellShape`（探针诊断） | `getBoundingBox()` 是局部坐标，与 0..1 比较 | ✅ 正确（不需要减 pos） |
| `Acoustics` 偏移射线 | `center.add(off)` / `ear.add(off)` 两端同时偏移 | ✅ 世界坐标 |
| `Acoustics.traceReverb` / 共视测试 | `hit.point()`、`hit.normal()`（世界坐标 + 整数法线）、`hp = hit.point() + normal*0.002` | ✅ |
| `World#raycast` 系列 | 全项目**没有**使用 | ✅（当初就是为避开它的"续射重复计数"才自己写 DDA） |

### 用户怎么核对（新日志字段）

| 场景 | `遮挡累积` | `主射线走格` | `实心格` | `判定挡` | `直通截止(GAINHF)` |
|---|---|---|---|---|---|
| 一格厚石头墙 | **≈1.000** | ≥2 | ≥1 | ≥1 | **≈0.011**（听感：明显闷） |
| 门关着 | **≈0.440** | ≥2 | ≥1 | ≥1 | **≈0.138** |
| 门打开 | **0.000** | ≥2 | 0 | 0 | **1.000**（完全透亮） |
| 射线旁边的墙（没挡在中间） | 0.000 | ≥2 | 0 | 0 | 1.000 |

若仍然全是 0：看 `实心格` —— `实心格=0` 说明射线路径上真的没有实体（换个位置再测）；
`实心格>0 且 判定挡=0` 说明几何判定还有问题，把那行发回来。

### 已知限制 / 未验证（诚实版）

- **0.12.7 的现场根因没能本地复现**：本机跑不了游戏。离线自检（真几何 + 生产同一路径）是**通过**的，
  所以我**不能断言**"现场就是 raycast 返回 null" —— 只能说 0.12.7 那条路径**没有兜底**，
  而证据（材质表好、混响有命中、只有主射线累加为 0）与"几何判定静默失效"一致。
  0.12.8 让这种失效**不可能再静默发生**（满格体素不依赖求交），并加了可自证的日志。
- **满格快捷判定是"保守"的**：DDA 只在格子边界相切时也会算"穿过"，此时满格方块会被判挡（更闷一点）。
  这是 0.12.6 之前的行为，属于可接受方向。
- **听懂感仍然只能靠用户**：石头墙 0.011 / 门关 0.138 / 门开 1.000 都是数值，不是听感。

---

## 0.12.7

**修「门关着和门开着遮挡几乎一样」（真 bug）+ 顺手加固刺声**

用户实测反馈两条：
1. 「门关上时候和开着时候的遮挡一样几乎没有」（**真 bug，本轮主任务**）
2. 「会有像打响指一样的爆破声很短促像是 bip 一下」，随后补充「像是那种音频接触不良似的那种刺声」，
   最后又说「不对，像是耳机问题」→ 按"**顺手加固、不重构**"处理（见文末）。

### Bug 1 根因（三条，全部能在代码里指到行）

1. **漏音放宽规则作用在"两端各偏移 ±1 格的 8 条射线"上，且一过门槛就砍 85%**
   （0.12.6 `Acoustics.evaluate`）：

   ```java
   double relax = MAX_RELAX * Math.min(1.0, openPaths / (double) need); // 0.85 * min(1, 通路/3)
   occ = occMain * (1.0 - relax);
   ```

   - 公式形状是 **`occ = occMain × (1 - 放宽)`**（不是 `× 放宽`）；
   - 乘的位置在 `occlusionAt()` **返回之后**（即 `MAX_OCC = 3.0` 的 clamp 之后）、
     `exp(-occ*k)` **之前**；
   - `physicsOcclusionPaths` 默认 **3** ⇒ **8 条偏移射线里只要有 3 条通透，主射线算出来的遮挡只剩 15%**。
   - 关键：偏移射线是"整条线平移"，擦着墙边、过门口、过墙角都会变成"通透"，
     所以一扇**关着的门 / 一格厚的墙**旁边很容易凑够 3 条 → 门关着时 0.22 被削到 0.033 →
     `exp(-0.033×4.5) = 0.86`（约 **-1.3 dB** 高频）= 听感上"跟没挂一样"。
2. **关着的门本身也算得太小**（`BlockAcoustics`）：木门 `isOpaque()` 为 false（×0.8）、
   `isOpaqueFullCube()` 也为 false（再 ×0.5）⇒ 0.55 × 0.8 × 0.5 = **0.22** →
   高频截止 0.372（**-8.6 dB**），只是"稍微暗一点"。
3. **几何判定用的是"方块类型"，不是"射线实际有没有被挡住"**：
   `occlusionAt` 只判 `getCollisionShape().isEmpty()`（这一格有没有碰撞体积），
   再用 `isOpaqueFullCube` 打对折来"补偿缝隙"。两个后果：
   - 关着的门（有碰撞体积、但被判成"非完整方块"）被当成"一条缝"；
   - 楼梯/栅栏这种"有碰撞体积但射线能从缝里过"的格子被整格当成实心白算一笔。

> 关于用户猜的「DDA 是不是把门的碰撞体积当成'可通过'」：**不是**。
> `RayWalk` 只看碰撞形状是否为空，关着的门形状非空 ⇒ 门**是**被 DDA 记进累加的。
> 真正的问题在"记进去之后又被两条打折规则砍掉"，以及"放宽规则又砍 85%"。

### 改了什么（旧 → 新）

| 项 | 旧（0.12.6） | 新（0.12.7） | 位置 |
|---|---|---|---|
| 放宽公式 | `0.85 × min(1, 通路/3)`（3/8 就砍 85%） | **门槛 + 上限**：`通路 ≥ 需要数` 才放宽；`放宽 = 上限 × (通路-需要数+1)/(8-需要数+1)`，最多 `0.40`；**达不到门槛一分不放宽** | `Acoustics.evaluate` |
| 需要通路数 | 3（配置 1~9） | **6**（配置 1~8，默认 6/8） | `DEFAULT_OPEN_PATHS` / `physicsOcclusionPaths` |
| 放宽上限 | 0.85（写死） | **0.40**（新配置 `physicsOcclusionRelax`，0~0.60） | `DEFAULT_RELAX_MAX` |
| 是否累加遮挡 | 这一格有碰撞体积就算 | **射线与碰撞形状精确求交**（`VoxelShape.raycast`）才累加 | `BlockAcoustics.blocksRay` + `Acoustics.occlusionAt` |
| 门/非完整方块 | 材质值 × 0.8（非不透明）× 0.5（非完整方块几何） | **删掉 × 0.5 几何打折**（几何已由求交精确判定） | `BlockAcoustics.occlusionOf` |
| 玻璃基础遮挡 | 0.50（×0.8×0.5 = 实际 0.20） | **0.25**（×0.8 = 实际 **0.20**，听感与 0.12.6 完全一致） | `BlockAcoustics` GLASS 档 |
| 配置版本 | 5 | **6**（`physicsOcclusionPaths ≤ 3` 时迁到 6，尊重用户自己调过的更大值） | `CloudDiscConfig` |

**数值结果**（`tools/AcousticsMath.java` 实跑，见下方原始输出）：
门关着 **0.44 → 截止 0.138（-17.2 dB）**、门开着 **0 → 1.000（0 dB）**、
一层石头 0.011（-39.1 dB）、一层玻璃 0.4066（-7.8 dB）、一层木板 0.0842（-21.5 dB）。
**门关 vs 门开的高频差距：8.6 dB → 17.2 dB**；玻璃/木板/石头三档与 0.12.6 一模一样。

### ① 桌面复算（真跑了，不是推论）

```
$ java tools/AcousticsMath.java
openness shared airspace (representative avgShared) = 0.90
openness floor at shared=0.90 -> 0.1897 (0.12.5 used it unconditionally)

== 0.12.7 scene table: occlusion accumulated by the ray (no relax applied) ==
scene                  occ(0.12.7) occ(0.12.6)     GAINHF    HF dB     GAIN
door CLOSED (oak_door)       0.44       0.22     0.1381    -17.2    0.673
door OPEN   (oak_door)       0.00       0.00     1.0000      0.0    1.000
1x planks   (oak)            0.55       0.55     0.0842    -21.5    0.610
1x glass    (block)          0.20       0.20     0.4066     -7.8    0.835
1x stone    (block)          1.00       1.00     0.0111    -39.1    0.407
2x stone    (stacked)        2.00       2.00     0.0050    -46.0    0.165

  door CLOSED vs OPEN = the audible gap the user asked for:
    closed GAINHF 0.1381 (-17.2 dB) / OPEN 1.0000 (0.0 dB) -> 17.2 dB apart in HF
    old 0.12.6 closed GAINHF 0.3716 (-8.6 dB) -> only 8.6 dB apart ("almost the same")

== relax (leak) rule: old 0.12.6 vs new 0.12.7 ==
open/8       relax(old)   relax(new) occ@door0.44  GAINHF@door
0/8                0.00         0.00 0.44 (old 0.44)       0.1381
1/8                0.28         0.00 0.44 (old 0.32)       0.1381
2/8                0.57         0.00 0.44 (old 0.19)       0.1381
3/8                0.85         0.00 0.44 (old 0.07)       0.1381
4/8                0.85         0.00 0.44 (old 0.07)       0.1381
5/8                0.85         0.00 0.44 (old 0.07)       0.1381
6/8                0.85         0.13 0.38 (old 0.07)       0.1798
7/8                0.85         0.27 0.32 (old 0.07)       0.2341
8/8                0.85         0.40 0.26 (old 0.07)       0.3048

  the reported bug in one line: a 1-thick wall / a closed door easily gets 3-4 clear
  offset rays, so the OLD rule wiped ~85% of the occlusion while the door was CLOSED:
    open=3/8 : 0.12.6 GAINHF 0.7430 (-2.6 dB)  ->  0.12.7 GAINHF 0.1381 (-17.2 dB)
    open=4/8 : 0.12.6 GAINHF 0.7430 (-2.6 dB)  ->  0.12.7 GAINHF 0.1381 (-17.2 dB)
    open=5/8 : 0.12.6 GAINHF 0.7430 (-2.6 dB)  ->  0.12.7 GAINHF 0.1381 (-17.2 dB)

== acceptance table: door closed / door open / 1 stone / 1 glass / 1 planks ==
scene                   paths     GAINHF    HF dB     GAIN    gain dB
door CLOSED               0/8     0.1381    -17.2    0.673       -3.4
door CLOSED               8/8     0.3048    -10.3    0.789       -2.1
door OPEN                 0/8     1.0000      0.0    1.000        0.0
door OPEN                 8/8     1.0000      0.0    1.000        0.0
1x stone                  0/8     0.0111    -39.1    0.407       -7.8
1x stone                  8/8     0.0672    -23.5    0.583       -4.7
1x glass                  0/8     0.4066     -7.8    0.835       -1.6
1x glass                  8/8     0.5827     -4.7    0.898       -0.9
1x planks                 0/8     0.0842    -21.5    0.610       -4.3
1x planks                 8/8     0.2265    -12.9    0.743       -2.6
```

（后半段"0.12.5 对照"与"为什么 0.12.5 只是稍微暗一点"原样保留在工具输出里。）

### Bug 2（刺声）：排查了哪几条、各是什么结论

| 怀疑 | 查证 | 结论 | 0.12.7 做了什么 |
|---|---|---|---|
| 播放中途改 EFX **拓扑** | `EfxEngine.applyToSource` 旧代码在 `|g - lastG| > EPS` 时**也**重发 `alSource3i(AL_AUXILIARY_SEND_FILTER, ...)`；`AL_DIRECT_FILTER` 同样每次重发 | **成立**（每次数值变化都写一次拓扑） | 接线只在"换声源"那一次做；之后**只** `alFilterf` 改数值 |
| 参数**跳变** | `Acoustics.smooth()` 已经是按时间一阶平滑（tau = `SMOOTH_SECONDS` = **0.15 s**，`k = 1-exp(-dt/tau)`） | 已有平滑 ✓；但**第一次写**会把"1 刻的平滑量（≈28%）"一步套上 | 第一次写**先写成直通**（截止/增益=1、发送=0），之后交给平滑 |
| 切进/切出 **DSP 兜底** | 旧 `filterMono` 入口是 `if (EfxEngine.isAvailable() \|\| !isActive() \|\| ...)`，并且一"不活跃"就把 `lpState = 0`、`smoothCutoffHz = -1` | **成立**：`isActive()` 在"回到全通"时会变假、之后又变真，重新接通时滤波器状态从 0 开始 ⇒ **一个阶跃** | **DSP 链路全程接通**，参数自己滑到全通；再也不清状态。EFX 路径下仍然一个样本都不碰（第一行就 return） |
| **混响 slot** 被反复重写 | `setReverb` 每次都以 `alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_EFFECT, fx)` 结尾（重设 effect 到 slot 会重置混响尾音） | **成立** | 效果器**在 `init()` 里绑一次**；之后只 `alEffectf` 改参数；只有"换环境"级别的大变化才重绑一次做重同步 |
| **位置/方向** 写入 | `smooth()` 里 `posX/Y/Z` 已经按 tau=0.15s 平滑，`applyPosition` 还有 10cm 死区 | 已有平滑 ✓（时间常数 0.15 s） | 追加**写入限频**（每 3 刻） |
| 总能量**过载**（"直通 + 4 段发送"相加削顶） | 读代码：`AudioPipeline.loop` 里的归一化 + 软限幅（0.95 以上温和压缩）**在 `filterMono` 之外**，无条件执行 ⇒ **并没有**因为走 EFX 而被旁路。真正会越界的是 **OpenAL 混音器里"直通 + Σ发送"相加** | **部分成立**（前提要修正，见左） | 加**能量预算**：`budget = min(1, 1/(直通+Σ发送))`，直通与所有发送一起缩放，保证合计 ≤ 1.0；有余量时**不做任何衰减** |
| 解码缓冲**欠载** | `Acoustics.tick` 的调用链：`ClientTickEvents.END_CLIENT_TICK` → `SyncService.tick` → `PlaybackController.tick` → `Acoustics.tick`，**全在客户端主线程**；射线评估**不在**解码线程/音频线程上 | 评估不会阻塞解码 | `PcmRingBuffer` 加**欠载计数**；打开调试日志后，起播 1/5 秒那行会带 `缓冲剩余空间 Xms，欠载 N 次` |
| 写入**频率** | 旧 `EPS = 0.008`（约 0.07 dB）≈ 每 tick 都在写；`reverbDiffers` 阈值 0.02 | 成立（zipper 噪声来源之一） | 写入限频 `WRITE_INTERVAL_TICKS = 3`；`EPS = 0.02`（约 0.17 dB）；`reverbDiffers` 增益阈值 0.02→0.04、衰减时间 0.05→0.15 |

**新增诊断**（`physicsSoundDebug = true`）：任何写进 OpenAL 的参数一次变化 > `2 × EPS` 就打一行
`物理声效·参数阶跃: 直通.GAINHF 0.9000 → 0.1000（差 -0.8000，阈值 0.0400）`，最多 1 秒一条 ——
用户听到"bip/刺声"时对着时间点看这一行即可判定"是不是我们的参数阶跃"。

### ② 静态自检（真跑了）：参数阶跃 → 波形不连续

```
$ java tools/ParamSmoothTest.java
sample rate = 48000 Hz, duration = 0.6s, switch around 0.200s
click detector = |y[n]-2y[n-1]+y[n-2]|, worst case over 64 switch phases
0.12.7 smoothing time constant tau = 0.15s (k = 1-exp(-dt/tau))

(1) direct gain 1.0 -> 0.3
    instant write (old)    worst click  0.465 ( 46.5% FS) | natural  0.054 | ratio    8.6x
    time smoothed (new)    worst click  0.054 (  5.4% FS) | natural  0.054 | ratio    1.0x

(2) DSP fallback chain: bypass -> engaged (400Hz one-pole lowpass)
    state cleared (old)    worst click  0.614 ( 61.4% FS) | natural  0.054 | ratio   11.4x
    state kept (new)       worst click  0.006 (  0.6% FS) | natural  0.006 | ratio    1.0x
```

结论（**数学层面，不是听感**）：旧的"直写目标值 + 清滤波器状态"在**最坏相位**下会产生
**46.5% / 61.4% 满刻度**的一步跳变（是信号自身自然斜率的 **8.6× / 11.4×**），
0.12.7 的两处改法都把它压回自然底噪（1.0×）。**听感是否消失必须由用户确认。**

### 用户自查"刺声是不是本模组造成的"（三步，越简单越好）

a. 「强度」设 **0**（完全不挂 EFX）→ 还有刺声吗？设 1.0 / 2.0 → 刺声随强度变多/变响吗？
b. **同一首歌在游戏外用播放器**放（同音量、同设备）→ 也刺吗？
c. 换**输出设备**（外放 / 另一副耳机 / USB 声卡）→ 还有吗？

判定：**(b) 或 (c) 也刺 ⇒ 基本是他那边的耳机/声卡/驱动**，与本模组无关；
**只有 (a) 的 2.0 档才刺 ⇒ 那是我们的过载**（能量预算已经先减了一道，届时报给我再调）。
另外本机 0.19.3 实例里同时装了「**物理声效重制版**」（`sound-physics-remastered-fabric-1.20.1-1.5.1.jar`），
它会给**所有**声音（包括原版唱片）挂自己的遮挡滤波，测本 Mod 时**建议先禁用它**，否则听感会被它盖住。

### 已知限制 / 未验证清单（诚实版）

1. **听感未验证**：本机跑不了游戏，所有"变闷/透亮/刺声消失"的结论都只是**代码 + 数值**，必须用户实测。
2. **精确求交有开销**：`occlusionAt` 现在每遇到一个有碰撞体积的格子就做一次
   `VoxelShape.raycast`（主射线 + 8 条偏移射线，5Hz）。理论上更贵，
   日志里的 `评估耗时=…ms` 可核对（0.12.6 实测约 1ms/次）；若明显上升再考虑加"满格形状走快路径"。
3. **重绑 effect 的假设**：0.12.7 假设 OpenAL Soft 会立刻把 `alEffectf` 的变化应用到正在播放的声音上
   （所以不再每次重绑）。这一条**没能在真机上验证**；万一混响变成"改参数不生效"，
   现成的退路是把 `EfxEngine.boundDiffersALot` 的阈值调到很小（等价于每次都重绑）。
4. **能量预算是保守的**：EAXReverb 自己的 `gain = 0.32` 会再压一道湿声，所以实际衰减可能比需要的多一点点；
   代价是"混响多的场景总音量略降"，换来的是不会削顶。
5. **门的实测数值依赖方块真实碰撞形状**：工具里的 0.44 是"WOOD 0.55 × 非不透明 0.8"，
   与"门的碰撞形状到底是不是满格"无关（新规则只看"射线有没有真的穿过形状"）；
   但**射线擦着门边过**的时候可能判成"没挡住" —— 这是"精确"的代价，属于预期行为。
6. **0.19.5 实例通电运行中**：装机时旧 jar 被占用删不掉，用户需关掉游戏后手动删
   `...\1.20.1-Fabric 0.19.5\mods\clouddisc-0.12.6.jar`（0.19.3 已装好且干净）。

---

## 0.12.6

**物理声效调参 + 材质探针（M7）：让"隔墙变闷"明显到一耳朵能听出来**

用户反馈（0.12.5 实测）：**效果确实有 ✓，但"没那么激进，有些场景听不出来" ✗**。
本轮只调参 + 加诊断：**没有新增/删除 mixin，没有改 EFX 架构，没有改调用链**。

### 根因（两条，都能在代码里指出来）

1. **开阔度修正在最后一步把遮挡顶了回去**（0.12.5 `Acoustics.java` 原第 378 行）：
   ```java
   cutoffWithShared = max(sqrt(avgShared) * 0.2f, cutoffNoAir);
   ```
   这一条没有门槛。站在开阔房间隔一层石头墙时 `avgShared ≈ 0.9` →
   下限 `sqrt(0.9)*0.2 = 0.194`，而石头墙自己算出来的是 `exp(-1.0*3) = 0.0498`
   → 被 `max` 顶到 **0.194**。这正是用户描述的"只稍微暗一点（0.2~0.3）"。
   数值对照（`occ=1.0`）：`0.0498`（该有的）vs `0.194`（实际下发的）。
2. **8 条偏移射线"取最小值"**：只要有一条缝没被挡，`occ → 0` → `cutoff → 1.0`
   → 低通滤波器等于没挂。这就是"有些场景完全听不出来"。

第 3 条已知偏差（不是根因，但会放大上面的问题）：`gain = cutoff^0.1`，
`0.0498^0.1 = 0.741` → 隔墙后总音量只掉 2.5 dB，用户感知到的"闷"几乎全来自高频那一段。

### 改了什么（旧值 → 新值，逐条）

| 项 | 旧 | 新 | 位置 |
|---|---|---|---|
| 遮挡陡度 k（`exp(-occ*k)`） | 3.0（写死） | **4.5**（配置 `physicsAbsorption`，2.0~9.0） | `Acoustics` `ABSORPTION_DEFAULT` / `absorption()` |
| 开阔度修正门槛 | 无门槛 | `OPENNESS_GATE_OCC = 0.6`：遮挡 ≥0.6 时修正一律失效 | `Acoustics.OPENNESS_GATE_OCC` |
| 截止下限 | 0.02 | **0.005** | `Acoustics.MIN_DIRECT_CUTOFF` |
| 直通增益指数 | `cutoff^0.1` | **`cutoff^0.2`** | `Acoustics.DIRECT_GAIN_EXP` |
| 偏移射线规则 | 8 条取最小值 | **数通路**：`放宽 = 0.85 × min(1, 通路数/需要数)` | `Acoustics.MAX_RELAX` / `physicsOcclusionPaths` |
| 需要通路数 | 1（等价于任意一条即可） | **3**（配置 `physicsOcclusionPaths`，1~9） | `Acoustics.DEFAULT_OPEN_PATHS` |
| 石头反射率 | 0.52 | **0.60** | `BlockAcoustics` STONE 档 |
| 木板遮挡 / 反射率 | 0.82 / 0.45 | **0.55 / 0.30** | `BlockAcoustics` WOOD 档 |
| 沙土遮挡 | 0.75 | 0.70 | `BlockAcoustics` SAND 档 |
| 幽匿遮挡 | 0.65 | 0.55 | SCULK 档 |
| 黏液遮挡 | 0.60 | 0.50 | SLIME 档 |
| 树叶遮挡 | 0.35 | 0.30 | GRASS 档 |
| 兜底反射率（未识别材质/射线未命中） | 0.40 | **0.60** | `BlockAcoustics.DEFAULT` + `Acoustics` 两处兜底 |
| 混响发送基准 | ×1.0 | **×1.6** | `Acoustics.SEND_BOOST` |
| 衰减时间系数 / 下限 / 上限 | 0.25 / 0.25 / 4.0 | **0.40 / 0.45 / 6.0** | `Acoustics` `baseDecay` |
| reflectionsGain | `0.20+0.45r` | `0.25+0.55r` | `traceReverb` |
| lateReverbGain | `0.35+0.45r` | `0.40+0.55r` | `traceReverb` |
| EAXReverb 整体 gain | 0.24 | **0.32** | `traceReverb` + `EfxEngine.Reverb` |
| `EfxEngine.Reverb` 默认值 | 0.28/1.2/0.3/0.5 | 0.32/1.6/0.4/0.65 | `EfxEngine.Reverb` |
| `physicsSoundLevel` 语义 | 只乘发送增益 | **同时乘"遮挡陡度 k"与"发送增益"**；0 = 等同关闭（不做射线、干净直通） | `Acoustics.evaluate` |
| 配置版本 | 4 | **5**（新键自动补进 `clouddisc.json`，无需迁移逻辑） | `CloudDiscConfig.CURRENT_VERSION` |

**为什么木板是 0.82 → 0.55**：用户要求"玻璃/木板/石头三档能明确分辨"。
在 `exp(-occ*4.5)` 下，0.82 与 1.00 分别给出 0.025 / 0.011 —— 都在 -32 dB 以下，
人耳几乎听不出差别（都"死闷"）。改成 0.55 后三档分别是
**玻璃 0.407 / 木板 0.084 / 石头 0.011**（约 -8 / -21 / -39 dB 高频），落在三个可分辨的区间。

### ① 材质探针（运行时取证，这是新加的东西）

- `BlockAcoustics` 的每条材质档现在带一个**来源标签**（`Params.source()`，第 4 个分量），
  例如 `声音组 STONE(石头)`、`声音组 GLASS(玻璃/水晶)｜非不透明方块(遮挡×0.8)`、
  `默认值（未识别的声音组）`。
- `BlockAcoustics.probeLine(state, world, pos)` 把它拼成一行：
  `<方块注册名> → 遮挡=x 反射率=x 吸声=x（来源=…）`。
- `Acoustics.occlusionAt` 的 DDA 回调里调用 `noteProbe(...)`，**只记录真正参与了遮挡累加的方块**
  （空气、无碰撞体积的草/火把不会进来）；按注册名去重，每轮最多收集 24 条。
- `Acoustics.flushProbe(nowTick)` 在每轮评估末尾打出**最多 6 条**，且限频
  `PROBE_INTERVAL_TICKS = 20`（1 秒）—— 评估是 5Hz，不限频会一秒刷 30 行反而看不清。
  时间戳初值用 `Long.MIN_VALUE / 2`（教训 1）；探针失败整个包在 try/catch 里（教训 4 的"先取证"）。
  仅当 `physicsSoundDebug=true` 时收集，关掉时 `noteProbe` 只多一次 `if`。
- `Acoustics.dumpMaterialTableOnce(world)`：调试开着时，开局打一次**材质表自检**
  （`BlockAcoustics.sampleTable`，20 种常见方块，含来源标签）—— **不依赖任何射线命中**，
  是最快的一条证据。

### ② 静态自检（我真跑了的，不是推论）

对**重映射后的 1.20.1 yarn 客户端 jar** 跑 `javap`：

```
BlockSoundGroup 字段总数(javap, 1.20.1 yarn build.10): 103
BlockAcoustics 引用的字段数: 103
引用了但在 1.20.1 里不存在的字段: （无）
1.20.1 存在但材质表完全没引用的声音组（会掉进兜底默认值）: 0 个
未覆写 equals/hashCode → == 就是引用恒等比较，单例匹配可靠 ✓
public net.minecraft.sound.BlockSoundGroup getSoundGroup();                        (AbstractBlock$AbstractBlockState)
public boolean isOpaqueFullCube(net.minecraft.world.BlockView, net.minecraft.util.math.BlockPos);
```

结论：**材质表覆盖了 1.20.1 的全部 103 个原版声音组，一个都没漏**；
所以 0.12.5 那个"退到近石质默认值"的失败模式**不可能是"名字取不到/漏了某一组"**，
只可能是 `getSoundGroup()` 抛异常（`derive` 里被 catch → `g = null` → DEFAULT）
或别的 mod 注册了自定义声音组。**这两种情况材质探针都会明确打出来源=默认值。**
（本轮没有在游戏里跑过，所以"运行时到底命中哪一组"仍以探针日志为准。）

### ③ 验证状态（诚实）

- **实测**：`gradlew build` 通过（唯一 warning 是 0.12.5 就存在的
  `SoundEngineMixin` 的 `@At(INVOKE)` 映射告警，与本轮无关）；jar 已装机到两个实例；
  上面的 javap 静态自检；下面"数值对照"是我用 PowerShell 按公式算出来的（等于代码逻辑）。
- **推论**：所有听感结论 —— 我没有跑游戏的能力，**必须由用户实测**。
- **未验证**：`physicsSoundLevel=2.0` 时遮挡陡度 9.0 会不会"闷到听不清唱什么"；
  木板 0.55 是否偏亮。这两个都在"对照表"里留了旋钮。

### 数值对照（`tools/AcousticsMath.java` 实跑输出，非实测听感）

`java tools/AcousticsMath.java`（纯公式，不依赖 Minecraft）实跑结果，代表场景 `avgShared = 0.9`：

```
== 0.12.6 (new defaults: k=4.5, gate, gain^0.2, floor 0.005) ==
scene                         occ     GAINHF    HF dB       GAIN  gain dB
no blocker                   0.00     1.0000      0.0      1.000      0.0
1x glass (0.12.6 table)      0.20     0.4066     -7.8      0.835     -1.6
1x wood plank                0.55     0.0842    -21.5      0.610     -4.3
1x stone                     1.00     0.0111    -39.1      0.407     -7.8
2x stone (stacked)           2.00     0.0050    -46.0      0.165    -15.6
1x deepslate                 1.00     0.0111    -39.1      0.407     -7.8

== 0.12.5 (old: k=3.0, no gate, gain^0.1, floor 0.02) ==
1x glass                     0.20     0.5488     -5.2      0.942     -0.5
1x wood plank                0.55     0.1920    -14.3      0.848     -1.4
1x stone                     1.00     0.1897    -14.4      0.847     -1.4
2x stone (stacked)           2.00     0.1897    -14.4      0.847     -1.4

== why 0.12.5 felt weak: 1x stone wall ==
  raw exp(-1.0*3.0)            = 0.0498  (-26.1 dB) <- what physics says
  after unconditional openness = 0.1897  (-14.4 dB) <- what was actually sent
  0.12.6 sends                 = 0.0111  (-39.1 dB)
```

**这张表里最值得看的两行**：
1. 0.12.5 里"1x stone / 2x stone / 1x deepslate"的截止**全是 0.1897** ——
   不光是"只掉 14 dB"，而是**厚墙与薄墙、石头与深板岩完全没有区别**（全被那个下限吃掉了）。
2. 0.12.6 里三档材质拉开成 **0.4066 / 0.0842 / 0.0111**（玻璃/木板 差 13.7 dB，木板/石头 差 17.6 dB），
   而且"两层石头"能继续掉到下限 0.005（-46 dB），厚薄可分辨。

（`gain` 一列由 `cutoffWithShared^0.2` 算出，用的<b>不是</b>被下限截断后的截止，
所以"两层石头"的 GAIN 能继续掉到 0.165 而 GAINHF 已经贴在下限 0.005。这是有意的：
总音量还能继续表达"更厚"，高频只负责"很闷"这一档。）

**听感仍然没有被验证** —— 上表只证明"公式会下发这些数"，不代表耳朵一定听得出；
`physicsSoundLevel=2.0` 时石头墙后 GAIN 0.165（-15.6 dB）会不会"闷到听不清唱什么"，
以及木板 0.55 是否偏亮，这两个都在"对照表"里留了旋钮。

### 诚实记录：这条改动的副作用（"开阔度修正"对直通已经不再生效）

加了门槛 + 把 k 提到 4.5 之后，逐点算下来
`0.2*sqrt(0.9)*(1-occ/0.6)` **永远赢不过** `exp(-occ*4.5)`：

```
occ=0.05  floor=0.1897  cutoffNoAir=0.7985  -> 遮挡赢
occ=0.20  floor=0.1897  cutoffNoAir=0.4066  -> 遮挡赢
occ=0.37  floor=0.0000  cutoffNoAir=0.1892  -> 遮挡赢（且下限已归零）
occ=0.60  floor=0.0000  cutoffNoAir=0.0672  -> 遮挡赢
```

也就是说：**"同一片开阔空间里声音能绕过来 → 直通不该被压死"这条机制，对直通截止实际上停用了。**
- 这是<b>有意的</b>：用户明确要求"先保证隔墙变闷这条链路有强效果"，而这条机制正是把
  隔一层石头墙从 0.05 顶回 0.19 的元凶。
- 代码形状（`max(floor, cutoffNoAir)`）保留着：把 `k` 调回 2~3 或把 `OPENNESS_FLOOR_COEF` 调大，
  它就会重新起作用（`physicsAbsorption` 已经做成配置，所以用户能自己试出来）。
- 它<b>并没有整体消失</b>：`SharedAirspace` 仍然在混响那侧用着 ——
  `w0..w3`（4 个延迟带各自的权重）与 `openness`（DSP 兜底路径的 room/decay）都还是它算的。
  所以"开阔空间尾巴长、小房间短促"这条听感不受影响。

### 安全 / 兼容

- 所有新代码都在原有的 `try/catch` 与"只作用于我们自己的声源"前提下工作，**EFX 架构与调用链没动**。
- EFX 不可用时仍然永久回退 DSP；`physicsSoundLevel=0` 现在直接跳过射线（等价关闭 + 省性能）。
- 新增两个配置键；老配置由 Gson 补 Java 默认值（4.5 / 3），配置版本 4 → 5 只触发一次文件重写。

---

## 0.12.5 / 0.12.4 / 0.12.3 / 0.12.2 / 0.12.1

**唱片机物理声效：从自研 PCM DSP 换成 OpenAL EFX（M2→M6），DSP 保留为自动兜底**

### 为什么必须换（上一版的根因）

`0.10~0.12.0` 那套是在**自己的 PCM** 上做一阶低通 + 玩具 Schroeder 混响，而
`AudioPipeline` 每块约 4096 样本 ≈ 85ms，再叠加引擎预队列（`pumpBuffers(4)` ≈ 4 秒）：
参数"跟着音频块走" → 实测"拆了墙还闷好几秒"。另外那套只看"16 条射线挡没挡"，
没有材质、没有反射、没有方向 —— 上限就在那里。

新做法与 SPR 同层：把参数写进**引擎里那条 OpenAL 声源**
（`AL_DIRECT_FILTER` 低通 + `AL_AUXILIARY_SEND_FILTER` 到带 EAXReverb 的 aux slot），
由驱动在混音时应用 → **延迟只跟 tick 有关**。

### M2 —— EFX 基础设施 + 直通低通每刻写入（0.12.1）

- **挂点**（用 `javap` 核对过 yarn 名）：SPR 用的 Mojang 名
  `SoundEngine#loadLibrary` 里 `Listener#reset()` 那一句，在 yarn 1.20.1 里是
  `SoundSystem#start` 里 `SoundListener#init()`。
  `javap -c net.minecraft.client.sound.SoundSystem` 反汇编确认：`SoundEngine.init(...)`
  （建上下文）之后**紧接着**就是这一句 → 注入时 ALC 上下文已 current。
  refmap 解析结果：`start → class_1140;method_4846()V`、
  `Lnet/minecraft/client/sound/SoundListener;init()V → class_4227;method_19673()V` ✓
- **EFX 资源**（`EfxEngine.init`）：`alcIsExtensionPresent(device,"ALC_EXT_EFX")` 为假 → 打日志 +
  **永久回退 DSP**；否则建 `min(4, ALC_MAX_AUXILIARY_SENDS)` 个 aux slot + EAXReverb + 低通滤波器。
  常量全部用 LWJGL 具名常量（`EXTEfx.*` / `AL10` / `AL11`），无魔数。
- **每刻写入**：`AL_LOWPASS_GAIN/GAINHF` + `AL_DIRECT_FILTER`；同时把
  `AL_DIRECT_FILTER_GAINHF_AUTO` / `AL_AUXILIARY_SEND_FILTER_GAIN_AUTO` / `..._GAINHF_AUTO`
  置 false，保证"写进去的值就是最终值"。变化小于 0.008 就不重写（省 AL 调用）。
- **声源识别（硬红线：绝不误伤别人的声音）**：不再只靠"最近一次 Source#play 捕获的 id"，
  而是通过三个 accessor 精确反查：
  `SoundSystem.sources`（`field_18950`）→ `Channel.SourceManager.source`（`field_18941`）→
  `Source.pointer`（`field_18893`，与 SPR 的 ChannelAccessor 同一个字段）。
  拿不到就**什么都不做**（绝不去猜一个 id）；只有刚捕获 40 刻内才允许用兜底 id。
- **ALC 属性**：写了 `SoundEngineMixin`（`@Redirect` 掉 `alcCreateContext`）请求
  `ALC_MAX_AUXILIARY_SENDS = 4`。**这是量出来的，不是猜的** —— 独立 LWJGL 探针（不经 Minecraft，
  直接 `alcOpenDevice` + `alcCreateContext`）实测：
  ```
  ctx(default)             → ALC_MAX_AUXILIARY_SENDS = 2
  ctx(带属性请求 4 个发送)  → ALC_MAX_AUXILIARY_SENDS = 4
  ```
  安全阀：任何异常或返回 0 都立刻退回原样调用，绝不让声音引擎起不来。
  ⚠ **未在游戏内验证**：Mixin 0.8 支持同一指令上的多个 `@Redirect` 串联，SPR 也在同一句上
  做同样的事，理论上共存；但"两个 Mod 同时 redirect 同一句"这件事**我没有实测过**。
  若启动即崩且指向 `SoundEngineMixin`：从 `clouddisc.mixins.json` 的 client 数组里删掉
  `"SoundEngineMixin"` 即可（其余功能照常，只是混响段数退到 2 段）。

### M3 —— 材质表 + 沿连线逐块累加遮挡（0.12.2）

- **材质表**（`BlockAcoustics`）：自己推导，**没有抄 SPR 的方块配置表**（那是 GPL）。
  三元组 `(遮挡, 反射率, 吸声)`：
  1. 基础值按 `BlockState#getSoundGroup()` 的**恒等比较**分类（石/木/羊毛/玻璃/金属/沙/雪/黏液/幽匿/深板岩…，
     约 20 组）；
  2. `isOpaqueFullCube` 为假 → 遮挡 ×0.5（楼梯/栅栏/玻璃板…一条缝就该降下来）；
  3. `getFluidState()` 非空（液体）→ 遮挡 ×0.15、反射率 ≤0.08；
  4. 硬度 <0（基岩/屏障）→ 遮挡取 1.0；硬度 ==0（火把/作物/花）→ 遮挡 ×0.35；
  5. 特例：**草方块 / 湿草 / 苔藓块**用的是草木那组声音但**是实心方块**（opaque）→ 按地面算
     （否则一整块草方块会被当成灌木）。
  结果用 `IdentityHashMap<BlockState,Params>` 缓存（>8192 清一次防爆）。
- **沿连线逐块累加**：`cutoff = exp(-遮挡累积 × 3.0)`、`gain = cutoff^0.1`（与 SPR 同形）。
- **为什么不用 `World#raycast` 反复续射**：命中点正好落在方块表面，续射会把**同一个方块再算一遍**
  （1 格厚的墙算成 2 格）。改用自己写的 **DDA（Amanatides–Woo）体素遍历** `RayWalk`，
  一次就把"这条线穿过哪些格子、每个格子的入射面法线"全拿到，且**没有 epsilon 拍脑袋**。
- **非严格模式**（默认）：主射线被挡时，再把两个端点各偏移 ±1 格的 8 个对角点算一遍**取最小值**
  —— 门缝/窗缝/拐角能让声音透过来。主射线本来就通透时（最小值必然是 0）直接跳过，不白花钱。
- **`RayWalk` 已做独立单元验证**（不启动 Minecraft）：仓库里留了
  [`tools/RayWalkTest.java`](../tools/RayWalkTest.java)，跑法写在文件头。
  8 组断言全过：轴向 / 负向 / 体对角线 / 边界起点 / maxSteps / 斜射线的格子序列与法线、
  t 单调性、无重复格。
- **性能**：`MAX_OCC_STEPS = 96`（注意是**格子数**不是格数 —— 斜射一条 20 格的线最多穿 3×20 个格子，
  给少了会"远处的墙没算进来"这种静默错误）。命中遮挡累积 ≥3.0 立刻停。

### M4 —— 混响射线 + 1 个 EAXReverb slot（0.12.3）

- 从唱片机发射 `physicsRays`(默认 32) 条射线，方向用**黄金角球面均匀分布**（Fibonacci sphere，
  不需要随机数），按唱片机坐标旋转一个相位避免所有机器图案一样。
- 每条最多 **4 次反弹**，镜面反射 `dir - 2(dir·n)n`；命中距离用 DDA 的 t 精确算。
- `reflectionDelay = 路程 × 0.12 × 反射率`，用三角权重落进 **4 个延迟带**；
  单次能量 `0.25 × (反射率×0.75 + 0.25)`；带权 `{6.4, 12.8, 12.8, 12.8}`。
- **共享空气空间**：每个命中点再打一条"到耳朵"的射线，通 → 计入。只在**直通被挡时**才算
  （通透时开阔度本来就是 1，没必要花这个钱；每次评估最多 48 次）。
  然后 `directCutoff = max(sqrt(平均共享空气空间)×0.2, cutoff)`（SPR 同形），
  并给 4 个带各自的 sendCutoff：`exp(-遮挡×3)×(1-w) + w`。
- EAXReverb 参数由**测到的**平均自由程和平均反射率推：`decayTime ∝ 平均自由程`（大厅尾巴长）、
  `gainHF ∝ 平均反射率`（吸声房间更暗）。**只在变化超阈值时**才写（避免每 tick 重设造成杂音）。

### M5 —— 4 段发送 + 逐层反射率 + 距离衰减（0.12.4）

- 4 个延迟带各绑一个 aux slot（段号 i ↔ 发送序号 i，固定绑定，不每 tick 改发送拓扑）。
- **逐层反射率幂次修正**：第 2 层 × 反射率、第 3 层 × 反射率³、第 4 层 × 反射率⁴
  —— 越晚的带经过的反射越多，对"墙面吸不吸声"越敏感。
- 晚带加 3% 死区（低于就归零），避免一直挂一层听不清的嘶声。
- **距离衰减**：`1 - min(距离/48, 1)`（混响在 48 格外消失）。
- 强度系数 `physicsSoundLevel` 只乘在发送增益上（遮挡是"物理事实"，不受它影响）。
- EFX 只给了 2 段时，第 3/4 段**折回**前两段，不丢能量。

### M6 —— 方向性 + 水下 + 配置界面（0.12.5）

- **方向性**：把"共享空气空间命中点 → 耳朵"的方向按 `1/距离²` 加权求和，归一化后把声源位置移到
  `听者 + 方向 × 原距离` —— **到听者的距离不变，所以音量不跳**；只在该方向足够一致
  （模长 ≥0.5）且直通被挡时才移。位置同样按时间平滑，每刻写 `AL_POSITION`。
- **水下**：`player.isSubmergedInWater()` → 直通增益 ×0.1、截止 ×0.3、4 段 sendCutoff ×0.4。
- **配置界面**：新增第 2 节「物理声效」（总开关 / 强度 / 射线数 / 严格遮挡 / 方向性 / 调试日志），
  并在页面上直接显示 `EfxEngine.status()`（EFX 可不可用、几段）。界面从 4 节变 5 节。
- 新配置项：`physicsSound`(true) / `physicsSoundLevel`(1.0) / `physicsRays`(32) /
  `physicsSoundDebug`(false) / `physicsStrictOcclusion`(false) / `physicsSoundDirection`(true)；
  配置版本 3 → 4（Gson 对缺失字段保留 Java 默认值，无需迁移逻辑，但会重写文件把新键补上）。

### 诊断日志（每 10 秒一行；`physicsSoundDebug=true` 时每 1 秒）

```
[CloudDisc] 物理声效[M6]: source=3 EFX=可用(4段) 遮挡累积=1.000 直通截止=0.050 直通增益=0.741
  开阔度=0.00 距离=6.5格 水下=false 位置偏移=0.0格 评估耗时=0.412ms
  ｜sendGain=[0.31, 0.12, 0.00, 0.00] sendCutoff=[0.05, 0.21, 0.62, 0.62]
  逐层反射率=[0.52, 0.45, 0.41, 0.38] 平均反射率=0.44 自由程=3.2格
```
一眼能看出的东西：`EFX=可用(N段)` / `不可用→DSP`、遮挡累积、直通截止、sendGain、
`source=` 是不是我们那条、`评估耗时` 是否在预算内。

### 安全 / 性能

- 所有声学代码都包 try/catch：**出错绝不影响播放**；EFX 任何一步失败 → 永久回退 DSP。
- EFX 生效时 `Acoustics.filterMono` **一个样本都不碰**（避免双重滤波）。
- 射线采集按 4 刻（5Hz）限频，但**平滑 + 写声源是每刻**都做 → 参数延迟只跟 tick 有关。
- 平滑系数按时间算：`1 - exp(-Δ秒/0.15)`，不按调用次数。
- 带时间戳的限频初值一律 `Long.MIN_VALUE / 2`（**踩过两次**：用 `Long.MIN_VALUE` 会和
  nowTick 相减溢出 → 永远 return）。
- 预算估算：遮挡 ≤ 9 条 × 96 格、混响 32 条 × ≤4 次 × ≤96 格 ≈ 3000~10000 次格查询/次评估，
  实测耗时见日志（预期 0.3~1.5ms，每 4 刻一次）。

### 未验证 / 已知限制（诚实清单）

- **听感只能由人测**：我这边能验证的是"构建通过 / refmap 解析 / 单元测试 / 代码审查"。
- **`SoundEngineMixin` 的 `@Redirect` 与 SPR 同时生效**这件事没有被实测（见上）。
- **方块声音组 → 具体方块**的对应关系没有运行时验证（想 bootstrap 方块注册表跑探针，
  但重映射后的 jar 在 Fabric Loader 之外会因包私有成员不可访问而抛 `IllegalAccessError`）。
  失败模式是**退到默认值（近石质）**，不会崩溃、不会静音。
- **多台唱片机同时播放**：混响的 aux slot 是全局共享的，两台机器的混响参数会互相覆盖
  （直通遮挡/位置是按声源分开的，不受影响）。
- **DSP 兜底路径**是单一链路的（同一时刻只有一个会话的滤波状态生效），
  这与旧版行为一致。
- 混响段数取决于 `ALC_MAX_AUXILIARY_SENDS`：本机默认 2，请求后 4。

### 0.12.5 补丁（提交 `7bd36fb`）—— 总开关以配置为准 + 关闭时摘掉已挂上的 EFX

> 这一条在原来的 0.12.5 一节里漏记了，一并补上（依据：`git show 7bd36fb`，提交信息写得很具体）。

- **Bug 1：配置里的总开关重启后不生效**。`Acoustics.enabled` 只在 Java 里初始化成 `true`；
  界面把 `physicsSound` 关掉并写进 JSON 之后，**重启读回配置也没人去同步它** → "关了又自己开"。
  改法：总开关一律读配置（`Acoustics.java:236-242`：`cfg != null ? cfg.physicsSound : enabled`），
  配置还没加载好时才用静态值兜底；启动时同步一次
  （`CloudDiscClient.java:47` `Acoustics.setEnabled(config.physicsSound)`）。
- **Bug 2：播放途中关开关，"关了还是闷"**。EFX 参数写进 OpenAL 声源是**留在声源上**的
  （`AL_DIRECT_FILTER` / `AL_AUXILIARY_SEND_FILTER` 不会因为代码不再写就消失），
  所以必须**主动摘掉**：新增 `EfxEngine.bypassSource(int)`（`EfxEngine.java:450`），
  用 `wasEnabled`（`Acoustics.java:131`、`:283-285`）捕捉"开着 → 关掉"那一次转变，
  摘完打一行 `物理声效: 已关闭 → 摘掉 EFX / 恢复干净直通`
  （**这是 0.12.5 当时的文案**，现在的代码里已经查不到；0.12.7 把它拆成两行：
  `物理声效: 已关闭 → 先把参数平滑到直通，约 8 刻后摘掉 EFX`（`Acoustics.java:354`）与
  `物理声效: 参数已平滑到直通 → 摘掉 EFX / 恢复干净直通`（`:1158`）。）
- **验证**：`gradlew build` 通过（提交信息口径）；**界面行为与听感没有留档**，
  属于"用户反馈 → 改 → 请用户再确认"的工作方式。
- **后续**：0.12.7 又在"摘"之前加了"先把参数平滑到直通再摘"
  （`DISENGAGE_TICKS = 8` 刻，`Acoustics.java:141-143`、`:349-360`、`:1115-1160`）
  —— 因为"一刀摘掉"本身就是拓扑突变，可闻。

---

> **史料说明：0.10.0 ~ 0.12.0（先说清楚这份记录的依据）**
>
> 仓库里**没有 0.10.0 ~ 0.12.0 的任何提交**：`git log` 从 `0.9.9`（`d818e5a`）直接跳到
> `0.12.5`（`85bfe31`）。0.12.5 那条提交把"自研 PCM DSP → OpenAL EFX"**一次做完**（M2→M6），
> 连同 M1（0.12.0 的"捕获声源 id"）一起带进来 —— `SourceMixin.java`、`SourceAccessor.java`、
> `SourceManagerAccessor.java`、`SoundSystemAccessor.java` 都是在那一次提交里**首次出现**
> （`git show --stat 85bfe31`）。工作区里也没有 0.10/0.11 时代的构建产物
> （`build/libs`、`build/devlibs` 最早只有 0.12.5）。
>
> 所以下面几节的依据只有四条：① `85bfe31` 的提交信息（它自己写了动机与 M2~M6 的分工）；
> ② [物理声效实现计划.md](物理声效实现计划.md) 的里程碑表（第 54-70 行）与「教训」（第 249-253 行）；
> ③ **保留至今的 DSP 兜底代码**（`Acoustics.java:1243-1353`，段首注释原文就是
> "下面是 0.10~0.12 那套自研 PCM DSP"）；④ 本文件 `## 0.12.5 / …` 一节里那段回顾
> （"`0.10~0.12.0` 那套"、"16 条射线挡没挡"）。
> **凡是没有依据的细节，下面一律标「提交信息未说明」** —— 不猜。

## 0.12.0

**M1 —— 捕获我们自己声源的 OpenAL id（"本步不改听感"，为 EFX 铺路）**

### 目标与做法

计划文档给的验收只有一句话：**"日志出现 `捕获到声源 id=N`"**
（[物理声效实现计划.md](物理声效实现计划.md):58）。

| 环节 | 实现 | 位置 |
|---|---|---|
| 标记"下一个 play 的是不是我们" | `SoundSystemMixin` 在 `SoundSystem#play` 前调 `Acoustics.markNextOwnSource(sound instanceof CloudDiscSoundInstance)`，同时 `noteSoundSystem(this)` | `SoundSystemMixin.java:36-37`；`Acoustics.java:247`、`:252` |
| 认领 + 捕获 id | `SourceMixin` 在 `Source#play` 的 `@At("HEAD")` 调 `consumePendingOwnSource()`，命中就 `attachSource(this.pointer)` | `SourceMixin.java:28-33`；`Acoustics.java:257-271` |
| 日志 | `物理声效: 捕获到声源 id={}（EFX {}）` | `Acoustics.java:270` |
| 首选路径（0.12.1 起） | 按声音实例**精确反查**：`SoundSystemAccessor#getSources()` → `SourceManagerAccessor#getSource()` → `SourceAccessor#getPointer()`（`field_18893`，与 SPR 的 ChannelAccessor 同一字段） | `Acoustics.java:304` `resolveSourceId` |

### 为什么不能只靠"最近一次 play 捕获的 id"

Minecraft 的 OpenAL source 是**池化复用**的，而且"参数写进 source 就留在 source 上"
（0.12.5 的 Bug 2 就是这条性质的另一面）。拿错 id 的后果是**去改别人的声音** —— 这是红线。
取舍写在 `resolveSourceId` 的 javadoc 上：**"返回 0 表示暂时拿不到（声源还没建好 / 已经停掉），
调用方应跳过本轮"**（`Acoustics.java:301-303`），也就是**拿不到就不做，绝不去猜一个 id**；
`lastSourceId` 只在"刚捕获 40 刻内"才允许当兜底（见本文件 M2 一节）。

### 验证方式（诚实分界）

- **真跑过**：`gradlew clean build` 通过；构建产物 `clouddisc-refmap.json` 里逐条核对 accessor 的目标
  （同一份口径见下面 M2 的表：`start → class_1140;method_4846()V`、
  `Lnet/minecraft/client/sound/SoundListener;init()V → class_4227;method_19673()V` 等）。
- **没有留档**：0.12.0 当时的**运行期日志**（是否真的出现过 `捕获到声源 id=`）没有存档；
  本机跑不了游戏，"装机后看日志"这条只有用户侧反馈，而那条反馈没有留在这里。
- **可复核**：`git show 85bfe31 --stat` 能证明这些文件是"随 EFX 一起进来"的；
  `SourceMixin.java:12-21` 的类注释原文就写着"**本步不改任何听感**，只把 id 记下来并打日志"。

### 已知限制

- id 是引擎内部编号，**换世界 / 重建声音引擎后会变** → 每一轮都要重新捕获；
  代码里状态表按 source id 分开（`Acoustics.STATES`，`Acoustics.java:210`）。
- 拿不到 id 时**效果直接不出现**（不降级成"猜一个"）—— 有意为之。

---

## 0.11.3 / 0.11.2

**修延迟：平滑必须按时间，不能按音频块**

### 现象与根因

- 现象（用户实测口径）：隔墙 / 拆墙后"闷 ↔ 亮"要**数秒**才收敛，且**不同曲目、不同机器上时长不一样**。
- 根因：当时的平滑系数按**调用次数**（≈ 音频块数）推，而块长是可变的：
  `AudioPipeline` 每块约 4096 样本，48 kHz 下 ≈ **85.3 ms**；再叠加 Minecraft 引擎预队列
  （`attachBufferStream` → `pumpBuffers(4)` ≈ **4 秒**，见 [开发日志.md](开发日志.md) 案例 8）。
  ⇒ "走多少步"固定、"一步多长"可变 → 收敛时间漂移。
- 这条被写进教训清单：[物理声效实现计划.md](物理声效实现计划.md):251
  「平滑系数必须**按时间**算，不能按"调用次数"（音频块大小会变）」。

### 改法（旧 → 新）

| 项 | 旧 | 新 |
|---|---|---|
| 平滑系数 | 按调用次数 / 块数（固定步长） | `k = 1 - exp(-Δ秒 / 0.15)`（按时间） |
| 收敛时间 | 随块长漂移（≈85 ms 起，块越大越慢） | 恒定 ≈ **0.15 秒**（`SMOOTH_SECONDS`） |

### 代码位置（现存对应物）

- 常量 `SMOOTH_SECONDS = 0.15f`：`Acoustics.java:53`。
- 计算：`Acoustics.java:1285`（`filterMono` 内）
  `float k = (float)(1.0 - Math.exp(-(double)frames / (SMOOTH_SECONDS * sampleRate)));`
  —— `frames / sampleRate` 就是"这一块有多长（秒）"，所以块长只影响"每步走多远"，不影响总收敛时间。
- 注：`filterMono` 今天是 **EFX 不可用时的兜底路径**，但这段是 0.11 那套代码**原样保留**的
  （段首注释 `Acoustics.java:1244`），所以时间常数这条修复现在仍可查。

### 0.11.2 与 0.11.3 各改了什么

- **提交信息未说明**（两版都没有提交），文档里也没有分开记：现存证据只有教训清单的那一条。
  按版本号顺序**推测**：0.11.2 先把"按块数"改成"按时间"，0.11.3 再做补充
  （例如位置 / 增益分别限频之类）。**这属于推测，不要当结论用。**

### 验证方式（诚实分界）

- **真跑过的**（注意时间线）：`tools/ParamSmoothTest.java`，**但它是 0.12.7 才写的**。
  48 kHz、0.6 秒信号、64 个切换相位，用二阶差分 `|y[n]-2y[n-1]+y[n-2]|` 当"可闻不连续"探针：
  ```
  (1) 直通增益 1.0 -> 0.3   即时写     worst click 0.465 (46.5% FS)  自然 0.054  比值 8.6x
                            按时间平滑 worst click 0.054 ( 5.4% FS)  自然 0.054  比值 1.0x
  ```
  （原始输出见 [0.12.8-复算与自检输出.txt](0.12.8-复算与自检输出.txt) 第 3 节）
- **它证明的是"按时间平滑"这条路是对的，不能当成 0.11.2/0.11.3 当时的现场证据**
  —— 当时的自检没有留档（本机也跑不了游戏）。

### 已知限制

- 只要效果还做在**我们自己的 PCM** 上，参数就必然受"块（≈85 ms）+ 预队列（≈4 秒）"限制；
  这条限制到 0.12.1 换 EFX 才从根上消失（参数直接写 OpenAL 声源，延迟只跟 tick 有关）。

---

## 0.11.1

**修 `Long.MIN_VALUE` 相减溢出 → 评估永不执行**

### 根因（纯算术，可静态验证）

- 限频写法形如 `if (nowTick - lastEvalTick >= INTERVAL)`，而 `lastEvalTick` 初值取了 `Long.MIN_VALUE`。
- `nowTick - Long.MIN_VALUE` 在 long 上**溢出为负**（`nowTick` 再大也补不回来）
  ⇒ 条件恒假 ⇒ **评估永远 return**，物理声效完全不出现。
- 这条成了项目第一条教训，代码里就写着：`Acoustics.java:1059`
  「【教训 1】带时间戳的限频代码，初值必须是 `Long.MIN_VALUE/2`，否则减法溢出 → 永远 return。」
  以及 [物理声效实现计划.md](物理声效实现计划.md):250。

### 改法（旧 → 新）

| 项 | 旧 | 新 |
|---|---|---|
| 时间戳初值 | `Long.MIN_VALUE` | `Long.MIN_VALUE / 2`（留出相减空间） |

修正后的写法今天可以逐个点出来：`Acoustics.java:143/152/166/167/168/207/936`、
`EfxEngine.java:62/72/74/214`、`JukeboxSession.java:43/60/61/69/71/73`。
另有一处干脆换了口径：`ChatTransport.java:34` 用"毫秒 + 0 哨兵"，注释写明
"（而不是 `nanoTime + Long.MIN_VALUE/2`）是因为后者在相减时会溢出"。

### 现象与验证（诚实）

- **现象未留档**：`git log` 里没有 0.11.1（连提交都没有），当时的日志 / 复现步骤都没有留存。
  按根因推断表现是"物理声效**完全不生效**（评估从来没跑过）"；
  **"是不是偶发、要不要重进世界"没有依据**。
- **验证方式**：当时没有自检；今天是**静态可验证**的（把初值换回 `Long.MIN_VALUE`，
  在 `nowTick` 为正数时相减必然溢出为负 —— 纯算术事实）。`tools/` 下没有针对它的单测。

---

## 0.11.0

**物理声效第二版：自研 PCM DSP（一阶低通 + 玩具 Schroeder 混响 + "挡没挡"射线）**

### 这一版做了什么

- 在 0.10.0 的"射线 → 遮挡 → 低通"之上补了**自研混响**与**开阔度**：参数层算出
  `directCutoff / directGain / sendGain[0] / openness`，应用到我们自己的 PCM 上。
- 计划文档与 0.12.5 一节的回顾把它写作：**"一阶低通 + 玩具 Schroeder + 只看'隔没隔东西'"**
  （[物理声效实现计划.md](物理声效实现计划.md):3），以及
  **"那套只看 16 条射线挡没挡，没有材质、没有反射、没有方向 —— 上限就在那里"**
  （本文件 `## 0.12.5 / …` 一节，第 490-493 行）。

### 代码位置与常数（现存 = 当年的兜底路径，未删除）

| 部件 | 常数 / 公式 | 位置 |
|---|---|---|
| 直通低通（一阶 IIR） | `MIN_CUTOFF_HZ = 450`、`MAX_CUTOFF_HZ = 20000`；截止按遮挡度在对数区间插值 `targetHz = MAX × (MIN/MAX)^(1-cutoff)` | `Acoustics.java:126-127`、`:1283` |
| 低通系数 / 状态 | `a = 1 - exp(-2π·f/fs)`；`y += a·(dry - y)` | `Acoustics.java:1292`、`:1299-1309` |
| 混响（Schroeder） | 4 个并联梳状 **29.7 / 37.1 / 41.1 / 43.7 ms**（`COMB_SECONDS`）+ 2 个串联全通 **5.0 / 1.7 ms**（`ALLPASS_SECONDS`） | `Acoustics.java:1250-1251`、`reverbSample` `:1313-1335` |
| 混响反馈 | `room = 0.6 + 0.9×openness`、`decay = 0.35 + 0.55×room`、`feedback = 0.001^(1/(decay·fs/1000·1.5))` | `Acoustics.java:1295-1297` |
| 湿声量 | `wet = sendGain[0]`（DSP 只有 1 条混响链路） | `Acoustics.java:1294`、`:1304-1306` |
| 处理入口 | `Acoustics.filterMono(buf, offset, frames, sampleRate)`，在**已下混、已归一化**的样本上调用 | `Acoustics.java:1272`；调用点 `AudioPipeline.java:236`（注释 `:232-235`） |
| 遮挡口径 | "挡没挡"（文档回顾：**16 条射线**）；**材质表要到 0.12.2 的 M3 才有**，与 0.10/0.11 不是同一条路线 | `Acoustics.java:1244` 段首注释 |

### 为什么最后放弃了它（这一节最该记住的结论）

- **延迟**：参数跟着音频块走（≈4096 样本 ≈ 85 ms，外加引擎预队列 ≈ 4 秒）
  → "拆了墙还闷好几秒"。这是 0.11.2/0.11.3 修了一半、**没有根治**的那条。
- **能力**：只知道"这条线上挡没挡" —— **没有材质、没有反射、没有方向**，公式形状再怎么调也就那样。
- 结论写在计划文档开头：**"上限就在那里"**；0.12.1 起换成 OpenAL EFX，DSP 转为**自动兜底**
  （`EfxEngine.init` 失败 → `EfxEngine.java:232` 打日志；`Acoustics.filterMono` 第一行
  `if (EfxEngine.isAvailable()) return;`，`Acoustics.java:1273-1275`）。

### 验证方式

- **没有当时自检留档**。今天可复核的只有：① 代码本身（`Acoustics.java:1243-1353`）；
  ② 0.12.7 补的 `tools/ParamSmoothTest.java` 第 (2) 组断言
  （"DSP fallback: bypass → engaged（400 Hz 一阶低通）：清状态 worst click 0.614 / 保留状态 0.006"）
  —— 它验证的是 **0.12.7 对这条老链路的状态处理**，不是 0.11.0 的听感。

### 已知限制（当时与今天相同）

- **单链路**：`dspState` 只有一个（`Acoustics.java:212`），同一时刻只有"最后一个被评估的会话"的滤波状态生效。
- EFX 可用时 `filterMono` **一个样本都不碰**（避免双重滤波）。
- 参数做在"我们自己的 PCM"上 ⇒ **引擎预队列里已经排进去的那约 4 秒音频无法回改**。

---

## 0.10.0

**物理声效第一版：射线 → 遮挡 → 低通（链路走通，效果粗糙）**

- 做了什么：从唱片机到耳朵打射线，算"中间隔了多少" → 0~1 的遮挡度 → 对**我们自己的** PCM 做低通。
  "隔墙变闷"第一次出现：**没有混响、没有开阔度、没有材质**。
- **常数 / 口径：提交信息未说明**（0.10.0 没有提交；现存代码里这一段已被 0.11/0.12 的写法覆盖）。
  唯一能查到的"最早口径"是遮挡陡度 **k = 3.0 写死**：
  `Acoustics.java:57` 注释「0.12.5 是固定 3.0」、`CloudDiscConfig.java:136`「0.12.5 是写死的 3.0」。
- **配置项：提交信息未说明**。现存配置里物理声效相关的键最早在 **0.12.5 的配置版本 3 → 4** 里成组出现
  （迁移注释见 `CloudDiscConfig.java:298-307`；`CURRENT_VERSION = 6` 在 `:27`），
  再往前没有迁移痕迹 —— 所以"0.10/0.11 时代有没有开关、叫什么名字"**无法确认**。
- **验证方式**：无留档。
- **顺带说明**：这三份日志此前都只记到 0.9.9 / 0.9.8，**0.10.0 ~ 0.12.0 这一段是本次补齐的**；
  0.12.1 起的 EFX 一版记在 `## 0.12.5 / 0.12.4 / 0.12.3 / 0.12.2 / 0.12.1` 一节。

---

## 0.9.9 / 0.9.8

**修复中继通道周期性静默失效（跟随方回落原版的真根因）**

- 日志证据（服务器联机）：三次放碟前中继状态分别为 `relay=1, chat=1`（正常）、`relay=1, chat=0`、
  `relay=0, chat=0`（✗）；`relay=0` 的两次跟随方听到原版；20:56 放碟时 `udp=0, relay=0, chat=0`。
- 机制：`isReady()` = "距上次 ACK < ACK_TTL(60s)"，而 `SyncService` 只在 `!isReady()` 时补发 HELLO，
  且 `sayHello()` 受 `MAX_HELLO_ATTEMPTS = 20`（每 3 秒一次 ≈ 7 分钟）限制 → 撞上限后**永久停止续报**。
  只有"退房重进"会 `resetAttempts()` —— 与"退房重进有时好使"完全吻合。
- 修法：`ACK_TTL_MILLIS` 60_000 → 600_000；ACK 时 `helloAttempts = 0`；
  `SyncService` 改为**无条件**每 400 刻（20 秒）续报。
- 服务端组件未改动（`CloudDiscServer` 自 0.4.4 起稳定，频道名/首字节/约定版本为冻结接口）。
## 0.9.6 / 0.9.5 / 0.9.4

**补上"解析阶段无人应答"的空窗 + 令牌可视化**

- **根因**（0.9.4）：`sessions` 是在**解析完成后的回调**里才 `put` 的，而解析（查真名 + 换 URL）要 1~3 秒。
  这段窗口内收到 `T_QUERY` 时 `sessions.get(key) == null` → 不回话 →
  询问方在 `HOLD_TICKS_UNCLAIMED`（原为 6 刻 = 300ms）后触发 `fallbackVanilla` → 听到原版声。
  0.6.6 加的"未排定开始刻就回 CLAIM"覆盖不到这一段，因为那时**会话对象还不存在**。
- **修法**：把 `pending`（右键即创建、原先在 1010 时被立刻 `remove`）当作"我占着这台唱片机"的凭据：
  - `onWorldEvent(1010)` 改为 `pending.get(key)` 而非 `remove`
  - `T_QUERY` 在无会话时走新增的 `claimFromPending()` → 回 CLAIM 延长对方等待
  - 解析成功/失败两条路径都显式 `pending.remove(s.key)`，不留残留
  - `HOLD_TICKS_UNCLAIMED` 6 → 40（0.3s → 2s），给解析留时间
- **0.9.5**：配置界面新增令牌栏位（`tokenOf()` / `setToken()` 与 `httpHeaders` 的 X-Token 双向映射），
  `ROW_STEP` 20 → 18 以容纳网络页第 9 行；两份配置的 URL 统一去掉了内嵌 `?token=`
- **仍待解决**：首次插入若右键未被识别（`待确认插入=false`，疑为"机内有碟时换碟"的右键路径），
  当前只会让它在 2 秒后回落原版；根因未查清，方向是"看到 1010 就直接读方块实体里的碟名"
## 0.9.3

**修复 lib 在单人/局域网不生效（Fabric 环境标记的经典坑）**

- 现象：单人游戏里装了 `clouddisc-jukeboxlib` 仍然被唱片时长掐断。
- 根因：lib 的 mixin 配置写成 `"server": [...]`、`fabric.mod.json` 里 `"environment": "server"` ——
  这个 `server` 指的是**独立服务端**；而单人/局域网的"服务端"是**客户端进程里的内置服务端**，
  物理环境仍是 CLIENT → Mixin **根本不会被应用** ✗
- 修法：mixin 配置改为通用数组 `"mixins": [...]`、`fabric.mod.json` 改为 `"environment": "*"` ✓
  （在多人客户端上应用它无害：`isSongFinished` 在客户端侧本来就不会被调用）
- 验证：refmap 仍解析出 `isSongFinished → class_2619;method_44372(class_1813)Z` ✓；
  实测（单人、最短唱片 + 4 分钟长曲）完整播完 ✓、拔碟立刻停 ✓
- 版本：附加组件 **1.0.1**；音乐 mod **0.9.3**（仅更新日志文本变化）
## 0.9.2 / 0.9.1 / 0.9.0

**服务端可选组件解决"长曲被唱片时长掐断"，音乐 Mod 回归干净行为**

- **先纠正一个错误假设**：0.8.1 曾用方块状态 `JukeboxBlockEntity` 的 `has_record` 区分
  "自然放完"与"玩家拔碟" —— 实测**失败**：原版自然放完时 `has_record` 同样变 false，
  两种情况无法用它区分；更糟的是它导致"拔碟不停、换碟不生效"。
- **另一个真根因**：随 0.8.0 引入的 `durationMs` 当时恒为 `0` ——
  旧接口 `api/song/detail` **不返回 `dt`**，于是所有"知道真实时长"的判断都不成立。
  改为先问本机社区项目（`/song/detail` 一定带 `dt`），实测：`188204 → 248964ms` ✓
- **挂点用一手源码 + refmap 双重确认**（不再靠猜）：
  - 反编译 `JukeboxBlockEntity` 得到判断式 `worldTime >= recordStartTick + disc.getSongLengthInTicks() + 20`
  - Yarn 名核实：`JukeboxBlockEntity#isSongFinished(MusicDiscItem)`（private）、
    `#startPlaying()`、`#getStack(int)`、`MusicDiscItem#getSongLengthInTicks()`
  - 构建产物 refmap 确认解析成功：
    `isSongFinished → class_2619;method_44372(class_1813)Z`、`startPlaying → class_2619;method_49212()V`
- **服务端组件**（独立 jar `clouddisc-jukeboxlib`，6 KB）：`@Inject(HEAD, cancellable)` 到
  `isSongFinished`，对"自定义名以 `@` 开头"的唱片返回 `false`；用 `@Unique` 记开始刻并设
  延长时间上限（默认 6 分钟），避免碟不取出时方块永远停在"播放中"（音符粒子/GameEvent 持续发）。
  原版唱片路径完全不变 ✓
- **音乐 Mod 侧**：
  - 收到 1011 一律按原版语义停止（拔碟即停、换碟正常）—— 不再做任何猜测
  - "到真实结尾收尾"改为**无条件生效**，时长优先用解析服务给的 `s.durationMs`，
    否则退回 `pipe.durationMs()`；**不再用 `pipe.playedMs()`**（它比真正在响的位置超前约 4 秒）
  - `fabricloader` 依赖由 `>=0.16.10` 放宽到 `>=0.15.0`（服务端 0.15.11 才能真正加载本 jar）
## 0.7.0

**工程化：GitHub 托管 + 日志分表里**

- 仓库整理为可直接推送 GitHub 的形态：新增 `.gitignore`、`.github/workflows/build.yml`
  （推 tag 自动构建并创建 Release，朋友直接下 Release 链接即可）
- 发布前做了敏感信息扫描：仓库内**不含**服务器地址、令牌、cookie（探针脚本里硬编码的
  一个 IP 也已改成 `127.0.0.1`）
- 更新日志拆分为 **公开简版**（`CHANGELOG.md` 与 jar 内 `changelog.txt`）与 **内部详细版**（本文件）
- 播放与同步逻辑未改动

---

## 0.7.0

**工程化：GitHub 托管 + 日志分表里**

- 仓库整理为可直接推送 GitHub 的形态：新增 `.gitignore`、`.github/workflows/build.yml`
  （推 tag 自动构建并创建 Release，朋友直接下 Release 链接即可）
- 发布前做了敏感信息扫描：仓库内**不含**服务器地址、令牌、cookie（探针脚本里硬编码的
  一个 IP 也已改成 `127.0.0.1`）
- 更新日志拆分为 **公开简版**（`CHANGELOG.md` 与 jar 内 `changelog.txt`）与 **内部详细版**（本文件）
- 播放与同步逻辑未改动

# Changelog · 歪歪网易云唱片 CloudDisc

> 忠于原版，高于原版。

本文件是**面向读者**的更新历史；游戏内的「配置界面 → 第 4 节 更新日志」是同一份内容的节选。
想看"每个结论是怎么查出来的"，请读 [docs/开发日志.md](docs/开发日志.md)；
想看原始测量数字，请读 [docs/实测数据.md](docs/实测数据.md)。

版本号在 `gradle.properties` 的 `mod_version`；每次改动都会在这里追加一节。

---

## 0.6.8

**缓存上限生效 + 写清"用自己的服务器解析 VIP 歌"的做法**

- `cacheMaxMb`（默认 512 MB，0 = 不限）现在真正生效：每次拿到新文件后整理缓存，
  超过上限按**最久未使用**清理到 80% 以下，并顺手删掉异常退出留下的 `.part` 临时文件
- 澄清一件容易被误解的事：**缓存从一开始就在每个玩家自己的机器上**
  （`游戏目录/config/clouddisc/cache`），**不占服务器硬盘**。
  连"解析服务器"也不需要落盘 —— 它只负责换地址
- 教程「进阶」新增一节：**用一台自己的服务器专门解析 VIP 歌**
  - 与"MC 服务器是谁的"完全无关：mod 只请求你配置的 HTTP 地址
  - 分工：你的服务器只换地址（纯内存）；下载与缓存都在玩家本机
  - 两种返回模式：返回地址（最省流量，先试这种）vs 直接回字节流（网易地址绑定 IP 时的兜底）
  - 安全：加长随机 token、绑 VPN/内网、日志不要打印 cookie
  - 维护成本：账号 cookie 会过期需要偶尔更新；建议只和朋友自用

## 0.6.7

**"VIP / 独家曲目怎么办" 写清楚（只改文案与教程，逻辑未动）**

- 抓取失败时的提示不再是"拿到的是 text/html"，而是**可直接照做的两条路**：
  A) 用你自己账号的解析服务；B) 直接把音频文件放进 `clouddisc-music/` 用 `@local:`
- 教程「进阶」一节补上**自建解析服务的接口契约**：返回 `{"title":"…","url":"…"}` 或直接回音频字节，
  `{id}` 占位；并强调 **cookie 只留在你自己的服务里**（本 Mod 不保存、不打印、不同步凭据），
  以及**别把这个服务裸露在公网**
- 明确写出本 Mod 不做的事：不内置第三方接口或共享 cookie、**不解密 `.ncm`/`.uc` 等受保护格式**、
  不提供"免费 VIP"那类做法（法律风险 + 接口一变就废 + 无法正常分发）

## 0.6.6

**两处单点修复（都不碰网络层 —— 吸取 0.6.4 的教训）**

### ① "先出一会原版音乐，然后被网易云替代"

- **机制**：未认领等待窗口只有 **6 刻（300 ms）**，而被询问方在**预取阶段**会拒绝应答
  （0.2.2 的修复：避免给出 `startTick=0` 的垃圾调度） → 询问方等不到任何回应 → 按设计回落原版
  → 1~2 秒后我们的 `PLAY` 才到 → 接管。听感就是"先响一下原版"。
- **修法**：持有会话但**尚未排定开始刻**时，回一个 **CLAIM**（语义正是"这块归我，先别放原版"），
  对方据此把等待延长到 140 刻，安静等我们的 `PLAY`。
- 只改了"应答"这一处；**未认领窗口保持 6 刻**，所以原版唱片该有的即时播放不受影响。

### ② "退出世界再回来偶发不播放"

- **机制**：进服后固定询问 3 次（1/3/10 秒）。若这 3 次恰好都撞上"此刻还没发现同伴"（消息被丢弃），
  **就再也不会重试** → 静音。这就是"时好时坏"的来源。
- **修法**：改为**问到有结果为止**（每 2 秒一次，最多 30 秒）；一旦本机建立了会话就停止询问。

## 0.6.5

**【回滚】0.6.4 的网络层改动实测把同步改坏了**

- 整段退回 0.6.3 的网络层行为：
  - 发送目标恢复为"当前有效对端"（去掉 0.6.4 加的"最近 60 秒见过的地址"那一层）
  - 对端登记恢复为按地址（保留实例 id 去重）
  - 进服询问恢复为 3 次（1 / 3 / 10 秒），去掉 0.6.4 加的"对端出现就补问"
- **保留**：0.6.3 的"接管前先停掉原版那条"修复，以及 0.5 / 0.6.0–0.6.3 的全部功能
  （真名显示、HUD 曲名、日志降噪、服务端中继、配置界面）
- **诚实说明**：0.6.4 想修的"退世界回来偶发不播放"**仍然存在**。
  在没有拿到该现象当时的日志之前，不再凭推测改动网络层。
- **教训**：那一次我在网络层一次改了三处（发送目标、对端登记、补问策略），
  结果是"想修一个偶现，改坏了一个常态"。**偶现 bug 的修法必须先有复现日志，
  而且一次只改一处、改完立刻验证。**

## 0.6.4

**修复"退出世界再回来有时同步不上、直接不播放"（偶现）+ 对端登记抖动**

日志给出的线索：`发现同伴 127.0.0.1（数据端口 25568）` / `发现同伴 192.168.2.13（数据端口 25568）`
**每 2 秒重复一次** —— 正常情况下这行只在"新的同伴"时打一次。查下去是同一个根因的两面：

| 问题 | 机制 | 修法 |
|---|---|---|
| **询问消息发不出去** | 判断"有没有对端"用的是严格超时（10 s），而**发送也用它** → 对端重启后数据端口变了（实测 25566 → **25568**），旧地址在严格超时内还算"有效"，消息发到旧端口就丢了；广播偶尔丢包也会让地址表瞬间变空 | **判断用严格超时，发送用宽松记忆**：发往"当前有效对端 ∪ 最近 60 秒见过的地址" |
| **对端登记抖动** | 同一个同伴的广播会**交替**从局域网 IP 与 `127.0.0.1` 进来，而旧代码按**地址**登记并"删掉旧地址" → 每次都在删另一条 → 每 2 秒"发现"一次，期间对端表还会指向过期地址 | 改为按**实例 id** 登记：一个同伴就是一条记录 |

另外两处加固：

- 进服后的主动询问由 3 次增加到 **5 次**（进服后 1 / 3 / 10 / 30 / 60 秒）
- 新增：只要"**对端数量变多 + 本机还没有会话 + 仍在进服后 60 秒内**"，立刻补问一次

**"偶现"的由来**：回来后第一次询问常常撞上"此刻还没发现同伴" → 消息被丢弃 → 没人回答 →
我们没有会话 → 静音。这一步的时序运气决定了"这次好不好使"。

## 0.6.3

**修复"有时候会卡出原版的音乐"（两条音轨同时响）**

根因是**同一种错误出现在两条路径上**：原版那条声音响了之后，没人负责把它收回来。

| 路径 | 触发条件 | 后果 |
|---|---|---|
| ① `onWorldEvent` 的"未发现同伴"分支会 `PASS`（不拦 1010） | 组播发现比 1010 晚一两秒 | 原版先响；同伴的 `PLAY` 随后到达 → 我们接上 → **两条同时响** |
| ② 等待答复超时后的"回落原版"用 `world.playSound(...)` | 同伴没在窗口内认领 | 这是**野生声音**，没进原版 `playingSongs` 记账表 → **谁都停不掉**；我们后来接上 → **两条同时响** |

**修法**：

- 回落改走**原版入口** `WorldRenderer#playSong(disc.getSound(), pos)` —— 顺带白拿 HUD 曲名与鹦鹉跳舞
- `playNow` 里**开声前先 `playSong(null, pos)`** 停掉该唱片机上原版那条 —— 上面两种来源一并覆盖

**教训**（已写入 [开发日志 案例 17](docs/开发日志.md)）：凡是"我们可能让位给原版"的设计，
都必须回答**"接管时怎么把原版那条收回来"**。让位不是问题，**让位后没人收摊**才是问题。

## 0.6.2

**日志降噪 + 清掉一处废弃配置**

- "进度对照"不再每 10 秒刷一条（健康状态下偏差恒为 50 ms，那是**刻的量化精度**而不是问题，
  以前那样刷纯属噪音）：
  - 偏差 **> 150 ms** → 立刻 `WARN`（每 10 秒最多一条）——**这才是值得看的信号**
  - 偏差正常 → 每 **60 秒**一条汇总，证明"还在对齐"
- 删除废弃配置项 `maxDriftMs`（0.1.4 起改为"同时开声、全程不纠偏"，它就没用了）。
  旧配置文件里若还留着这个键会被 Gson 直接忽略，**不需要手动清理**

## 0.6.1

**元数据兜底链修正（这次是本机实测发现的）**

- **坑一**：歌曲网页的 `<title>` **实测是空的**（整页 JS 渲染）→ 原先写的"网页兜底"是**无效代码**。
  改为读分享标签 `og:title`（实测有值）
- **坑二**：`/api/song/detail?ids=[id]`（不带 `id=`）这种参数形态**同样可用** → 加为第二级兜底
- 现在三级兜底全部实测过：主接口 → 备用参数形态 → `og:title`
- **实测样例**（本机直接打接口取真名）：

  | 唱片名 | 查回来的真名 |
  |---|---|
  | `@1330348068` | `起风了 - 冯沁苑(买辣椒也用券)` |
  | `@5169250` | `Polynesia (Remix) - Mikron` |
  | `@569213220` | `像我这样的人 - 毛不易` |

## 0.6.0

**物品只存 id，播放时显示联网查回来的真名**

- 唱片名里**只需要写歌曲 id**；屏幕下方"正在播放"显示的曲名改为**联网查回来的真名**（歌名 - 歌手）
- 玩家自己写的备注仍然保留：拼在真名后面，例如 `起风了 - …（我挑的）`
  （规则：真名优先 → 备注 → `网易云 #id`；曲名超长截断到 60 字符）
- 元数据查询与音频地址解析**并行**，只有 **1.2 秒**预算，超时即放弃：
  **查不到名字不影响播放**，只会退回兜底标题
- 请求带 `UA` + `Referer`，但**不带任何账号或 cookie**（与既有合规线一致）

## 0.5.0

**还原原版"屏幕下方显示曲名" + 让唱片名备注变成显示的曲名**

- 原版这一句在 `WorldRenderer#playSong` 里：`inGameHud.setRecordPlayingOverlay(item.getDescription())`。
  我们为了不发出原版声音会取消 1010 事件，**顺带把它一起丢掉了**。
  现在在"真正开声的那一刻"调用**同一个方法**（同一时机、同一淡出逻辑），观感与原版一致
- **不碰挂点、不加 Mixin**：`InGameHud#setRecordPlayingOverlay(Text)` 在 1.20.1 是 public 的，
  所以这次还原没有引入任何"注入失败就崩"的风险
- 唱片名里的**备注直接变成显示的曲名**：`@186016（晴天）` → 屏幕下方显示"晴天"；
  没有备注时回退到音源给的标题；备注也会随会话广播给同伴（对方看到同一个标签）
- 仍然保留的差异：**鹦鹉还不会跳舞**。这需要把挂点从 `ClientWorld#syncWorldEvent` 下移到
  `WorldRenderer#playSong`（设计文档 §2.4 有可直接照抄的 `@Redirect` 写法），属可选的后续项

## 0.4.4

**拆掉服务端中继里最高风险的一处假设 + 给出"真的在转发"的证据**

- **去掉 `ServerPlayNetworking.canSend()` 转发门禁**。它依赖 Fabric 对"客户端声明过哪些频道"的记账，
  跨版本行为不一定一致；万一它恒为 false，就会出现最难查的故障：
  客户端显示"中继可用"（应答走得通），但数据一条都转不出去。
  现改为只认**主动报到过的玩家**，判据完全在我们自己手里
- 服务端新增可见证据：前几次成功转发会打
  `已把 XXX 的同步数据转发给 N 人（参与者共 N 人）` —— 这一行才是"中继真的在工作"
- 客户端报到最多重试 20 次（每次 3 秒），避免在没有中继的服务器上无限发包
- [docs/实测数据.md](docs/实测数据.md) 新增《未验证项（诚实清单）》：
  服务端中继、跨真实互联网、聊天兜底在别人的服务器上、3 人以上、OGG 解码路径 —— 全部标注为未实测

## 0.4.3

**紧急修复：0.4.0–0.4.2 会导致客户端启动崩溃**

- 崩溃原文：`Cannot load class dev.clouddisc.server.CloudDiscServer in environment type CLIENT`
- 根因：服务端入口类上加了 `@Environment(EnvType.SERVER)`。这个注解的语义**不是**"跳过调用"，
  而是 **"环境不匹配时让这个类无法加载"**；而 `main` 入口点在**物理客户端上也会被执行**
  → 客户端加载入口点被拦下 → 启动即崩
- 修法：类上不加注解，改在 `onInitialize()` 里做运行时判断（客户端直接 return）
- 规则澄清：`main` 入口点跑在两端 → 类必须两端可加载 → 不能用 `@Environment`；
  `client` 入口点与 client 段 Mixin 配置不受影响
- 复盘已写入 [docs/开发日志.md](docs/开发日志.md) 案例 16

## 0.4.2

**把"服务端中继的兼容性"变成可核对的承诺**

- 明确把三项标为**冻结接口**并写进文档：频道名 `clouddisc:relay`、载荷第 1 字节含义、约定版本号
- 报到/应答里新增**约定版本字节**，双方日志都会打印各自版本；不带这个字节的旧版中继按 0 处理，仍然兼容。
  意义：万一将来必须改动约定，新客户端能认出对端版本并选择兼容行为，而不是"装了老中继就静默失效"
- README 新增「兼容承诺」表：什么情况下服务端那个 jar 才需要换

  | 变化 | 服务端要重装吗 |
  |---|---|
  | 客户端 mod 升级（0.4 → 0.5 → 1.0…） | **不用** |
  | 服务端 jar 升级 | 不影响老客户端 |
  | Minecraft 大版本升级（1.20.1 → 1.21+） | **要**（整个 mod 都得重做，Fabric 生态皆然） |
  | 改动冻结接口 | 不会发生；且已有版本协商兜底 |

- **最坏情况承诺**：中继不可用时客户端只**降级**（退回 UDP/聊天），不会崩、不会连不上服

## 0.4.1

**修正一处不一致：聊天通道的兜底语义**

- `chatRelayOnlyWhenNoUdp` 的默认值改回 **开**。此前回滚时它被改成默认关，
  结果"装了中继之后聊天栏不再刷 `[CloudDisc]`"这句话与代码不符 —— 文档描述的是应当的行为，代码没跟上
- 现在通道优先级是明确的：

  | 可用通道 | 实际使用 |
  |---|---|
  | 服务端中继可用（收到过应答） | 中继 + UDP（若有对端），**聊天静音** |
  | 只有 UDP 发现过对端 | UDP，**聊天静音** |
  | 两者都没有 | **聊天兜底**（并在日志里说明风险） |

  "可用"的判定依据是**真实信号**（收到过服务端应答 / 发现过对端），不是猜测
- 配置界面标签改为「聊天仅作兜底（中继/UDP 可用时静音）」，避免歧义

## 0.4.0

**服务端中继：跨公网不再依赖聊天，而且服务端一辈子不用改**

- 新增**服务端中继**：服主把本 Mod 的**同一个 jar** 放进服务端 `mods/` 即可；客户端会自动探测并使用，
  **没装也完全无副作用**（自动降级到 UDP / 聊天）
- 服务端组件只做**原样字节转发**：只认载荷第 1 个字节（报到/数据），其余字节不解析、不存储、不执行；
  不注册命令、不要求权限；只转发给**主动报到过**的玩家 → 没装 Mod 的客户端**永远收不到**我们的包
- 因此：**以后客户端协议怎么升级，服务端都不需要跟着改**（这是"一劳永逸"的关键设计）
- 装了中继后：聊天兜底通道自动让位（聊天栏不再出现 `[CloudDisc]...`），
  且不受聊天 256 字符限制，可直接携带完整音频地址
- `environment` 由 `client` 改为 `*`：同一个 jar 两端通用

## 0.3.3

**明确告知"现在只剩聊天通道了"**

- 当 UDP 看不到任何对端时，日志会明确提示已改用聊天兜底通道，并列出它在**别人的服务器**上可能失败的原因：
  聊天被插件改写/禁用、`enforce-secure-profile=true` 且本机无 profile key
- 同时给出建议：和朋友跨公网同步时先连同一个 VPN 走 UDP 直连，不碰聊天

## 0.3.2

**标语定稿并铺到各处**

- 主标语进入配置界面顶部、模组列表介绍、启动日志、教程页、文档：`歪歪网易云唱片mod——忠于原版，高于原版`
- 新增一句话定位：`让 Minecraft 自己唱你的网易云：全程客户端，全图同步。`
- 更新日志页脚新增「关于同步（技术说明）」一节，把可核对的数字写进去

## 0.3.1

**品牌与观感**

- 新增标语（内容同 0.3.2 的主标语）
- mod 显示名改为「歪歪网易云唱片 CloudDisc」；mod id 仍是 `clouddisc`，不影响配置与存档
- 补上 mod 图标（黑胶盘），修掉 Fabric 日志里的 `broken icon` 警告

## 0.3.0

**界面内自带文档**

- 配置界面扩为 4 节：播放设置 / 网络与音源 / **使用教程** / **更新日志**
- 后两节为可滚动只读页，内容取自 jar 内资源文件（`assets/clouddisc/text/*.txt`），改文案不用改 Java
- 唱片命名简化为 `@歌曲id`，并允许后缀备注：`@186016（晴天）`；只有"冒号前确实是已注册音源名"时才按 `<音源>:<曲目>` 切分
- 默认音源改为网易云（本地文件用 `@local:文件名` 显式指定）

## 0.2.3

**修复：退出重进后接不上、完全不播放**

- 根因：刚进服的客户端 `world.getTime()` 与房主能差几万刻（实测 `-28440 刻`、更早还有 `LATE 1000900ms`），用刻差换算会算出**越过文件末尾**的播放位置，管道立刻读到结尾 → 一点声音都没有
- 修法：重进服 / 中途加入一律使用对方给出的**内容位置**定位；正常开始仍用刻差换算（那条已验证可用）
- 新增起播位置安全上限（60 分钟）：定位算错时明确报错，不再静默无声

## 0.2.2

**修复：发起方"还没准备好就回答询问"**

- 根因：放碟后会先解析 + 预取，这段时间会话已在表里但 `localStartTick` 仍为 0；此时回答别人的追问，对方会收到 `startTick = 0`，算出 `-28440 刻` 的离谱偏移，触发兜底后**提前约 2 秒**开始（听感像错拍）
- 修法：未排定开始刻就不应答；接收方忽略没有有效调度的消息
- 另外：同一个同伴不再被登记两次（此前日志里出现 `udp=2`）

## 0.2.1

**修复：中途加入卡 10 秒然后播错位置**

- 根因：解码线程灌满环形缓冲后阻塞在 `write()`；缓冲腾空要靠 MC 消费，而 MC 要等我们 `play()`；seek 又必须在解码线程的循环顶部执行 → 互相等，`requestSeekBlocking` 超时 10 秒，随后播出的是缓冲区里那份"开头"的数据（与记录的位置差近 10 秒）
- 修法：请求 seek 前先清空环形缓冲，放行阻塞中的写入；超时改为明确 ERROR

## 0.2.0

**配置界面（零硬依赖）**

- 三个入口：快捷键 `K`、客户端命令 `/clouddisc`、ModMenu 模组列表按钮
- 只用原版控件；没装 Cloth Config / ModMenu 也能正常打开
- 聊天行前缀改为 `[CloudDisc]` 并做 gzip 压缩，减少对未安装玩家的干扰

## 0.1.4

**中途加入 / 从 64 格外走近 + 同步模型改为"同时开声"**

- 新增"谁在放什么"询问与应答，并用一次性 seek 对齐到当前进度
- 同步模型参考原版唱片：预取完成后广播统一开始刻，各端到点零延迟开声
- **删除每秒一次的强制纠偏**：那次纠偏的度量口径本身是错的（拿"已交给 MC 的字节数"和"游戏刻推算的位置"比），会让音乐变成一段一段

## 0.1.3

**修复：心跳进度的度量口径**

- 改为报"内容位置"，日志里的偏差数字才是真实值（此前固定显示 `-4000ms` 是假象，详见开发日志案例 8）

## 0.1.2

**网络层三修**

- 去掉数据端口上的 `SO_REUSEADDR` 并支持自动往后试端口（25566 → 25567）：双开时两个实例曾绑在同一端口，单播消息只送到其中一个
- 新增固定组播组发现（`224.0.2.61`），局域网发现不再依赖"双方端口相同"
- 修复"幽灵对端"：不再把自己广播出去的包当成同伴

## 0.1.1

**修复：音频完全静音**

- 根因：下混后缓冲区里是 ±32768 的原始幅度，而软限幅按 ±1.0 语义写 → 每个样本都被压成 ±1 LSB（日志里峰值打成 `32230.428` 就是铁证）
- 修法：统一下混 / 限幅 / 写回为归一化空间
- 新增输出峰值诊断（现在报 0.810 / 0.984）

## 0.1.0

**首个可用版本**

- **真正替换唱片机声音**：注入 `SoundLoader#loadStreamed`，由 Minecraft 自己的音频引擎播放我们的 PCM；定位衰减、音量类别、暂停、原版停止逻辑全部沿用
- 网易云：识别分享链接 / 歌曲 id，展开成音频地址并播放
- MP3 解码（内置 JLayer，LGPL-2.1，见 [THIRD-PARTY.md](THIRD-PARTY.md)）
- 音源外链的 302 跳转（https → http）与 Content-Type 校验
- 按文件头魔数识别格式，不再依赖扩展名
- 纯客户端：服务器什么都不用装

---

## 关于同步（技术说明）

- **同步精度**：一个游戏刻（50 ms），实测连续 90 秒无漂移（[实测数据](docs/实测数据.md#1-同步精度)）
- **原版的边界**：`ServerLevel.levelEvent` 通过 `PlayerList.broadcast(..., 64.0, ...)` 只把 1010 事件发给插碟那一刻 64 格内、同维度的玩家，且**不会重发**；因此原版下"中途进服"或"从远处走近"的人什么也听不到。我们让这部分人也能从当前进度接上
- **不是外挂播放器**：我们注入游戏的取流点，由原版音频链路出声——换掉的只是"数据从哪来"
- **只有发起方需要配置音源**：解析结果随会话广播，其它玩家零配置
