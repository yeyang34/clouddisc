package dev.clouddisc.audio;

/**
 * 只为了在音频包内拿到一个 logger，避免各处重复写。
 */
final class CloudDiscAudio {
	static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("CloudDisc");

	private CloudDiscAudio() {
	}
}
