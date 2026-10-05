# 歪歪网易云唱片 · CloudDisc

> 忠于原版，高于原版。

给唱片改个名字（`@歌曲ID`），唱片机就会播放网易云的歌 —— **是唱片机本身在响**，不是外挂一个播放器。
装了同一个 mod 的人，听的是同一首、同一进度。

**Minecraft 1.20.1 · Fabric · 纯客户端（服务器不用装）**

---

## 下载

到 [Releases](https://github.com/yeyang34/clouddisc/releases/latest) 下载最新版：

| 文件 | 说明 |
| --- | --- |
| `clouddisc-x.y.z.jar` | 主 mod（必需） |
| `clouddisc-jukeboxlib-*.jar` | 可选：解除唱片时长限制，想放整首歌就装它 |

## 一分钟上手

1. 装好 **Fabric Loader + Fabric API**，把 jar 丢进 `mods`
2. 拿 **铁砧**把任意唱片改名成 `@<网易云歌曲ID>`（例如 `@186016`）
3. 把唱片放进**唱片机**，右键
4. 想放 VIP / 独家歌：按 `K` → 「网络与音源」→ 填自己的解析服务地址 → 保存

## 特性

- **唱片机本体发声** —— 接口、时长、切歌全部走原版唱片机那套
- **多人同步** —— 装了这个 mod 的人听到同一进度
- **物理声效** —— 隔墙变闷、开门听得到、房间里的柱子/家具不挡声、屋顶和墙按真实几何算
- **不依赖服务器** —— 纯客户端，各自解析、彼此同步

## 常见问题

**别人听不到 / 放的是原版音乐？**
确认对方也装了同一个版本，并且两台机器都能访问解析服务。

**没有声音 / 提示解析失败？**
按 `K` 检查解析服务地址；没有填也能听免费歌。

**和别的声效 mod 冲突？**
「物理声效重制版」一类会抢同一个 OpenAL 上下文，两者只能留一个。

## 构建

```bash
./gradlew build        # 产物在 build/libs/
```

需要 JDK 17。

## 作者合影

<p align="center">
  <img src="docs/images/skin-head-plain.png" width="96" alt="作者">
  <img src="docs/images/skin-ayanami.png" width="96" alt="Ayanami005">
  <img src="docs/images/agent-fish.jpg" width="96" alt="助理">
</p>

<p align="center"><sub>作者 · Ayanami005（提供 AI token） · 那条鱼</sub></p>

## 许可

MIT