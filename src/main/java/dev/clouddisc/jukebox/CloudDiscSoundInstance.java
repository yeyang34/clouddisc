package dev.clouddisc.jukebox;

import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * 把一次 CloudDisc 播放伪装成一条普通声音实例。
 *
 * <p>实现 {@link SoundInstance}（1.20.1 里<b>没有</b> {@code resolve} / {@code getStream}；
 * 名字分别是 {@code getSoundSet(SoundManager)} 与"由 SoundLoader 取流"），
 * 于是 MC 会把它当普通流式声音处理：
 * <ul>
 *   <li>位置 = 唱片机坐标 → 有距离衰减，单声道 PCM 能被 OpenAL 正确空间化</li>
 *   <li>类别 = {@link SoundCategory#RECORDS} → 跟随"唱片机/音符盒"音量滑块</li>
 *   <li>由原版 {@code SoundManager} 管理 → 暂停、失焦静音、{@code stop(instance)} 全部照常</li>
 * </ul>
 */
public final class CloudDiscSoundInstance implements SoundInstance {
	/**
	 * 与原版唱片对齐：vanilla 用 {@code PositionedSoundInstance.record(...)}，
	 * 即 {@code SoundCategory.RECORDS} + volume 4.0f + pitch 1.0f + LINEAR 衰减。
	 */
	private static final int ATTENUATION_DISTANCE = 16;

	private final Identifier id;
	private final Sound sound;
	private final double x;
	private final double y;
	private final double z;
	private final float volume;
	private final Random random = Random.create();

	public CloudDiscSoundInstance(Identifier streamId, BlockPos pos, float volume) {
		this.id = new Identifier("clouddisc", "session/" + streamId.getPath().substring("stream/".length()));
		// 自定义 Sound：FILE + stream=true —— 这就是"流式播放"的开关
		this.sound = new Sound(streamId.toString(), r -> 1.0F, r -> 1.0F, 1,
				Sound.RegistrationType.FILE, true, false, ATTENUATION_DISTANCE);
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
		this.volume = volume;
	}

	@Override
	public Identifier getId() {
		return id;
	}

	@Override
	public WeightedSoundSet getSoundSet(SoundManager soundManager) {
		// 绕过 sounds.json：直接把我们的 Sound 打包成 Set 交回去
		WeightedSoundSet set = new WeightedSoundSet(getId(), null);
		set.add(this.sound);
		return set;
	}

	@Override
	public Sound getSound() {
		return sound;
	}

	@Override
	public SoundCategory getCategory() {
		return SoundCategory.RECORDS;
	}

	@Override
	public boolean isRepeatable() {
		return false;
	}

	@Override
	public boolean isRelative() {
		return false;
	}

	@Override
	public int getRepeatDelay() {
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
	public SoundInstance.AttenuationType getAttenuationType() {
		return SoundInstance.AttenuationType.LINEAR;
	}

	@Override
	public boolean canPlay() {
		return true;
	}
}
