package dev.clouddisc.jukeboxlib;

/**
 * lib mod 的设置。
 *
 * <p>刻意做得极小：只有三个值，且**不读写任何文件**（避免和音乐 mod 的配置打架）。
 * 想改的话直接改这里的常量重新构建，或者后续版本再接配置文件。
 */
public final class JukeboxLibConfig {
	/** 总开关：false = 完全不干预原版（等同于没装本 lib）。 */
	public static boolean ENABLED = true;

	/** 只对"自定义名以此前缀开头"的唱片解除限制 —— 服务器上别人的原版唱片完全不受影响。 */
	public static String DISC_PREFIX = "@";

	/**
	 * 延长的上限（分钟）。
	 * <p>为什么不无限延长：碟不被取出时，方块会一直显示"在播放"（音符粒子与 GameEvent 持续发出）。
	 * 给一个上限，超过后交回原版结束，避免在服务器上留下长期副作用。
	 */
	public static int MAX_EXTEND_MINUTES = 6;

	public static long maxExtendTicks() {
		return MAX_EXTEND_MINUTES * 60L * 20L;
	}

	private JukeboxLibConfig() {
	}
}
