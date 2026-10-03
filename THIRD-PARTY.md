# 第三方组件声明（THIRD-PARTY NOTICES）

本 Mod（CloudDisc）本体以 MIT 许可发布。它包含/依赖以下第三方组件：

## JLayer — MP3 解码

- 用途：解码 MP3 音频（`dev.clouddisc.audio.Mp3PcmSource`）
- 坐标：`com.googlecode.soundlibs:jlayer:1.0.1.4`（Maven Central）
- 上游：<https://sourceforge.net/projects/javalayer/> / <https://www.javazoom.net/javalayer/javalayer.html>
- 许可：**GNU Lesser General Public License v2.1（LGPL-2.1）**

### 为什么这样打包

Loom 的 `include` 把它作为**独立的嵌套 jar** 放进 `META-INF/jars/jlayer-1.0.1.4.jar`，
而不是直接编进本 Mod 的类里。这样做是为了满足 LGPL 对"用户可以替换该库"的要求：
用户要换版本时，只需替换那个嵌套 jar（或从 `build.gradle` 里改坐标重新构建），无需改动本 Mod 的源码。

### 你的分发义务

如果你要**分发**本 Mod 的构建产物，请一并满足 LGPL-2.1 的要求，至少包括：

1. 附上 JLayer 的许可证全文与版权声明（见下）；
2. 明确告知它是以 LGPL-2.1 授权、且以独立 jar 形式提供（本文档即为此声明）；
3. 不要移除嵌套 jar 或其中的许可证信息。

JLayer 的完整许可证全文随其源码分发（`COPYING.LESSER`）。如果你不想承担这些义务，
最简单的做法是：**删掉这条依赖与 `Mp3PcmSource`，只保留 OGG/WAV**（这两条链路零第三方依赖）。

## Minecraft / Fabric

- Minecraft 的类与资源、Fabric Loader / Fabric API 均为各自的许可与使用条款所约束。
- 本 Mod 以 Mixin 方式在运行时与 Minecraft 交互，**不包含也不分发** Minecraft 的任何代码或资源。
