package dev.clouddisc.jukebox;

import dev.clouddisc.CloudDiscClient;
import dev.clouddisc.CloudDiscConfig;
import dev.clouddisc.audio.AudioPipeline;
import dev.clouddisc.audio.OggPcmSource;
import dev.clouddisc.audio.PcmAudioStream;
import dev.clouddisc.audio.PcmSource;
import dev.clouddisc.audio.TrackFetcher;
import dev.clouddisc.disc.DiscName;
import dev.clouddisc.music.MusicProvider;
import dev.clouddisc.music.Providers;
import dev.clouddisc.sync.Protocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 唱片机播放控制：把"世界事件 / 同步消息"翻译成"MC 里真的有一条声音在响"。
 *
 * <p>核心流程：
 * <ol>
 *   <li>玩家右键把 CloudDisc 唱片放进唱片机 → 本地立刻广播 {@code CLAIM}（占位）</li>
 *   <li>服务端让唱片机开始播放 → 客户端收到世界事件 1010 → 本地解析曲目（异步）</li>
 *   <li>解析成功后广播 {@code PLAY}（含 uri 与统一开始刻），各端在开始刻对齐播放</li>
 *   <li>播放期间发起方每秒发一次 {@code HB}，接收方据此纠偏</li>
 * </ol>
 *
 * <p>别人的唱片，或没有同伴时的唱片：走原版（这里返回 {@link Decision#PASS}）。
 */
public final class PlaybackController {
	public enum Decision {
		/** 交给原版处理 */
		PASS,
		/** 我们已经接管（原版声音必须被取消） */
		CANCEL
	}

	/** 由 SyncService 实现：把会话状态广播出去。 */
	public interface Announcer {
		void announceClaim(JukeboxSession session, long nowTick);

		void announcePlay(JukeboxSession session, long nowTick);

		void announceStop(JukeboxSession session, long nowTick);

		void announceQuery(String dim, BlockPos pos, long nowTick);

		void announceAnswer(JukeboxSession session, long nowTick);

		void announceHeartbeat(JukeboxSession session, long posMs, long nowTick);

		boolean hasPeers();

		/** 诊断用：各通道的对端数，例如 {@code "udp=0, chat=0"}。 */
		String peerSummary();

		boolean peersCarryUri();

		/** 心跳间隔（tick）。聊天通道必须显著拉长，避免触发服务端刷屏踢人。 */
		int heartbeatIntervalTicks();
	}

	/** 右键后等世界事件的窗口（tick）。 */
	private static final int PENDING_TICKS = 200;
	/** 没收到 CLAIM 时静音等待的窗口：很短，避免误伤普通唱片。 */
	private static final int HOLD_TICKS_UNCLAIMED = 120;
	/** 收到 CLAIM 后愿意等 PLAY 的窗口。 */
	private static final int HOLD_TICKS_CLAIMED = 140;

	private static final ExecutorService WORKER = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "CloudDisc-Worker");
		t.setDaemon(true);
		return t;
	});

	private static final class Pending {
		final JukeboxSession session;
		long expireTick;

		Pending(JukeboxSession session, long expireTick) {
			this.session = session;
			this.expireTick = expireTick;
		}
	}

	private static final class Held {
		final String dim;
		final BlockPos pos;
		final int itemId;
		String epoch;
		long expireTick;
		boolean claimed;

		Held(String dim, BlockPos pos, int itemId, long expireTick) {
			this.dim = dim;
			this.pos = pos;
			this.itemId = itemId;
			this.expireTick = expireTick;
		}
	}

	private final CloudDiscConfig cfg;
	private final Announcer announcer;
	private final Map<String, JukeboxSession> sessions = new HashMap<>();
	private final Map<String, Pending> pending = new HashMap<>();
	private final Map<String, Held> holds = new HashMap<>();
	/** 对某个坐标"主动询问"的冷却，避免心跳把询问刷爆。 */
	private final Map<String, Long> queryCooldown = new HashMap<>();

	public PlaybackController(CloudDiscConfig cfg, Announcer announcer) {
		this.cfg = cfg;
		this.announcer = announcer;
	}

	private static long now() {
		ClientWorld world = MinecraftClient.getInstance().world;
		// Yarn getTime() == Mojang getGameTime()：只增不减、不受 /time 与 doDaylightCycle 影响
		return world == null ? 0L : world.getTime();
	}

	public List<JukeboxSession> sessions() {
		return new ArrayList<>(sessions.values());
	}

	// ------------------------------------------------------------ 入口：本地

	/** 玩家右键把唱片放进唱片机（客户端预测阶段）。 */
	public void noteLocalUse(Identifier dim, BlockPos pos, Text itemName) {
		DiscName.SongQuery query = DiscName.parse(itemName, cfg.discNamePrefix);
		if (query == null) {
			return;
		}
		String key = Protocol.key(dim.toString(), pos.asLong());
		if (sessions.containsKey(key)) {
			return;
		}
		JukeboxSession session = new JukeboxSession(dim.toString(), pos.asLong(),
				UUID.randomUUID().toString(), query.provider(), query.trackId());
		session.originator = true;
		pending.put(key, new Pending(session, now() + PENDING_TICKS));
		// 抢在服务端真正开始播放之前占位，让同伴先别响原版唱片声
		announcer.announceClaim(session, now());
		CloudDiscClient.LOGGER.info("[CloudDisc] 检测到 CloudDisc 唱片: {} -> {}:{}",
				itemName.getString(), query.provider(), query.trackId());
	}

	// -------------------------------------------------- 入口：原版世界事件

	/**
	 * 我确实持有这个会话、但**还没排定开始刻**（正在解析/预取）时，回一个 CLAIM。
	 *
	 * <p><b>为什么需要这一步</b>（实测现象：对方"先出一会原版音乐，然后被网易云替代"）：
	 * {@code announceAnswer} 在"尚未排定开始刻"时会拒绝发送（否则会给出 {@code startTick=0}
	 * 的垃圾调度，见 0.2.2）。于是询问方在**未认领等待窗口（6 刻 = 300ms）**内收不到任何回应
	 * → 它按设计回落原版唱片声 → 1~2 秒后我们的 PLAY 才到 → 接管。
	 * 听感就是"先响一下原版"。
	 *
	 * <p>CLAIM 的语义恰好就是"这块归我，先别放原版"：它会把对方的等待窗口延长到
	 * {@code HOLD_TICKS_CLAIMED}，于是它安静地等我们的 PLAY，不再放原版。
	 */
	/**
	 * 解析阶段（会话尚未进入 sessions 表）被询问时，用 pending 里的会话回一个 CLAIM。
	 *
	 * <p><b>为什么需要它</b>（实测现象："偶现先响一下原版唱片，几秒后才切成网易云"）：
	 * 会话是在**解析完成后的回调**里才放进表的，而解析（查真名 + 换地址）要 1~3 秒。
	 * 这段时间里有人来问"谁在放什么"，我们这边"看起来什么都没有" → 不回话 →
	 * 对方按设计补放原版唱片声 → 等我们的 PLAY 到了才接管。
	 * pending 从右键那一刻就存在，正好补上这个空窗期。
	 */
	private void claimFromPending(String key, long nowTick) {
		Pending p = pending.get(key);
		if (p == null || p.expireTick < nowTick) {
			return;
		}
		announcer.announceClaim(p.session, nowTick);
		CloudDiscClient.LOGGER.info("[CloudDisc] 询问到达时本机正在解析 → 回 CLAIM 让对方继续等（避免它回落原版）");
	}

	private void claimIfNotScheduledYet(JukeboxSession s, long nowTick) {
		if (s.localStartTick > 0L) {
			return; // 已经有合法开始刻了，上面的应答就够
		}
		announcer.announceClaim(s, nowTick);
		CloudDiscClient.LOGGER.info("[CloudDisc] 有人询问，但我尚未排定开始刻 → 已回 CLAIM 让他继续等（避免他回落原版）");
	}

	/** 本机当前持有的会话数（供"询问是否已经有结果"这类判断用）。 */
	public int sessionCount() {
		return sessions.size();
	}

	public Decision onWorldEvent(Identifier dim, BlockPos pos, int eventId, int data, long nowTick) {
		String dimId = dim.toString();
		String key = Protocol.key(dimId, pos.asLong());

		if (eventId == 1010) {
			// 不再立刻移除：解析阶段（会话还没进表）它就是"我占着这台唱片机"的凭据，
			// 用来在被询问时回 CLAIM，避免对方 300ms 收不到回应而回落原版唱片声。
			Pending p = pending.get(key);
			CloudDiscClient.LOGGER.info("[CloudDisc] 收到世界事件 1010（唱片机开始播放）@ {} 物品ID={}｜本机待确认插入={} 已有会话={} 各通道对端: {}",
					pos, data, p != null, sessions.containsKey(key), announcer.peerSummary());
			if (p != null) {
				startSession(p.session, nowTick);
				return Decision.CANCEL;
			}
			if (sessions.containsKey(key)) {
				return Decision.CANCEL; // 已经由我们接管
			}
			if (!announcer.hasPeers()) {
				return Decision.PASS; // 没有同伴：不干扰原版
			}
			Held hold = new Held(dimId, pos, data, nowTick + HOLD_TICKS_UNCLAIMED);
			holds.put(key, hold);
			announcer.announceQuery(dimId, pos, nowTick);
			return Decision.CANCEL; // 先静音，等答复/超时回落
		}

		if (eventId == 1011) {
			// 【0.9.0 起的行为】原版怎么通知，我们就怎么停 —— 干净、可预期：
			//   · 自然放完（到唱片标称时长）→ 停
			//   · 玩家拔碟 / 换碟             → 停（换碟随后会有新的 1010 接管）
			// "不受唱片时长限制"这件事**不在这里做**：它由可选的附加 lib mod
			// （服务端改 JukeboxBlockEntity 时长，或客户端按时刻判断）负责。
			// 这样音乐 mod 自己永远不需要猜"这次 1011 是哪种"，也就不会出现
			// "停不下来 / 换不了碟"那种别扭行为。
			JukeboxSession live = sessions.remove(key);
			CloudDiscClient.LOGGER.info("[CloudDisc] 收到世界事件 1011（唱片机停止/取出唱片）@ {}｜本机有会话={}", pos, live != null);
			if (live != null) {
				live.dispose();
				announcer.announceStop(live, nowTick);
				return Decision.CANCEL;
			}
			holds.remove(key);
		}
		return Decision.PASS;
	}

	/**
	 * 唱片还在唱片机里吗？
	 * <p>用来区分两件都发 1011 的事：「原版以为放完了」与「玩家把唱片拔走了」。
	 * 前者我们要继续放，后者必须真的停。
	 */
	private static boolean recordStillInserted(String dimId, BlockPos pos) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || !mc.world.getRegistryKey().getValue().toString().equals(dimId)) {
			return false;
		}
		net.minecraft.block.BlockState state = mc.world.getBlockState(pos);
		return state.contains(net.minecraft.block.JukeboxBlock.HAS_RECORD)
				&& state.get(net.minecraft.block.JukeboxBlock.HAS_RECORD);
	}

	// ------------------------------------------------------------- 入口：消息

	public void onMessage(Protocol.Msg m, long nowTick) {
		if (m == null || m.t == null || m.dim == null) {
			return;
		}
		long offset = nowTick - m.sentTick; // 发送方 gameTime → 本机 gameTime
		String key = Protocol.key(m.dim, m.pos);

		switch (m.t) {
			case Protocol.T_CLAIM -> {
				Held h = holds.get(key);
				if (h != null) {
					h.claimed = true;
					h.epoch = m.epoch;
					h.expireTick = nowTick + HOLD_TICKS_CLAIMED;
				}
			}
			case Protocol.T_PLAY, Protocol.T_ANSWER -> {
				// 诊断：把关键字段打出来（这类"同步不对"的问题全靠这几个数定位）
				CloudDiscClient.LOGGER.info("[CloudDisc] 收到 {}: startTick={} sentTick={} posMs={} 本机刻={} epoch={}",
						m.t, m.startTick, m.sentTick, m.posMs, nowTick, m.epoch);
				// 防御：发起方可能"还没排定开始刻"就回了话（它还在预取），
				// 这种消息里 startTick 是 0、posMs 也是 0，拿它算出来的偏移完全是垃圾
				// （实测会变成"比发起方早 2 秒开始"）。直接忽略，等它真正的 PLAY。
				if (m.startTick <= 0L && m.posMs <= 0L) {
					CloudDiscClient.LOGGER.info("[CloudDisc] 该消息没有有效开始刻/内容位置（发起方尚未就绪），忽略");
					return;
				}
				JukeboxSession existing = sessions.get(key);
				if (existing != null) {
					if (existing.epoch.equals(m.epoch)) {
						return; // 重复消息
					}
					if (existing.originator) {
						return; // 自己发出去的，别把自己顶掉
					}
					stopQuiet(existing);
				}
				JukeboxSession s = new JukeboxSession(m.dim, m.pos, m.epoch, m.prov, m.track);
				s.title = m.title;
				s.uri = m.uri;
				s.durationMs = m.durMs;
				if (Protocol.T_ANSWER.equals(m.t) && m.posMs > 0L) {
					// 【重进服 / 中途加入】必须用对方给出的"内容位置"定位，**不能**用跨客户端刻换算：
					// 刚进服的客户端 world.getTime() 与房主能差几万刻（实测 -28440 刻、甚至 -1000900ms），
					// 算出来的位置要么离谱、要么越过文件末尾 —— 表现就是"根本不播放"。
					s.localStartTick = nowTick - m.posMs / 50L;
					CloudDiscClient.LOGGER.info("[CloudDisc] 按对方的内容位置定位: {}ms（重进服/中途加入）", m.posMs);
				} else {
					// 【正常开始】对方给的是"未来某一刻开始"，用本机与对方的刻差换算即可（这条已验证可用）。
					s.localStartTick = m.startTick + offset;
				}
				s.state = JukeboxSession.State.PREPARING;
				sessions.put(key, s);
				holds.remove(key);
				CloudDiscClient.LOGGER.info("[CloudDisc] 跟随播放: {} @ {}（预定 {}ms 后开声）",
						m.title, m.dim, Math.max(0L, (s.localStartTick - nowTick) * 50L));
				// 立刻开始预取：希望能在开始刻之前就绪，那样就是"零延迟同时开声"
				beginPrepare(s, false);
			}
			case Protocol.T_STOP -> {
				JukeboxSession s = sessions.get(key);
				if (s != null && (m.epoch == null || m.epoch.equals(s.epoch))) {
					stopQuiet(s);
				}
				holds.remove(key);
			}
			case Protocol.T_QUERY -> {
				JukeboxSession s = sessions.get(key);
				if (s != null) {
					announcer.announceAnswer(s, nowTick);
					claimIfNotScheduledYet(s, nowTick);
				} else {
					claimFromPending(key, nowTick);
				}
			}
			case Protocol.T_QUERY_ALL -> {
				// 有人刚进服 / 想知道全服在放什么：把所有还在放的会话都报一遍
				for (JukeboxSession s : new ArrayList<>(sessions.values())) {
					if (s.pipeline != null && s.state != JukeboxSession.State.DEAD) {
						announcer.announceAnswer(s, nowTick);
						claimIfNotScheduledYet(s, nowTick);
					}
				}
			}
			case Protocol.T_HEARTBEAT -> {
				JukeboxSession s = sessions.get(key);
				if (s != null && s.epoch.equals(m.epoch) && !s.originator) {
					s.hbReceived = true;
					s.hbPosMs = m.posMs;
					s.hbAtTick = m.sentTick + offset;
				} else if (s == null) {
					// 有人在这台机器旁边放歌，而我这边没有会话 —— 典型场景是"从 64 格外
					// 走进了范围"（原版 1010 只发给插碟那一刻 64 格内的人，我收不到）。
					// 主动问一次，让对方把曲目和起始刻告诉我。
					String qKey = key;
					Long last = queryCooldown.get(qKey);
					if (last == null || nowTick - last > 60L) {
						queryCooldown.put(qKey, nowTick);
						CloudDiscClient.LOGGER.info("[CloudDisc] 收到未知坐标的心跳，主动询问该处会话 @ {}", BlockPos.fromLong(m.pos));
						announcer.announceQuery(m.dim, BlockPos.fromLong(m.pos), nowTick);
					}
				}
			}
			default -> {
				// 忽略未知类型
			}
		}
	}

	// ---------------------------------------------------------------- tick

	public void tick(long nowTick) {
		// 1) 右键了但唱片机没真的开始播放
		Iterator<Map.Entry<String, Pending>> pi = pending.entrySet().iterator();
		while (pi.hasNext()) {
			Map.Entry<String, Pending> e = pi.next();
			if (nowTick > e.getValue().expireTick) {
				pi.remove();
			}
		}

		// 2) 等待解答的唱片机：超时就回落到原版声音
		Iterator<Map.Entry<String, Held>> hi = holds.entrySet().iterator();
		while (hi.hasNext()) {
			Map.Entry<String, Held> e = hi.next();
			if (nowTick > e.getValue().expireTick) {
				fallbackVanilla(e.getValue());
				hi.remove();
			}
		}

		// 3) 推进会话
		if (sessions.isEmpty()) {
			return;
		}
		for (JukeboxSession s : new ArrayList<>(sessions.values())) {
			if (s.state == JukeboxSession.State.SCHEDULED) {
				if (nowTick >= s.localStartTick) {
					beginPlayback(s);
				}
			} else if (s.state == JukeboxSession.State.PLAYING) {
				tickPlaying(s, nowTick);
			}
		}
	}

	/**
	 * 打一行"声音引擎到底有没有把我们的音频取走"的诊断。
	 * <p>这是判断"真的没声音"最直接的一行：
	 * <ul>
	 *   <li>{@code playedMs} 一直为 0 → MC 根本没在消费这条流（说明它被拒绝或被立刻停掉了），
	 *       而不是"音量太小"；</li>
	 *   <li>{@code playedMs} 在增长 → 音频确实进了 OpenAL，问题只可能在音量/输出设备。</li>
	 * </ul>
	 */
	private static void logConsumption(JukeboxSession s, AudioPipeline pipe, String when) {
		long played = pipe.playedMs();
		long backlog = pipe.ring().available() / AudioPipeline.BYTES_PER_MS;
		if (played <= 0L) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 起播 {} 后：声音引擎一点都没取数据（playedMs=0，缓冲积压 {}ms）"
					+ " → 这条声音没有被真正播放。请先检查『唱片机/音符盒』音量是不是 0，以及是否被别的东西立刻停掉了",
					when, backlog);
		} else {
			CloudDiscClient.LOGGER.info("[CloudDisc] 起播 {} 后：声音引擎已消费 {}ms，缓冲积压 {}ms（正常）", when, played, backlog);
		}
	}

	private void tickPlaying(JukeboxSession s, long nowTick) {
		AudioPipeline pipe = s.pipeline;
		if (pipe == null) {
			return;
		}
		// 诊断：起播 1 秒 / 5 秒后各打一次"声音引擎到底有没有把我们的音频取走"。
		// 这是判断"真的没声音"最直接的一行：playedMs 一直是 0 = MC 根本没在消费
		// （说明这条声音被拒绝/被停掉了），而不是"音量太小"。
		// 唱片机物理声效：**每个正在播放的会话**每刻都评估一次（射线采集按 4 刻限频，
		// 但"平滑 + 写进 OpenAL 声源"是**每刻**都做的 → 参数延迟只跟 tick 有关，
		// 不再跟着音频块（约 85ms/块）走。传 s.instance 是为了按声音实例精确反查 source id。
		// 之前插在"发起方心跳块"里 → 跟随方（就是听的人）永远不评估 → 隔墙毫无变化。
		dev.clouddisc.audio.Acoustics.tick(s.pos(), s.instance, nowTick);
		long sinceStart = nowTick - s.startedTick;
		if (sinceStart >= 20L && !s.consumptionLogged1s) {
			s.consumptionLogged1s = true;
			logConsumption(s, pipe, "1 秒");
		}
		if (sinceStart >= 100L && !s.consumptionLogged5s) {
			s.consumptionLogged5s = true;
			logConsumption(s, pipe, "5 秒");
		}
		// ---- 关于"同步"：这里刻意不做任何周期性纠偏 ----
		//
		// 原版唱片是怎么同步的？它压根没有"进度"概念：服务端发一个世界事件，
		// 每个客户端在收到的那一瞬各自 SoundManager.play()，所有人在同一个刻开始，
		// 之后各放各的、全程不纠偏 —— 靠"同时开始"就够了。
		//
		// 我之前每秒做一次 seek 纠偏，结果就是用户看到的"走步 / 被强行拽回去"：
		//   ① 度量本身就是错的：playedMs() 统计的是"交给 MC 的字节数"，
		//      而 MC 的 OpenAL 队列里还压着最多 4 秒（pumpBuffers(4)）没播出去，
		//      拿它跟"期望进度"比永远差几秒 → 每秒都触发 seek；
		//   ② 每次 seek 都会清空环形缓冲重新填 → 声音被切成一段一段。
		// 现在只记录"内容位置是否一致"（用各自的开声时刻 + 游戏刻推算），不做动作。
		// 降噪（0.6.2）：**只在"真的不对"时刷日志**。
		// 健康状态下的偏差恒为一个游戏刻（50ms）以内 —— 那是刻的量化精度，不是问题，
		// 所以以前每 10 秒一条"差 -50ms"纯属噪音。现在的策略：
		//   · 偏差 > 150ms  → 立刻 WARN（每 10 秒最多一条），这才是值得看的信号；
		//   · 偏差正常     → 每 60 秒给一条汇总，证明"还在对齐"，平时不吵。
		if (s.hbReceived && !s.originator && nowTick - s.lastDriftLogTick > 200L) {
			s.lastDriftLogTick = nowTick;
			long expected = s.hbPosMs + (nowTick - s.hbAtTick) * 50L;
			long mine = s.msAtStart + (nowTick - s.startedTick) * 50L;
			long diff = mine - expected;
			if (Math.abs(diff) > 150L) {
				if (nowTick - s.lastDriftWarnTick > 200L) {
					s.lastDriftWarnTick = nowTick;
					CloudDiscClient.LOGGER.warn("[CloudDisc] 进度偏差偏大: 发起方 {}ms / 本机 {}ms / 差 {}ms"
							+ "（仅记录、不纠偏；若听感也能察觉，把这行发我）", expected, mine, diff);
				}
			} else if (nowTick - s.lastDriftSummaryTick > 1200L) {
				s.lastDriftSummaryTick = nowTick;
				CloudDiscClient.LOGGER.info("[CloudDisc] 进度对齐正常: 差 {}ms（一个游戏刻 = 50ms，属量化精度）", diff);
			}
		}
		// 发起方按配置的间隔广播进度（聊天通道下间隔会拉长）
		if (s.originator && nowTick - s.lastHeartbeatTick >= announcer.heartbeatIntervalTicks()) {
			s.lastHeartbeatTick = nowTick;
			// 必须报"内容位置"（本机开声刻 + 游戏刻推算），**不能**报 pipe.playedMs()。
			// 原因：playedMs() 是"已经交给 MC 的字节数"，而 attachBufferStream 会立刻
			// pumpBuffers(4)，MC 的 OpenAL 队列一开始就压了约 4 秒 —— 它比真正在响的位置
			// 超前约 4 秒。实测两边日志里那个稳定的 "差 -4000ms" 就是这么来的（并非真的差 4 秒）。
			long contentMs = s.msAtStart + (nowTick - s.startedTick) * 50L;

			announcer.announceHeartbeat(s, contentMs, nowTick);
		}
		// 【0.8.2】被原版提前掐掉之后，由我们自己按真实时长收尾：放到真正的结尾才停。
		// 注意：这里**不再**用方块状态判断"唱片是否被拔走" —— 实测原版自然放完时
		// has_record 也会变 false，两者无法区分。代价：延长段里玩家拔碟不会立刻停
		// （会一直播到这首歌结束），这是为了让"长歌不被掐断"成立而接受的取舍。
		// 【0.9.1】到真实结尾就收尾 —— 无条件生效（不再只在"被原版提前掐掉"的模式下检查）。
		// 时长来源：优先解析服务返回的 durationMs（流式 MP3 解码器给不出时长），否则退回解码器时长。
		if (s.startedTick > 0L) {
			long playedTo = s.msAtStart + (nowTick - s.startedTick) * 50L;
			long realEnd = s.durationMs > 0L ? s.durationMs : pipe.durationMs();
			if (realEnd > 0L && playedTo > realEnd + 500L) {
				CloudDiscClient.LOGGER.info("[CloudDisc] 已播到真实结尾（{}ms）→ 结束播放", realEnd);
				stopQuiet(s);
				announcer.announceStop(s, nowTick);
				return;
			}
		}
		// 播完自动收尾（时长已知的来源，例如本地文件；用"内容位置"而不是 playedMs，
		// 后者比真正在响的位置超前约 4 秒 —— 那是 MC 的 OpenAL 预队列）
		long dur = pipe.durationMs();
		long contentNow = s.msAtStart + (nowTick - s.startedTick) * 50L;
		if (dur > 0L && s.startedTick > 0L && contentNow > dur + 500L) {
			stopQuiet(s);
			announcer.announceStop(s, nowTick);
		}
	}

	// ------------------------------------------------------------ 内部流程

	private void startSession(JukeboxSession s, long nowTick) {
		MusicProvider provider;
		if (s.provider == null || s.provider.isBlank()) {
			// 没写 provider：明显是网易云链接就自动交给 163，否则用默认 provider
			provider = dev.clouddisc.music.NeteaseLinkProvider.looksLikeNetease(s.trackId)
					? Providers.byId("163")
					: Providers.defaultProvider(cfg);
		} else {
			provider = Providers.byId(s.provider);
		}
		if (provider == null) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 未知音源 provider: {}（可用: local / 163(netease/wy) / url）", s.provider);
			return;
		}
		CloudDiscClient.LOGGER.info("[CloudDisc] 开始解析曲目: provider={} trackId={}", provider.id(), s.trackId);
		provider.resolve(s.trackId, cfg).whenComplete((track, err) ->
				MinecraftClient.getInstance().execute(() -> {
					if (err != null || track == null) {
						pending.remove(s.key);
						CloudDiscClient.LOGGER.warn("[CloudDisc] 解析失败 {}:{} -> {}", s.provider, s.trackId, String.valueOf(err));
						return;
					}
					// 曲名优先级（实测 0.6.0 起的规则）：
					//   ① 查回来的**真名**（歌名 - 歌手）—— 这是"物品只存 id、显示查真名"的核心
					//   ② 若玩家还写了备注（@186016（我喜欢的）），拼在真名后面，两边都保留
					//   ③ 查不到真名就只用备注
					//   ④ 都没有 → 兜底 "网易云 #id"
					String annotation = dev.clouddisc.disc.DiscName.annotation(s.trackId);
					String resolvedTitle = track.title();
					boolean hasReal = resolvedTitle != null && !resolvedTitle.isBlank()
							&& !resolvedTitle.startsWith("网易云 #");
					if (hasReal && annotation != null) {
						s.title = resolvedTitle + "（" + annotation + "）";
					} else if (hasReal) {
						s.title = resolvedTitle;
					} else if (annotation != null) {
						s.title = annotation;
					} else {
						s.title = resolvedTitle;
					}
					CloudDiscClient.LOGGER.info("[CloudDisc] 曲名: {}（真名={} 备注={}）", s.title, resolvedTitle, annotation);
					s.uri = track.uri();
					s.durationMs = track.durationMsHint();
					s.state = JukeboxSession.State.PREPARING;
					pending.remove(s.key);
					JukeboxSession old = sessions.put(s.key, s);
					if (old != null && old != s) {
						old.dispose();
					}
					// 关键（参考原版模型）：**先把自己这边的音频准备好，再广播"统一开始刻"**。
					// 这样 lead 只需要覆盖别人的下载时间，而不是"我的下载 + 别人的下载"；
					// 各端才能在同一个刻真正同时开声。
					beginPrepare(s, true);
				}));
	}

	/**
	 * 下载 + 解码 + 预缓冲（预取开头）。
	 * <p>准备好之后不立刻开声：等 {@code localStartTick} 到点再由 {@link #beginPlayback} 开声，
	 * 这就是"同时播放 = 最好的同步"（原版唱片就是这么做的：所有客户端收到同一个世界事件后
	 * 各自 {@code play()}，全程不做任何进度纠偏）。
	 */
	private void beginPrepare(JukeboxSession s, boolean announceAfterReady) {
		final String uri = resolveUriForThisClient(s);
		if (uri == null || uri.isBlank()) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 会话没有可用 uri（可能来自不携带 URI 的通道）: {}", s.title);
			stopQuiet(s);
			return;
		}
		final TrackFetcher.Trust trust = s.originator
				? TrackFetcher.Trust.SELF
				: (cfg.blockPeerPrivateUrls ? TrackFetcher.Trust.PEER_BLOCK_PRIVATE : TrackFetcher.Trust.PEER_ALLOW_PRIVATE);
		CloudDiscClient.LOGGER.info("[CloudDisc] 预取音频: uri={}｜来源={}", uri, trust);
		CompletableFuture.supplyAsync(() -> {
			Path file = TrackFetcher.fetch(uri, cfg.resolveCacheDir(), cfg.maxTrackMb, cfg.httpHeaders, trust).join();
			// 每次拿到新文件后收拾一次缓存：缓存只占玩家本机，且不超上限
			TrackFetcher.pruneCache(cfg.resolveCacheDir(), cfg.cacheMaxMb);
			PcmSource source = openSource(file);
			CloudDiscClient.LOGGER.info("[CloudDisc] 已就绪: {}（{} Hz / {} 声道 / 时长 {}ms）",
					file.getFileName(), source.sampleRate(), source.channels(), source.durationMillis());
			AudioPipeline pipe = new AudioPipeline(source, cfg.outputGain);
			// 先把"开头"预缓冲好。正常情况（大家都按时准备好）开声时是零延迟的。
			boolean ok = pipe.awaitPrebuffer(cfg.prebufferMs, 15_000L);
			if (!ok) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 预缓冲超时（只缓冲了 {}ms），仍继续",
						pipe.ring().available() / AudioPipeline.BYTES_PER_MS);
			}
			return pipe;
		}, WORKER).whenComplete((pipe, err) -> MinecraftClient.getInstance().execute(() -> {
			if (s.state == JukeboxSession.State.DEAD) {
				if (pipe != null) {
					pipe.close();
				}
				return;
			}
			if (err != null || pipe == null) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 预取失败: {}", String.valueOf(err));
				stopQuiet(s);
				return;
			}
			s.pipeline = pipe;
			s.state = JukeboxSession.State.SCHEDULED;
			if (announceAfterReady) {
				s.localStartTick = now() + cfg.startLeadTicks;
				CloudDiscClient.LOGGER.info("[CloudDisc] 本机已就绪，广播统一开始刻: {} 刻后开声", cfg.startLeadTicks);
				announcer.announcePlay(s, now());
			}
		}));
	}

	/** 起播位置的安全上限：超过这个值说明定位算错了，宁可不放也别静悄悄地放个寂寞。 */
	private static final long MAX_START_POSITION_MS = 60L * 60L * 1000L;

	/** 到点开声。 */
	private void beginPlayback(JukeboxSession s) {
		AudioPipeline pipe = s.pipeline;
		if (pipe == null) {
			stopQuiet(s);
			return;
		}
		long lateMs = Math.max(0L, (now() - s.localStartTick) * 50L);
		if (lateMs > MAX_START_POSITION_MS) {
			// 定位算错的兜底：以前这种情况会去 seek 一个越过文件末尾的位置，
			// 结果管道直接读到结尾 → MC 收到空缓冲 → 一点声音都没有，而日志看不出来。
			CloudDiscClient.LOGGER.error("[CloudDisc] 起播位置离谱（{}ms，超过 60 分钟），放弃本次播放以免静音。"
					+ "请把这行连同上面的『收到 …』『跟随播放』一起发我", lateMs);
			stopQuiet(s);
			return;
		}
		if (lateMs <= 250L) {
			// 正常情况：缓冲里就是"开头"，直接开声，零延迟。
			// 注意这里**不做 seek** —— seek 会清空刚预缓冲好的数据，白白制造延迟。
			playNow(s, pipe, 0L);
			return;
		}
		// 少数情况：本机没赶上开始刻（下载慢，或本来就是中途加入）。
		// 只做一次性 seek 到"此刻应有的位置"，之后再也不纠偏。
		s.state = JukeboxSession.State.BUFFERING;
		// 位置必须在**开声前的最后一刻**重算：下载/解码花掉的时间要补上，
		// 否则中途加入的人会永远落后"准备耗时"那么多（这也是之前"走步"的根因之一）。
		// 这里用单调时钟补上准备工作耗时，避免跨线程读游戏刻。
		final long baseTick = now();
		final long baseNanos = System.nanoTime();
		CloudDiscClient.LOGGER.info("[CloudDisc] 起播已过开始刻（晚了约 {}ms）：一次性 seek 对齐后再开声", lateMs);
		CompletableFuture.supplyAsync(() -> {
			long extraMs = (System.nanoTime() - baseNanos) / 1_000_000L;
			long pos = Math.max(0L, (baseTick - s.localStartTick) * 50L + extraMs);
			boolean seeked = pipe.requestSeekBlocking(pos, 8_000L);
			if (!seeked) {
				// 以前这里是静默的：seek 超时后照样 play()，但放的是缓冲里那份旧位置的数据，
				// 表现为"中途加入的人差了半分钟"。现在把它变成一条明确的错误。
				CloudDiscClient.LOGGER.error("[CloudDisc] seek 到 {}ms 未生效（超时）—— 将从缓冲里现有的位置开始，可能不同步", pos);
			}
			pipe.awaitPrebuffer(cfg.prebufferMs, 15_000L);
			return pos;
		}, WORKER).whenComplete((pos, err) -> MinecraftClient.getInstance().execute(() -> {
			if (s.state == JukeboxSession.State.DEAD || pos == null) {
				return;
			}
			playNow(s, pipe, pos);
		}));
	}

	/** HUD 是单行显示，太长会糊成一片；这里做个温和的截断。 */
	private static String trimForHud(String title) {
		if (title == null) {
			return "";
		}
		return title.length() <= 60 ? title : title.substring(0, 59) + "…";
	}

	/** 注册流 + 交给原版声音系统播放。到这里就完全是 MC 自己在放了。 */
	private void playNow(JukeboxSession s, AudioPipeline pipe, long positionMs) {
		// ① 先停掉"这个唱片机上原版正在响的那条"，再放我们自己的。
		//
		// 为什么必须有这一步（实测 bug：有时候会卡出原版的音乐）：原版的声音有两种来源，
		// 都可能在此时还在响 ——
		//   · 我们主动 PASS 让原版播的（收到 1010 时还没发现同伴，见 onWorldEvent 的 hasPeers 分支）；
		//   · 等待窗口超时后我们补的那条原版回退。
		// 这两种情况下原版已经响了，而我们随后接上 = **两条音轨同时出声**。
		// playSong(null, pos) 是原版自己的停止路径：从 playingSongs 摘掉 + SoundManager.stop。
		try {
			MinecraftClient.getInstance().worldRenderer.playSong(null, s.pos());
		} catch (Throwable t) {
			CloudDiscClient.LOGGER.debug("[CloudDisc] 停原版唱片实例失败（不影响我们播放）: {}", t.toString());
		}
		Identifier streamId = StreamRegistry.nextId();
		PcmAudioStream stream = new PcmAudioStream(pipe.ring(), 150L, pipe::close);
		StreamRegistry.put(streamId, stream);
		CloudDiscSoundInstance instance = new CloudDiscSoundInstance(streamId, s.pos(), cfg.jukeboxVolume);
		s.streamId = streamId;
		s.stream = stream;
		s.instance = instance;
		s.startedTick = now();
		s.msAtStart = positionMs;
		// 交给原版声音系统：定位、音量类别、停止逻辑全部沿用原版
		MinecraftClient.getInstance().getSoundManager().play(instance);
		s.state = JukeboxSession.State.PLAYING;
		// 还原原版行为：屏幕下方显示"正在播放: xxx"。
		// 原版这一句在 WorldRenderer#playSong 里 —— inGameHud.setRecordPlayingOverlay(item.getDescription())；
		// 我们为了不发出原版声音会取消 1010 事件，顺带把它一起丢掉了。这里按原版的方式补回来：
		// 同一个方法、同一个时机（真正开声的那一刻），所以观感与原版一致（淡出也由 HUD 自己管）。
		MinecraftClient.getInstance().inGameHud.setRecordPlayingOverlay(
				net.minecraft.text.Text.literal(trimForHud(s.title == null || s.title.isBlank() ? s.trackId : s.title)));
		// 同时打出"请求位置 / 实际位置"：这两个数不一致就说明 seek 没生效（排查这类问题的关键一行）
		CloudDiscClient.LOGGER.info("[CloudDisc] 已开始播放: {}（请求位置 {}ms / 实际 {}ms）｜流 id={}｜预缓冲 {}ms",
				s.title, positionMs, pipe.playedMs(), streamId, pipe.ring().available() / AudioPipeline.BYTES_PER_MS);
	}

	/**
	 * 跟随者拿到 URI 之后要做的修正。
	 *
	 * <p>发起方给的<b>本地文件路径</b>是发起方机器上的绝对路径，在别人机器上并不存在。
	 * 这种情况下尝试用同一个 provider + trackId 在<b>本机</b>重新解析一次
	 * （例如 {@code @local:晴天} → 对端在自己 {@code clouddisc-music} 里找同名文件）。
	 *
	 * <p>http(s) 的 URI 直接沿用：网易云这类在线音源不需要对端配置任何东西（这正是
	 * "只有发起方需要配置音源"的设计收益）。
	 */
	private String resolveUriForThisClient(JukeboxSession s) {
		String uri = s.uri;
		if (s.originator) {
			return uri;
		}
		boolean missing = uri == null || uri.isBlank();
		boolean isHttp = !missing && (uri.startsWith("http://") || uri.startsWith("https://"));
		if (isHttp) {
			return uri; // 在线音源：直接沿用发起方的地址，对端不需要任何配置
		}
		// 两种情况要本机重新解析：
		// ① 发起方给的是它自己机器上的本地路径（对端根本不存在这个文件）；
		// ② 聊天兜底通道为了不超 256 字符会把 uri 剥掉（missing），只能靠对端自己解析同一个 key。
		MusicProvider provider = (s.provider == null || s.provider.isBlank()) ? null : Providers.byId(s.provider);
		if (provider == null || s.trackId == null) {
			return uri;
		}
		try {
			MusicProvider.ResolvedTrack local = provider.resolve(s.trackId, cfg).get(5, java.util.concurrent.TimeUnit.SECONDS);
			CloudDiscClient.LOGGER.info("[CloudDisc] 用本机音源重新解析『{}』（{}) : {} → {}",
					s.trackId, missing ? "通道未携带 uri" : "发起方给的是本地路径", uri, local.uri());
			return local.uri();
		} catch (Exception e) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 本机重新解析『{}』失败（{}），沿用原值", s.trackId, String.valueOf(e));
			return uri;
		}
	}

	/** 判定音频格式并分派解码器。
	 *
	 * <p><b>只信文件头，不信扩展名</b>。踩过的坑：网易云的直链是
	 * {@code .../outer/url?id=1330348068.mp3}，{@code .mp3} 在 <b>query</b> 里，
	 * 而按 {@code URI.getPath()} 推断扩展名会得到 {@code .bin} →
	 * 明明音频已经下载成功（5 MB 的 mp3），却在"按扩展名挑解码器"时失败。
	 * 魔数识别从根上避免这类问题（缓存文件名、重定向、无扩展名链接都不再重要）。
	 */
	private static String detectFormat(Path file) {
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
			byte[] head = in.readNBytes(12);
			if (head.length >= 4) {
				if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
					return "ogg";
				}
				if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
					return "flac";
				}
				if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
					return "mp3"; // 带 ID3v2 标签
				}
				if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
					return "mp3"; // 裸 MPEG 帧同步字
				}
				if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
						&& head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
					return "wav";
				}
			}
		} catch (Exception ignored) {
			// 读不了就退回扩展名
		}
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".ogg") || name.endsWith(".oga")) {
			return "ogg";
		}
		if (name.endsWith(".wav")) {
			return "wav";
		}
		if (name.endsWith(".mp3")) {
			return "mp3";
		}
		if (name.endsWith(".flac")) {
			return "flac";
		}
		return "unknown";
	}

	private static PcmSource openSource(Path file) {
		String kind = detectFormat(file);
		try {
			switch (kind) {
				case "ogg":
					return OggPcmSource.open(file);
				case "wav":
					return dev.clouddisc.audio.WavPcmSource.open(file);
				case "mp3":
					return dev.clouddisc.audio.Mp3PcmSource.open(file);
				default:
					break;
			}
		} catch (Exception e) {
			throw new RuntimeException("解码失败(" + file.getFileName() + "，判定为 " + kind + "): " + e, e);
		}
		if ("flac".equals(kind)) {
			throw new UnsupportedOperationException(
					"FLAC 暂不支持（需要再引入 org.jflac:jflac-codec，见设计文档 §5.3）: " + file.getFileName());
		}
		throw new UnsupportedOperationException("认不出这个音频格式，已支持 OGG / WAV(16bit PCM) / MP3: " + file.getFileName());
	}

	/** 等待窗口内没人认领 → 把原版唱片声补上（会晚一点点，但总比一直静音好）。 */
	private void fallbackVanilla(Held h) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld world = mc.world;
		if (world == null || !world.getRegistryKey().getValue().toString().equals(h.dim)) {
			return;
		}
		Item item = Item.byRawId(h.itemId);
		if (!(item instanceof MusicDiscItem disc)) {
			return;
		}
		// 关键：走**原版自己的入口** WorldRenderer#playSong，而不是 world.playSound。
		// 差别很大：
		//   · playSong 会把它登记进 playingSongs（原版记账表）→ 之后能被原版/我们停掉、
		//     HUD 会显示曲名、鹦鹉会跳舞；
		//   · 以前用 world.playSound 放的是"野生声音"，没有任何人能停它 ——
		//     等我们这边真接上时，就变成**两条音轨同时响**（实测 bug：有时候会卡出原版的音乐）。
		mc.worldRenderer.playSong(disc.getSound(), h.pos);
		CloudDiscClient.LOGGER.debug("[CloudDisc] 无人认领，回落原版唱片声 @ {}（走原版 playSong，可被接管时停掉）", h.pos);
	}

	private void stopQuiet(JukeboxSession s) {
		sessions.remove(s.key);
		s.dispose();
	}

	/** 断开连接/切维度：全部收掉，避免声音残留。 */
	public void shutdown() {
		for (JukeboxSession s : new ArrayList<>(sessions.values())) {
			s.dispose();
		}
		sessions.clear();
		pending.clear();
		holds.clear();
		StreamRegistry.abortAll();
	}
}
