package dev.clouddisc.audio;

/**
 * 字节环形缓冲：解码线程写，MC 声音线程读。
 *
 * <p><b>关键设计（依据 1.20.1 反编译源码）</b>：MC 的 {@code Channel.pumpBuffers} 是这样处理的：
 * <ul>
 *   <li>{@code getBuffer(...)} 返回 <b>null</b> → <b>安全跳过</b>这一轮，不会结束声音；</li>
 *   <li>返回空缓冲 → 被当成"没有数据"，可能导致这条声音停掉；</li>
 *   <li>抛 {@code IOException} → 只记一条错误日志，之后**不再 pump**（声音悄悄没了）。</li>
 * </ul>
 * 所以欠载时正确做法是<b>返回 null</b>（让 MC 下一轮再来），只有真正读完文件才返回空缓冲收尾。
 * 这个类负责区分这两种情况（-1 = EOF，0 = 欠载）。
 */
public final class PcmRingBuffer {
	private final byte[] data;
	private int readPos;
	private int writePos;
	private int available;
	private boolean finished;
	private volatile boolean starved;

	public PcmRingBuffer(int capacityBytes) {
		int cap = Integer.highestOneBit(Math.max(1024, capacityBytes - 1)) << 1;
		this.data = new byte[cap];
	}

	public int capacity() {
		return data.length;
	}

	public synchronized int available() {
		return available;
	}

	public boolean starved() {
		return starved;
	}

	/** 解码线程：写入；缓冲满则阻塞等待（对解码线程形成天然背压）。 */
	public synchronized void write(byte[] src, int off, int len) {
		int written = 0;
		while (written < len) {
			while (available == data.length && !finished) {
				try {
					wait(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			if (finished) {
				return;
			}
			int chunk = Math.min(len - written, data.length - available);
			for (int i = 0; i < chunk; i++) {
				data[writePos] = src[off + written + i];
				writePos = (writePos + 1) & (data.length - 1);
			}
			available += chunk;
			written += chunk;
			notifyAll();
		}
	}

	/**
	 * 音频线程：尽量凑满 len 字节。
	 *
	 * @return 实际写入 dst 的字节数；<b>-1 表示流已真正结束</b>；0 表示暂时欠载
	 *         （调用方应返回 null 让 MC 下一轮再来，绝不能当成 EOF）
	 */
	public synchronized int read(byte[] dst, int off, int len, long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		int got = 0;
		while (got < len) {
			int chunk = Math.min(len - got, available);
			if (chunk > 0) {
				for (int i = 0; i < chunk; i++) {
					dst[off + got + i] = data[readPos];
					readPos = (readPos + 1) & (data.length - 1);
				}
				available -= chunk;
				got += chunk;
				notifyAll();
				continue;
			}
			if (finished) {
				break;
			}
			long remain = (deadline - System.nanoTime()) / 1_000_000L;
			if (remain <= 0) {
				break;
			}
			try {
				wait(Math.min(remain, 20L));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (got == 0) {
			if (finished) {
				return -1; // 真 EOF
			}
			starved = true;
			return 0; // 欠载
		}
		if (got < len) {
			starved = true;
		}
		return got;
	}

	/** 立即丢弃已缓冲数据（seek 纠偏时用）。 */
	public synchronized void clear() {
		readPos = 0;
		writePos = 0;
		available = 0;
		notifyAll();
	}

	/** 解码线程读到文件尾时调用：等缓冲被取空后，读端收到 0。 */
	public synchronized void finish() {
		finished = true;
		notifyAll();
	}

	public synchronized void abort() {
		finished = true;
		clear();
		notifyAll();
	}
}
