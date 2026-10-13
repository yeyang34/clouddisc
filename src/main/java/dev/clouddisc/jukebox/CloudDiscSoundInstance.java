package dev.clouddisc.jukebox;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.sounds.SoundEventRegistration;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * 把一次 CloudDisc 播放伪装成一条普通声音实例。
 *
 * <p>实现 {@link SoundInstance}（1.20.1 里<b>没有</b> {@code resolve} / {@code getStream}；
 * 名字分别是 {@code resolve(SoundManager)} 与"由 SoundLoader 取流"），
 * 于是 MC 会把它当普通流式声音处理：
 * <ul>
 *   <li>位置 = 唱片机坐标 → 有距离衰减，单声道 PCM 能被 OpenAL 正确空间化</li>
 *   <li>类别 = {@link SoundSource#RECORDS} → 跟随"唱片机/音符盒"音量滑块</li>
 *   <li>由原版 {@code SoundManager} 管理 → 暂停、失焦静音、{@code stop(instance)} 全部照常</li>
 * </ul>
 */
public final class CloudDiscSoundInstance implements SoundInstance {
	/**
	 * 与原版唱片对齐：vanilla 用 {@code PositionedSoundInstance.record(...)}，
	 * 即 {@code SoundSource.RECORDS} + volume 4.0f + pitch 1.0f + LINEAR 衰减。
	 */
	// 【0.12.31】衰减距离 16 → 48 格。
	// 16 格时远处直通早就衰减到听不见，只剩混响在响，于是离远听得见但没方位、很假。
	// 48 格时远处仍听得见，而且走的是引擎的距离衰减（带空间定位），方位感正常。
	private static final int ATTENUATION_DISTANCE = 48;

	private final Identifier id;
	private final Sound sound;
	private final double x;
	private final double y;
	private final double z;
	private final float volume;
	private final RandomSource random = RandomSource.create();

	public CloudDiscSoundInstance(Identifier streamId, BlockPos pos, float volume) {
		this.id = Identifier.fromNamespaceAndPath("clouddisc", "session/" + streamId.getPath().substring("stream/".length()));
		// 自定义 Sound：FILE + stream=true —— 这就是"流式播放"的开关
		this.sound = new Sound(streamId, r -> 1.0F, r -> 1.0F, 1,
				net.minecraft.client.resources.sounds.Sound.Type.FILE, true, false, ATTENUATION_DISTANCE);
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
		this.volume = volume;
	}

	@Override
	public Identifier getIdentifier() {
		return id;
	}

	@Override
	public net.minecraft.client.sounds.WeighedSoundEvents resolve(SoundManager soundManager) {
		// 绕过 sounds.json：直接把我们的 Sound 打包成 Set 交回去
				// 绕过 sounds.json：自己装一份只含本条 Sound 的注册表（26.2 里 resolve 返回 WeighedSoundEvents）
		net.minecraft.client.sounds.WeighedSoundEvents set =
				new net.minecraft.client.sounds.WeighedSoundEvents(id, null);
		set.addSound(this.sound);
		return set;
	}

	@Override
	public Sound getSound() {
		return sound;
	}

	@Override
	public SoundSource getSource() {
		return SoundSource.RECORDS;
	}
	@Override
	public int getDelay() {
		return 0;
	}


	@Override
	public boolean isLooping() {
		// 【回退】不要用原版循环机制 ✗（isLooping=true 会让引擎在流结束时再要一条新流 ✗
		// 而我们的流是一次性的 ✗ → 拿不到就整条声音都没了 ✗ 实测把播放搞坏了 ✓）
		return false;
	}

	@Override
	public boolean isRelative() {
		return false;
	}

	public int clouddisc$repeatDelay() {
		return 0;
	}

	@Override
	public float getVolume() {
		return volume;
	}

	@Override
	public float getPitch() {
		return 1.0F;
	}

	@Override
	public double getX() {
		return x;
	}

	@Override
	public double getY() {
		return y;
	}

	@Override
	public double getZ() {
		return z;
	}

	@Override
	public SoundInstance.Attenuation getAttenuation() {
		return SoundInstance.Attenuation.LINEAR;
	}

	@Override
	public boolean canPlaySound() {
		return true;
	}
}
