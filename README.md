# 歪歪网易云唱片 CloudDisc

[![Release](https://img.shields.io/github/v/release/yeyang34/clouddisc)](https://github.com/yeyang34/clouddisc/releases/latest)
[![Download](https://img.shields.io/badge/download-latest%20jar-2ea44f)](https://github.com/yeyang34/clouddisc/releases/latest)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)
![Fabric](https://img.shields.io/badge/Fabric-client--only-orange)

> **歪歪网易云唱片mod——忠于原版，高于原版**
>
> 让 Minecraft 自己唱你的网易云：全程客户端，全图同步。中途进场也不掉拍。

---

## 下载与安装（给玩家）

1. 到 **[Releases](https://github.com/yeyang34/clouddisc/releases/latest)** 下载最新的 `clouddisc-x.y.z.jar`
2. 丢进 `.minecraft/mods/`（需要 **Fabric 1.20.1** 与 **Fabric API**）
3. 用铁砧把任意唱片改名为 **`@歌曲id`**（例如 `@188204`），放进唱片机即可播放
4. 想放 VIP 曲目需要自建解析服务 —— 游戏内按 `K` 打开配置界面 → 第 3 节「使用教程」有完整说明

> 更新时**先删掉旧 jar 再放新的**（同时留两个同 id 的 jar 会让 Fabric 拒绝启动）。
> 服务器侧不需要装；若服主愿意把同一个 jar 放进服务端 `mods/`，跨公网同步会更省心（不装也能用）。

---

把**被铁砧改过名的唱片**放进唱片机，就会播放网易云的歌；并且**所有装了本 Mod 的玩家听到同一进度**（含中途进服、从远处走近）。

- Minecraft Java **1.20.1** / **Fabric** / **纯客户端**（服务器什么都不用装，连原版服可用）
- 声音是**真替换**：走 Minecraft 自己的音频引擎（OpenAL），所以定位衰减、"唱片机/音符盒"音量滑块、暂停、失焦静音全都照常生效
- 完整设计说明见 [`docs/设计文档.md`](docs/设计文档.md)

> ✅ 已经过实机验证：真替换唱片机声音、多人同步（实测偏差 50 ms 且无漂移）、
> 中途进服/走近接上、联网显示真名、VIP 曲目（需自建解析服务）。各版本变更见更新日志。
>
> ⚠️ **未实测**的部分（服务端中继、跨真实互联网、3 人以上）单独列在
> [docs/实测数据.md](docs/实测数据.md) 的《未验证项》一节 —— 我不用"应该能行"糊过去。

---

## 文档

| 文件 | 内容 |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | **更新日志（简版）**：说人话的版本变更，给玩家看 |
| [docs/CHANGELOG-内部.md](docs/CHANGELOG-内部.md) | **更新日志（内部详细版）**：每个 bug 的根因、实测数据、工程细节 |
| [docs/开发日志.md](docs/开发日志.md) | **调试实录**：每个结论是怎么查出来的，含误判与方法论 |
| [docs/实测数据.md](docs/实测数据.md) | **实测数字**：同步精度、中途加入、网易云抽样、音频指标、未验证项 |
| [docs/设计文档.md](docs/设计文档.md) | 完整设计：四个关键判断、架构、协议、通信层、合规边界 |
| [docs/发布到GitHub.md](docs/发布到GitHub.md) | 推到 GitHub 的步骤与"以后怎么更新"（含自动构建 Release） |
| [docs/使用教程.md](docs/使用教程.md) | 给玩家看的逐步教程（游戏内也有一份） |
| [THIRD-PARTY.md](THIRD-PARTY.md) | 第三方组件与许可义务 |

### 几个可核对的事实

- **同步精度**：一个游戏刻（50 ms），90 秒窗口内 10 次采样全部 `-50ms`、**无漂移** —— [数据](docs/实测数据.md#1-同步精度)
- **中途加入**：B 在 A 播放 45 秒后接上，日志为 `请求位置 45150ms / 实际 45150ms` —— [数据](docs/实测数据.md#2-中途加入--从-64-格外走近)
- **原版的边界**：1010 事件由 `PlayerList.broadcast(..., 64.0, ...)` 只发给插碟那一刻 64 格内、同维度的玩家，且**不会重发**（`ServerLevel.levelEvent`）；因此原版下迟到的人什么也听不到
- **真替换**：`SoundLoader#loadStreamed` 注入点，由 MC 自己的 OpenAL 链路出声（refmap 解析到 `class_4237;method_19744`）
- **零服务端依赖**：`environment: client`，连原版服务器不需要服务端装任何东西

## 服务端（可选，但跨公网时强烈推荐）

服务端**不装也能用**（纯客户端）。如果服主愿意配合，把**同一个 jar** 放进服务端 `mods/` 就自动生效：

| 步骤 | 说明 |
|---|---|
| 1 | 确认服务端已装 **Fabric API**（绝大多数 Fabric 服都有；我们的客户端本来也依赖它） |
| 2 | 把 `clouddisc-x.y.z.jar` 放进服务端 `mods/` |
| 3 | 重启服务端，日志会出现 `[CloudDisc] 服务端同步中继已就绪：频道 clouddisc:relay` |
| 4 | 客户端进服后日志出现 `[CloudDisc] 服务器中继可用（这台服务器装了 CloudDisc 的服务端组件）` |

**为什么它一辈子不用改**：服务端组件只认载荷的**第 1 个字节**（"报到"还是"数据"），其余字节**原样转发、绝不解析**。
客户端以后加字段、加消息类型、换协议版本，服务端照转不误 —— 协议演进全部留在一侧。

**安全性**：不解析、不存储、不执行任何内容；不注册命令、不要求权限；只转发给**主动报到过**的玩家，
所以没装 Mod 的客户端**永远收不到**我们的任何包。客户端也可用配置项 `enableServerRelay` 关掉这条通道。

**装了中继之后**：聊天兜底通道自动让位（聊天栏不再出现 `[CloudDisc]...`），
而且它不受聊天 256 字符限制，可以直接携带完整音频地址 —— 跨公网同步从此与"服务器装了什么插件"无关。

**没装中继会怎样**：客户端探测不到应答，自动降级到 UDP 直连（同局域网/同 VPN）或聊天兜底，一切照旧。

### 兼容承诺（什么时候才需要换服务端那个 jar）

| 变化 | 服务端需要重装吗 |
|---|---|
| 客户端 mod 升级（0.4 → 0.5 → 1.0…） | **不需要**。服务端不解析数据，只看载荷第 1 个字节 |
| 服务端 jar 升级 | 不影响老客户端（协议字节透明） |
| **Minecraft 大版本升级**（1.20.1 → 1.21+） | **需要** —— 整个 mod 都得重做，Fabric 生态里人人如此 |
| 我们改动"频道名 / 第 1 字节含义 / 约定版本" | 这三项已标为**冻结接口**。并且报到/应答里带了"约定版本"字节（旧中继按 0 兼容），万一必须改也能协商，而不是静默失效 |

**最坏情况**：中继不可用时，客户端只会**降级**（退回 UDP / 聊天），不会崩溃、不会连不上服。

## 怎么用

> 📖 逐步操作 + 排障表 + 同步测试方法：见 **[docs/使用教程.md](docs/使用教程.md)**

1. 用铁砧把任意唱片改名为 **`@` + 网易云歌曲 id**：

   | 铁砧里输入 | 说明 |
   |---|---|
   | `@186016` | 最短 |
   | `@186016（晴天）` | **备注可选**；播放时显示的是**联网查回的真名**，备注拼在真名后面 |
   | `@186016 晴天`、`@186016-晴天` | 备注用别的分隔符也行（只认开头那串数字） |
   | `@https://music.163.com/song?id=186016` | 直接粘分享链接也能识别 |
   | `@local:test-metronome` | 想放本地文件就用 `local:` 显式指定 |

2. 把这张唱片放进唱片机，两秒后开始播放
3. 其它装了 Mod 的玩家会自动跟上同一进度（含**中途进服 / 从远处走近**的情况）

> ⚠️ 网易云这条链路**只放"匿名就能听"的曲目**（免费/非独家）。VIP/独家曲目网易会返回 404 网页，日志里会明确说明不是 bug。
> 想放 VIP 曲目只能靠你自己的解析服务：配置 `neteaseEndpoint`（详见 [使用教程 §7.5](docs/使用教程.md)）。
> 本 Mod **不登录、不带 cookie、不做接口加签、不解密受保护格式**。

改名格式（`@` 前缀和默认音源都能在配置界面里改）：

```
@<歌曲id>            最简写法（现在只走网易云）
@<歌曲id>（备注）      备注可选；屏幕下方显示的是联网查回的真名，备注拼在后面
@<歌曲id> 任意备注     任意分隔符都行，只认开头那串数字
@<链接>              直接粘网易云分享链接
@local:<文件名>      显式走本地文件（clouddisc-music/ 里）
```

- `@` 前缀可在配置里改。**只有带前缀的唱片才会被接管**，普通改名唱片不受影响。
- 铁砧输入框上限约 50 字符，所以 key 要短。

## 配置

`.minecraft/config/clouddisc.json`（首次启动自动生成）。常用项：

| 项 | 默认 | 说明 |
|---|---|---|
| `discNamePrefix` | `"@"` | 唱片改名前缀 |
| `defaultProvider` | `"local"` | 默认音源 provider |
| `localMusicDir` | `"clouddisc-music"` | 本地音乐目录 |
| `udpPort` | `25566` | 玩家间直连端口（**同一台电脑开两个客户端要改成不同值**） |
| `enableLanDiscovery` | `true` | 局域网自动发现 |
| `enableChatRelay` | `true` | 允许聊天通道做兜底（有副作用，见设计文档 §7.3） |
| `startLeadTicks` | `40` | 统一开始刻的提前量（2 秒），给各端预缓冲 |
| `prebufferMs` | `1500` | 预缓冲时长 |
| `maxDriftMs` | `150` | 超过这个偏差就 seek 纠偏 |
| `jukeboxVolume` | `4.0` | 与原版唱片一致 |
| `neteaseEnabled` | `true` | 网易云 provider：只做"识别分享链接/歌曲 id → 展开成音频地址"，不登录、不带 cookie |
| `neteaseUrlTemplate` | 网易公开外链 | 没自己的服务时用它展开 `{id}`。实测：免费曲可用、VIP 曲返回 404 网页 |
| `neteaseEndpoint` | `""` | **你自己的解析服务**（唯一能放 VIP 曲目的方式）。`{id}` 会被替换；返回 JSON `{"title","url"}` 或直接返回音频流 |
| `blockPeerPrivateUrls` | `false` | 是否禁止同伴使用私网音源地址（防 SSRF；环回/链路本地/云元数据地址始终拒绝） |

## 构建

需要 **JDK 17**（1.20.1 的编译目标）。

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 开发环境启动游戏
```

> 国内网络如果卡在 "Downloading gradle-8.8-bin.zip"，把 `gradle/wrapper/gradle-wrapper.properties`
> 里的 `distributionUrl` 改成镜像即可，例如
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.8-bin.zip`（本项目的首次构建就是用这个镜像完成的）。

版本矩阵（都已核实）：Minecraft `1.20.1`、Yarn `1.20.1+build.10`、Fabric Loader `0.16.14`、Fabric API `0.92.12+1.20.1`、Loom `1.6.12`、Gradle `8.8`。

## 代码结构

```
src/main/java/dev/clouddisc/
├─ CloudDiscClient.java          入口与装配（Fabric 事件）
├─ CloudDiscConfig.java          JSON 配置
├─ disc/DiscName.java            "名字即协议"的编解码
├─ audio/                        解码 → 单声道 → 48k → 环形缓冲 → 伪装成 MC 的 AudioStream
├─ music/                        可插拔音源 provider（local / netease(自备接口) / url）
├─ jukebox/                      会话状态机、播放控制、自定义 SoundInstance、流注册表
├─ sync/                         协议、通道抽象、UDP 主通道、聊天兜底、同步服务
└─ mixin/
   ├─ SoundLoaderMixin.java      ★ 音频注入点（换成我们的 PCM 流）
   └─ ClientWorldMixin.java      ★ 声音替换判定点（1010/1011）
```

设计依据（含 1.20.1 反编译源码与行号）见 [`docs/设计文档.md`](docs/设计文档.md)。

## 合规声明

- 本 Mod **不内置**任何音乐平台的接口地址或凭据，**不支持**解密受保护/加密的音频格式，**不缓存后再分发**音频。
- 音源是可插拔的 provider：默认走**你自己的本地文件**或**你自建的服务**（Subsonic/Navidrome/Jellyfin 等）。
- 请只播放你有权播放的内容，并遵守所在地法律与各平台的服务条款。设计文档 §8.2 有详细说明。

## 许可

MIT
