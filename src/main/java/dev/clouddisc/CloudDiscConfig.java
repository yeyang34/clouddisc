package dev.clouddisc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 简单 JSON 配置（config/clouddisc.json）。
 * <p>刻意不依赖 Cloth Config / YACL，先保证骨架零额外依赖；后续可加配置界面。
 */
public final class CloudDiscConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/**
	 * 当前配置版本。
	 * <p>为什么需要它：Gson 只会覆盖"文件里存在的字段"，所以<b>改了 Java 默认值对老配置文件无效</b>
	 * —— 实测就踩过这个坑：老配置里写着 {@code neteaseEnabled: false}（当时的默认值），
	 * 新版本把默认值改成 true 之后，老用户仍然读到 false，表现为"功能像没实现"。
	 * 有了版本号就能在老配置上做一次迁移。
	 */
	public static final int CURRENT_VERSION = 4;

	private static CloudDiscConfig instance;

	/**
	 * 配置版本，见 {@link #CURRENT_VERSION}。
	 * <p><b>必须是 0 而不是 CURRENT_VERSION</b>：字段在 JSON 里缺失时 Gson 保留 Java 初始值，
	 * 如果初始值就是当前版本，老配置永远会被当成"最新"，迁移就成了死代码（踩过一次）。
	 * 新建配置时由 {@link #load()} 显式写入当前版本。
	 */
	public int configVersion = 0;

	/** 唱片改名前缀：只有以此开头的唱片才会被本 Mod 接管，避免误伤普通改名唱片。 */
	public String discNamePrefix = "@";

	/** 默认音源 provider id。 */
	public String defaultProvider = "local";

	/** 本地音乐目录（相对 .minecraft 或绝对路径），provider=local 时按文件名/相对路径查找。 */
	public String localMusicDir = "clouddisc-music";

	// ---- 播放与缓冲 ----
	/** 约定统一的起始时刻时预留的提前量（tick），给各客户端预缓冲留时间。 */
	public int startLeadTicks = 40;
	/** 预缓冲多少毫秒音频后才允许声音开始（CompletableFuture 完成）。 */
	public int prebufferMs = 1500;
	// 注：曾有一个 maxDriftMs（周期性纠偏的阈值）。0.1.4 起改为"同时开声、全程不纠偏"
	// （参考原版唱片），该配置已废弃并删除。旧配置文件里若还留着这个键，Gson 会直接忽略。
	/**
	 * 喂给声音引擎之前的输出余量（0.1~2.0）。
	 * <p><b>为什么需要它</b>：vanilla 唱片的实例音量是 4.0（反编译 {@code PositionedSoundInstance} 确认），
	 * 但 vanilla 的唱片 ogg 是低电平母带；而在线音源（网易云等）的 mp3 是正常商业母带，
	 * 峰值接近满刻度 —— 直接乘 4.0 会严重削顶，表现为"声音巨大 + 音质炸裂"。
	 * <p>最终响度 = {@code outputGain × jukeboxVolume}。两个都取 1.0 时峰值不超标、且与原版唱片响度可比。
	 */
	public float outputGain = 1.0f;

	/**
	 * 唱机音频音量倍率（OpenAL 侧的增益）。
	 * <p>响度 = {@code outputGain × jukeboxVolume}：
	 * <ul>
	 *   <li>{@code 1.0}（默认）→ 干净、不削顶，与原版唱片相当</li>
	 *   <li>{@code 1.5~2.0} → 更响，超过 1.0 的部分会被软限幅温和压缩（不会硬削顶的刺耳声）</li>
	 *   <li>{@code 3.0+} → 明显压缩，音质有损</li>
	 * </ul>
	 */
	public float jukeboxVolume = 1.0f;

	// ---- 缓存与网络 ----
	/**
	 * 缓存总体积上限（MB），0 = 不限制。
	 * <p>缓存位于<b>每个玩家自己的机器</b>：{@code 游戏目录/config/clouddisc/cache}，**不占服务器硬盘**。
	 * 超过上限时按"最久未使用"清理到上限的 80% 以下。
	 */
	public int cacheMaxMb = 512;
	/** 单曲允许的最大下载体积（MB），防止被别人的 URL 撑爆内存/磁盘。 */
	public int maxTrackMb = 64;
	/**
	 * 是否禁止使用"别的玩家给过来的私网地址"。
	 * <p>默认 false：为了"同一局域网里的自建音源（Navidrome/Jellyfin 等）也能被同伴播放"。
	 * 打开后更安全（防 SSRF），但局域网音源就只能发起方自己听得见。
	 * <p>注意：环回(127.0.0.1)、链路本地(169.254.x.x)、云元数据地址<b>无论如何都会被拒绝</b>；
	 * 而<b>本机自己 provider 解析出来的地址不受此限制</b>（所以你把接口搭在本机也完全没问题）。
	 */
	public boolean blockPeerPrivateUrls = false;

	// ---- 通信层 ----
	/** UDP 监听端口（玩家之间直连用）。端口被占用时会自动往后试几个，日志里会打印实际端口。 */
	public int udpPort = 25566;
	/** 是否开启局域网 UDP 广播自动发现。 */
	public boolean enableLanDiscovery = true;
	/**
	 * 手动指定的对端地址（形如 {@code "127.0.0.1:25567"}）。
	 * <p>用途：组播/广播被网络环境挡住时的兜底，例如"同一台电脑双开两个客户端"这种测试场景。
	 * 填了之后不管自动发现是否成功，都会主动往这些地址发消息。
	 */
	public String[] udpPeers = new String[0];
	/**
	 * 是否启用"服务端中继"通道（默认开）。
	 * <p>服务端装本 Mod 的同一个 jar 即自动生效；<b>没装时完全无副作用</b>（客户端会探测不到，自动降级）。
	 * <p>这是跨公网最省事的一条通道：不刷聊天、不受服务器插件影响，而且能携带完整音频地址。
	 */
	public boolean enableServerRelay = true;

	// ---- 物理声效（纯客户端，只作用于我们自己的声源） ----
	/**
	 * 物理声效总开关（默认开）。
	 * <p>关掉之后：不挂 EFX、不做射线、也不做 PCM DSP —— 就是"干净的直通"，用来 A/B 对比。
	 */
	public boolean physicsSound = true;
	/**
	 * 强度（0.0 ~ 2.0，默认 1.0）：混响发送增益的整体倍率。
	 * <p>遮挡造成的闷响不受它影响（那是"物理事实"），它只调"余响有多湿"。
	 */
	public float physicsSoundLevel = 1.0f;
	/**
	 * 精度：混响射线数（默认 32）。
	 * <p>越大越准、越贵。主线程预算约 1ms/次评估（每 4 刻评估一次），
	 * 32 条 x 最多 4 次反弹 ≈ 128 次 raycast。
	 */
	public int physicsRays = 32;
	/** 调试日志：每 10 秒打一行遮挡/截止/发送增益/耗时（默认关，排障时开）。 */
	public boolean physicsSoundDebug = false;
	/**
	 * 严格遮挡（默认关）。
	 * <p>开：只听"唱片机 → 耳朵"这一条线，墙上有缝也当墙。
	 * <p>关（推荐）：再把两个端点各偏移 ±1 格的 8 个对角点算一遍取最小值 ——
	 * 门缝、窗缝、拐角能让声音明显透过来，这更接近真实听感。
	 */
	public boolean physicsStrictOcclusion = false;
	/**
	 * 方向性（默认开）。
	 * <p>直通被挡住时，把声源位置沿"反射来向"偏移（<b>到听者的距离保持不变</b>，
	 * 所以不会造成音量突变），听感就是"声音从拐角/走廊那头绕过来"，而不是从墙里穿过来。
	 */
	public boolean physicsSoundDirection = true;

	/** 是否允许聊天通道作为兜底信令/数据通道。 */
	public boolean enableChatRelay = true;
	/**
	 * 聊天通道是否只作兜底（默认<b>开</b>）。
	 * <p>开（推荐）：中继或 UDP 任一可用时，聊天通道自动静音 —— 没装 Mod 的玩家不会看到
	 * {@code [CloudDisc]...} 行，服务器日志也干净。
	 * <p>关：两条通道并行发送，多一层冗余（UDP 不稳时更抗丢包），代价是聊天栏会刷同步行。
	 * <p>判定"可用"用的是**真实信号**：中继要收到过服务端应答，UDP 要发现过对端 —— 不是猜测。
	 */
	public boolean chatRelayOnlyWhenNoUdp = true;

	// ---- 音源接入 ----

	/**
	 * 网易云 provider 开关（默认开启）。
	 * <p>它<b>只做</b>一件事：把"分享链接 / 歌曲 id"识别出来，展开成一条音频地址。
	 * 不登录、不带 cookie、不做接口加签、不解析加密格式、不缓存后再分发。
	 */
	public boolean neteaseEnabled = true;

	/**
	 * 你自己的解析服务（可选，但这是唯一能放 VIP 曲目的方式）。
	 * <p>{@code {id}} 会被替换成歌曲 id。响应可以是：
	 * <ul>
	 *   <li>JSON：{@code {"title":"...","url":"http://.../x.mp3"}}</li>
	 *   <li>或者直接返回音频字节流（此时请求地址本身就被当成音频 URL）</li>
	 * </ul>
	 */
	public String neteaseEndpoint = PresetResolver.ENDPOINT;

	/**
	 * 没有自己的服务时，用它把歌曲 id 展开成音频地址。
	 * <p>默认值是网易的公开外链。实测（2026-10，抽样 8 首）：
	 * <b>免费/非独家曲目可以拿到 audio/mpeg</b>；VIP/独家/下架曲目会跳到 {@code /404} 返回网页。
	 * 因此"能不能放"取决于那首歌匿名是否可播。
	 * <p>想更稳、或要放 VIP 曲目，请把它改成你自己的服务地址（或用上面的 neteaseEndpoint）。
	 */
	public String neteaseUrlTemplate = "https://music.163.com/song/media/outer/url?id={id}.mp3";

	/**
	 * 查"歌名 + 歌手"的接口模板（{@code {id}} 占位）。
	 * <p>唱片名里只存歌曲 id；<b>显示用的歌名是播放时去网易云查回来的真名</b>。
	 * <p>默认走网易云自己的公开详情接口；取不到会自动退到"歌曲网页的 {@code <title>}"，
	 * 两条都失败就显示兜底标题 {@code 网易云 #<id>} —— <b>元数据失败绝不影响播放</b>。
	 * <p>留空 = 不试 JSON 接口，直接走网页标题那条。
	 */
	public String neteaseMetaUrlTemplate = "https://music.163.com/api/song/detail/?id={id}&ids=[{id}]";

	/** 附加请求头，例如 Referer / User-Agent，仅用于你自己配置的音源。 */
	public String[] httpHeaders = PresetResolver.TOKEN.isBlank()
			? new String[0]
			: new String[] { "X-Token: " + PresetResolver.TOKEN };

	public static CloudDiscConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("clouddisc.json");
	}

	private static CloudDiscConfig load() {
		Path p = path();
		if (Files.isRegularFile(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				CloudDiscConfig cfg = GSON.fromJson(r, CloudDiscConfig.class);
				if (cfg != null) {
					if (cfg.configVersion < CURRENT_VERSION) {
						cfg.migrate();
						cfg.save();
					}
					return cfg;
				}
			} catch (Exception e) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 配置读取失败，使用默认值", e);
			}
		}
		CloudDiscConfig cfg = new CloudDiscConfig();
		cfg.configVersion = CURRENT_VERSION; // 新配置直接标成最新
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 配置写入失败", e);
		}
	}

	/** 老配置迁移。注意：只在版本落后时执行一次。 */
	private void migrate() {
		if (configVersion < 2 && !neteaseEnabled) {
			// v1 里 neteaseEnabled 的默认值是 false，且当时"网易云"指的是"你自己的接口"；
			// v2 起它代表"识别分享链接/歌曲 id"的能力，默认应当开启。
			// 代价：v1 里特意手动关掉它的用户会被改回 true（那种情况极罕见，且改回来只需一行）。
			neteaseEnabled = true;
		}
		if (configVersion < 3 && jukeboxVolume == 4.0f) {
			// v2 的默认音量照抄了 vanilla 的 4.0，对满刻度素材会削顶（实测"声音巨大 + 音质炸裂"）。
			// 只在这个值恰好等于旧默认值时才改写，避免覆盖用户自己的选择。
			jukeboxVolume = 1.0f;
			outputGain = 1.0f;
		}
		configVersion = CURRENT_VERSION;
	}

	/** provider=local 时的音乐根目录。 */
	public Path resolveLocalMusicDir() {		Path p = Path.of(localMusicDir);
		if (p.isAbsolute()) {
			return p;
		}
		return FabricLoader.getInstance().getGameDir().resolve(localMusicDir);
	}

	/** 缓存目录：放在 config 下，尽量避免路径里出现非 ASCII 字符。 */
	public Path resolveCacheDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("clouddisc").resolve("cache");
	}
}
