package dev.clouddisc.sync;

import dev.clouddisc.CloudDiscClient;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 主通道：UDP 点对点。
 *
 * <p>两个 socket，各司其职：
 * <ul>
 *   <li><b>数据 socket</b>：绑在 {@code udpPort}（被占用会自动往后试），只负责同步消息。</li>
 *   <li><b>发现 socket</b>：一个 {@link MulticastSocket}，绑在固定的
 *       {@link #DISCOVERY_PORT} 并加入组播组 {@link #GROUP}，只负责"我在哪、我的数据端口是多少"。</li>
 * </ul>
 *
 * <p>为什么要拆开（这是"同一台电脑双开两个客户端"能否自动发现的关键）：
 * 如果发现包也发到"自己的数据端口"，那么当两个客户端的端口不同时，
 * A 发的包永远到不了 B，互相发现不了。把发现固定在一个公共的组播组+端口上，
 * 并在 presence 包里带上<b>自己的数据端口</b>，就与各自的端口无关了。
 *
 * <p>已知限制：如果网络环境屏蔽组播，会自动退回"广播 + 同端口"以及配置里的手动对端
 * （{@code udpPeers}）。跨公网仍需信令交换公网地址后用 {@link #addManualPeer(String)}。
 */
public final class UdpTransport implements Transport {
	private static final String GROUP = "224.0.2.61";
	private static final int DISCOVERY_PORT = 25465;
	private static final String PRESENCE_PREFIX = "{\"cd\":\"hi\"";
	private static final long PEER_TIMEOUT_MS = 10_000L;
	private static final long BEACON_INTERVAL_MS = 2_000L;
	private static final int MAX_DATAGRAM = 1_400;
	/** 数据端口被占用时往后试几个端口。 */
	private static final int PORT_TRIES = 10;

	private static final class Peer {
		final InetSocketAddress address;
		volatile long lastSeen;

		Peer(InetSocketAddress address, long lastSeen) {
			this.address = address;
			this.lastSeen = lastSeen;
		}
	}

	private final int requestedPort;
	private final boolean lanDiscovery;
	private final List<String> manualPeers;
	private final String instanceId = UUID.randomUUID().toString();
	private final Map<String, Peer> peers = new ConcurrentHashMap<>();
	/** 实例 id → 我们记下的地址（仅用于诊断日志）。 */
	private final Map<String, String> knownInstances = new ConcurrentHashMap<>();

	private DatagramSocket dataSocket;
	private MulticastSocket discoverySocket;
	private InetAddress groupAddress;
	private int actualPort = -1;
	private Thread dataThread;
	private Thread discoveryThread;
	private Thread beaconThread;
	private volatile boolean running;
	private volatile Consumer<Protocol.Msg> listener;
	private volatile long lastEmptyWarn;

	public UdpTransport(int requestedPort, boolean lanDiscovery, List<String> manualPeers) {
		this.requestedPort = requestedPort;
		this.lanDiscovery = lanDiscovery;
		this.manualPeers = manualPeers == null ? Collections.emptyList() : manualPeers;
	}

	@Override
	public String name() {
		return "udp";
	}

	/** 实际绑定的数据端口（-1 表示没起来）。 */
	public int actualPort() {
		return actualPort;
	}

	@Override
	public void start(Consumer<Protocol.Msg> listener) {
		this.listener = listener;

		// 1) 数据 socket：端口占用就往后试，避免"双开时第二个客户端直接没声音"
		//
		// 注意：这里**故意不设** setReuseAddress(true)。Windows 允许两个进程用 SO_REUSEADDR
		// 绑同一个 UDP 端口，于是双开时两个实例都会"成功"绑到 25566，而单播数据只会交给
		// 其中一个 socket —— 结果是发起方发的 PLAY 另一个实例收不到（实测踩过：
		// 对方一直放原版唱片）。不设这个选项，第二个实例的 bind 会失败，从而走到下面的端口自增。
		for (int i = 0; i < PORT_TRIES && dataSocket == null; i++) {
			int tryPort = requestedPort + i;
			try {
				DatagramSocket s = new DatagramSocket(null);
				s.setBroadcast(true);
				s.bind(new InetSocketAddress(tryPort));
				dataSocket = s;
				actualPort = tryPort;
				if (i > 0) {
					CloudDiscClient.LOGGER.info("[CloudDisc] UDP 端口 {} 被占用，改用 {}", requestedPort, tryPort);
				}
			} catch (Exception e) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 绑定 UDP {} 失败: {}", tryPort, e.toString());
			}
		}
		if (dataSocket == null) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] UDP 数据端口 {}~{} 全部绑定失败，UDP 通道不可用", requestedPort, requestedPort + PORT_TRIES - 1);
			return;
		}

		running = true;
		dataThread = new Thread(() -> receiveLoop(dataSocket, false), "CloudDisc-UDP-Rx");
		dataThread.setDaemon(true);
		dataThread.start();

		// 2) 发现 socket（组播）：固定端口，与数据端口无关
		if (lanDiscovery) {
			try {
				MulticastSocket ms = new MulticastSocket(DISCOVERY_PORT);
				ms.setReuseAddress(true);
				ms.setLoopbackMode(false); // 允许本机接收自己/同伴发出的组播（双开测试必需）
				groupAddress = InetAddress.getByName(GROUP);
				ms.joinGroup(groupAddress);
				discoverySocket = ms;
				discoveryThread = new Thread(() -> receiveLoop(discoverySocket, true), "CloudDisc-UDP-Discovery");
				discoveryThread.setDaemon(true);
				discoveryThread.start();
				CloudDiscClient.LOGGER.info("[CloudDisc] 组播发现已启动: {}:{}（数据端口 {}）", GROUP, DISCOVERY_PORT, actualPort);
			} catch (Exception e) {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 组播发现启动失败（退回广播 + 手动对端）: {}", e.toString());
			}
			beaconThread = new Thread(this::beaconLoop, "CloudDisc-UDP-Beacon");
			beaconThread.setDaemon(true);
			beaconThread.start();
		}

		// 3) 配置里手写的对端
		for (String p : manualPeers) {
			if (addManualPeer(p)) {
				CloudDiscClient.LOGGER.info("[CloudDisc] 已加入手动对端 {}", p);
			} else {
				CloudDiscClient.LOGGER.warn("[CloudDisc] 手动对端格式不对（应为 ip:port）: {}", p);
			}
		}

		CloudDiscClient.LOGGER.info("[CloudDisc] UDP 通道已启动，数据端口 {}，实例 id {}", actualPort, instanceId.substring(0, 8));
	}

	// ------------------------------------------------------------ 发送/对端

	@Override
	public void send(Protocol.Msg msg) {
		DatagramSocket s = dataSocket;
		if (s == null || s.isClosed()) {
			return;
		}
		byte[] data = Protocol.toBytes(msg);
		if (data.length > MAX_DATAGRAM) {
			CloudDiscClient.LOGGER.warn("[CloudDisc] 消息过大({}B)，已丢弃", data.length);
			return;
		}
		if (peers.isEmpty()) {
			long now = System.currentTimeMillis();
			if (now - lastEmptyWarn > 30_000L) {
				lastEmptyWarn = now;
				CloudDiscClient.LOGGER.info("[CloudDisc] 暂无可见对端（同伴未装/未开/被网络挡住；可用配置 udpPeers 手动指定）");
			}
			return;
		}
		for (Peer p : peers.values()) {
			try {
				s.send(new DatagramPacket(data, data.length, p.address));
			} catch (Exception e) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] 发送失败 {}: {}", p.address, e.toString());
			}
		}
	}

	/** 手动加入对端，参数形如 {@code 1.2.3.4:25566} 或 {@code 127.0.0.1:25567}。 */
	public boolean addManualPeer(String hostPort) {
		try {
			int i = hostPort.lastIndexOf(':');
			String host = i > 0 ? hostPort.substring(0, i) : hostPort;
			int p = i > 0 ? Integer.parseInt(hostPort.substring(i + 1)) : requestedPort;
			return addPeer(new InetSocketAddress(InetAddress.getByName(host), p));
		} catch (Exception e) {
			return false;
		}
	}

	private boolean addPeer(InetSocketAddress address) {
		if (address == null || address.isUnresolved() || address.getAddress() == null) {
			return false;
		}
		// 拒绝"自己"：环回地址 + 与本机数据端口相同 = 我自己
		if (address.getAddress().isLoopbackAddress() && address.getPort() == actualPort) {
			return false;
		}
		String key = address.getAddress().getHostAddress() + ":" + address.getPort();
		boolean isNew = !peers.containsKey(key);
		peers.put(key, new Peer(address, System.currentTimeMillis()));
		return isNew;
	}

	@Override
	public int peerCount() {
		expirePeers();
		return peers.size();
	}

	@Override
	public boolean carriesUri() {
		return true;
	}

	@Override
	public boolean isReady() {
		return dataSocket != null && !dataSocket.isClosed();
	}

	// ------------------------------------------------------------ 接收

	private void receiveLoop(DatagramSocket socket, boolean fromDiscovery) {
		byte[] buffer = new byte[MAX_DATAGRAM];
		while (running) {
			try {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				socket.receive(packet);
				if (!running) {
					break;
				}
				String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);

				if (text.startsWith(PRESENCE_PREFIX)) {
					handlePresence(packet, text);
					continue;
				}
				Protocol.Msg msg = Protocol.fromJson(text);
				if (msg == null) {
					continue;
				}
				// 数据包的源端口就是对方的数据端口，顺手记为对端
				addPeer((InetSocketAddress) packet.getSocketAddress());
				Consumer<Protocol.Msg> l = listener;
				if (l != null) {
					l.accept(msg);
				}
			} catch (Exception e) {
				if (running) {
					CloudDiscClient.LOGGER.debug("[CloudDisc] UDP 接收异常({}): {}", fromDiscovery ? "发现" : "数据", e.toString());
				}
			}
		}
	}

	private void handlePresence(DatagramPacket packet, String text) {
		String senderId = field(text, "i");
		if (senderId != null && senderId.equals(instanceId)) {
			return; // 自己发的（组播环回 / 广播），别把自己当同伴
		}
		String portText = field(text, "p");
		int peerPort = actualPort;
		if (portText != null) {
			try {
				peerPort = Integer.parseInt(portText);
			} catch (NumberFormatException ignored) {
				// 用源端口兜底
			}
		}
		InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
		if (portText == null) {
			peerPort = from.getPort();
		}
		InetSocketAddress peer = new InetSocketAddress(from.getAddress(), peerPort);
		// 同一个实例可能被记两次（一次走局域网 IP、一次走 127.0.0.1），用实例 id 去重。
		if (senderId != null) {
			String previous = knownInstances.put(senderId, from.getAddress().getHostAddress() + ":" + peerPort);
			if (previous != null && !previous.equals(from.getAddress().getHostAddress() + ":" + peerPort)) {
				peers.remove(previous);
			}
		}
		boolean isNew = addPeer(peer);
		if (isNew) {
			CloudDiscClient.LOGGER.info("[CloudDisc] 发现同伴 {}（数据端口 {}）", from.getAddress().getHostAddress(), peerPort);
			// 单播回一份，让对方也知道我
			DatagramSocket s = dataSocket;
			if (s != null && !s.isClosed()) {
				try {
					byte[] reply = presenceBytes().getBytes(StandardCharsets.UTF_8);
					s.send(new DatagramPacket(reply, reply.length, from.getAddress(), DISCOVERY_PORT));
				} catch (Exception ignored) {
					// ignore
				}
			}
		}
	}

	/** 极小的 JSON 取值（避免为此引入解析器）。 */
	private static String field(String json, String key) {
		int i = json.indexOf("\"" + key + "\":");
		if (i < 0) {
			return null;
		}
		int s = i + key.length() + 3;
		if (s >= json.length()) {
			return null;
		}
		if (json.charAt(s) == '"') {
			int e = json.indexOf('"', s + 1);
			return e < 0 ? null : json.substring(s + 1, e);
		}
		int e = s;
		while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
			e++;
		}
		return json.substring(s, e);
	}

	private String presenceBytes() {
		return "{\"cd\":\"hi\",\"i\":\"" + instanceId + "\",\"p\":" + actualPort + "}";
	}

	// ------------------------------------------------------------ 广播发现

	private void beaconLoop() {
		byte[] payload = presenceBytes().getBytes(StandardCharsets.UTF_8);
		while (running) {
			try {
				MulticastSocket ms = discoverySocket;
				if (ms != null && !ms.isClosed() && groupAddress != null) {
					// 组播（首选：与端口无关，双开也能互相发现）
					try {
						ms.send(new DatagramPacket(payload, payload.length, groupAddress, DISCOVERY_PORT));
					} catch (Exception ignored) {
						// ignore
					}
				}
				// 广播兜底（组播被屏蔽的网络）+ 环回（同机测试）
				DatagramSocket s = dataSocket;
				if (s != null && !s.isClosed()) {
					for (InetAddress target : broadcastTargets()) {
						try {
							s.send(new DatagramPacket(payload, payload.length, target, DISCOVERY_PORT));
							// 同时往"数据端口"发一份：兼容两台机器都用默认端口的老式发现
							s.send(new DatagramPacket(payload, payload.length, target, actualPort));
						} catch (Exception ignored) {
							// 单个网卡失败不影响其它
						}
					}
				}
				expirePeers();
				Thread.sleep(BEACON_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				CloudDiscClient.LOGGER.debug("[CloudDisc] beacon 异常: {}", e.toString());
			}
		}
	}

	/** 255.255.255.255 + 各网卡的子网广播地址 + 环回。 */
	private List<InetAddress> broadcastTargets() {
		List<InetAddress> list = new ArrayList<>();
		try {
			list.add(InetAddress.getByName("255.255.255.255"));
			list.add(InetAddress.getByName("127.0.0.1"));
		} catch (Exception ignored) {
			// ignore
		}
		try {
			Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
			while (ifaces.hasMoreElements()) {
				NetworkInterface ni = ifaces.nextElement();
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
					continue;
				}
				for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
					InetAddress b = ia.getBroadcast();
					if (b != null) {
						list.add(b);
					}
				}
			}
		} catch (Exception ignored) {
			// ignore
		}
		return list;
	}

	private void expirePeers() {
		long now = System.currentTimeMillis();
		peers.entrySet().removeIf(e -> now - e.getValue().lastSeen > PEER_TIMEOUT_MS);
	}

	@Override
	public void close() {
		running = false;
		if (discoverySocket != null) {
			try {
				if (groupAddress != null) {
					discoverySocket.leaveGroup(groupAddress);
				}
			} catch (Exception ignored) {
				// ignore
			}
			discoverySocket.close();
		}
		if (dataSocket != null) {
			dataSocket.close();
		}
		for (Thread t : new Thread[] { dataThread, discoveryThread, beaconThread }) {
			if (t != null) {
				t.interrupt();
			}
		}
		peers.clear();
	}
}
