package dev.clouddisc.audio;

import net.minecraft.block.BlockState;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块声学材质表 —— <b>本项目自己推导</b>的（没有抄 SPR 的方块配置表，那份是 GPL）。
 *
 * <p>每个方块给一个三元组：
 * <ul>
 *   <li>{@code occlusion}：挡住声音的强度（累加到"沿连线遮挡值"，再算 {@code exp(-acc*3)}）</li>
 *   <li>{@code reflectivity}：反射率（混响射线每次命中按它累积能量）</li>
 *   <li>{@code absorption}：吸声系数（留给后面做频谱倾斜/干燥度，现在只记录）</li>
 * </ul>
 *
 * <h2>怎么推的</h2>
 * <ol>
 *   <li><b>基础值取"方块声音组"</b>（{@link BlockState#getSoundGroup()}，即 {@link BlockSoundGroup}
 *       的那些单例：石/木/羊毛/玻璃/金属…）。理由是：声音组本来就是"这个方块听起来像什么材质"
 *       的官方分类，用它当材质类别最省事、也最不容易跑偏。</li>
 *   <li><b>再按几何打折</b>：{@link BlockState#isOpaqueFullCube} 为假（楼梯/栅栏/玻璃板…）→
 *       遮挡 × {@link #NON_FULL_BLOCK_OCCLUSION}。一条缝就该让遮挡降下来。</li>
 *   <li><b>液体</b>：{@code getFluidState()} 非空 → 遮挡大幅下调（水几乎不挡声，但很吸声）。</li>
 *   <li><b>硬度微调</b>：硬度 &lt; 0（基岩/屏障这类"不可破坏"）算满遮挡；
 *       硬度 == 0（火把/作物/花这类一碰就碎）→ 遮挡 ×0.35。</li>
 * </ol>
 *
 * <p><b>缓存</b>：{@link IdentityHashMap}（{@code BlockState} 是单例，恒等比较最快）。
 * 只在"第一次见到这个状态"时算一次，之后就是一次哈希查找。
 */
public final class BlockAcoustics {
	/** 不是完整方块时的遮挡折扣（与 SPR 的 nonFullBlockOcclusionFactor 同一个量级，数值自己定）。 */
	public static final float NON_FULL_BLOCK_OCCLUSION = 0.5f;
	/** 兜底材质（认不出声音组时）。 */
	private static final float[] DEFAULT = {0.90f, 0.40f, 0.60f};

	/** (occlusion, reflectivity, absorption) */
	public record Params(float occlusion, float reflectivity, float absorption) {
	}

	private static final Map<BlockState, Params> CACHE = new IdentityHashMap<>();

	private BlockAcoustics() {
	}

	/** 某个方块状态的材质三元组（含缓存）。 */
	public static Params of(BlockState state) {
		Params p = CACHE.get(state);
		if (p != null) {
			return p;
		}
		p = derive(state);
		if (CACHE.size() > 8192) {
			CACHE.clear(); // 保护：装了别的 mod 生成大量状态时不要无限涨
		}
		CACHE.put(state, p);
		return p;
	}

	/**
	 * 实际用于"沿连线"累加的遮挡值：材质基础值 + 几何/液体/硬度修正。
	 * <p>需要 {@code world/pos} 只是为了 {@link BlockState#isOpaqueFullCube}（它可能跟位置有关）。
	 */
	public static float occlusionOf(BlockState state, BlockView world, BlockPos pos) {
		Params p = of(state);
		float occ = p.occlusion();
		try {
			if (!state.isOpaqueFullCube(world, pos)) {
				occ *= NON_FULL_BLOCK_OCCLUSION;
			}
		} catch (Throwable ignored) {
			// 位置相关的判定失败就按"完整方块"算
		}
		return occ;
	}

	/** 反射率（混响射线用）。 */
	public static float reflectivityOf(BlockState state) {
		return of(state).reflectivity();
	}

	/** 吸声系数（频谱倾斜/干燥度用）。 */
	public static float absorptionOf(BlockState state) {
		return of(state).absorption();
	}

	private static Params derive(BlockState state) {
		BlockSoundGroup g = null;
		boolean opaque = true;
		boolean liquid = false;
		float hardness = 1.0f;
		try {
			g = state.getSoundGroup();
			opaque = state.isOpaque();
			liquid = !state.getFluidState().isEmpty();
			hardness = state.getBlock().getHardness();
		} catch (Throwable ignored) {
			// 任何取值失败都走兜底
		}

		float[] base = baseFor(g);
		float occ = base[0];
		float refl = base[1];
		float abs = base[2];

		// "听起来像草木、其实是实心方块"的例外：草方块 / 湿草 / 苔藓块用的是草木那组声音，
		// 但它们本身是完整实心方块，挡声应当按"地面"算，不能按灌木算。
		// 判据很好找：树叶不是 opaque，草方块是。
		boolean plantLike = g == BlockSoundGroup.GRASS || g == BlockSoundGroup.WET_GRASS
				|| g == BlockSoundGroup.MOSS_BLOCK;
		if (plantLike && opaque) {
			occ = Math.max(occ, 0.85f);
			refl = 0.25f;
			abs = Math.max(abs, 0.65f);
		}

		// 液体：水几乎不挡声（声音能穿过），但能量会被吸收
		if (liquid) {
			occ *= 0.15f;
			refl = Math.min(refl, 0.08f);
			abs = Math.max(abs, 0.85f);
		} else {
			// 非完整方块（楼梯/栅栏/草/花/铁栏杆…）：缝隙多，遮挡打折
			if (!opaque) {
				occ *= 0.8f;
			}
			if (hardness < 0.0f) {
				// 基岩 / 屏障 / 命令方块这类"打不动的"：实心且不吸声
				occ = Math.max(occ, 1.0f);
				refl = Math.max(refl, 0.5f);
				abs = Math.min(abs, 0.35f);
			} else if (hardness == 0.0f) {
				// 火把 / 作物 / 花 / 火：几乎没有实体
				occ *= 0.35f;
				refl *= 0.7f;
				abs = Math.max(abs, 0.7f);
			}
		}
		return new Params(clamp(occ, 0.0f, 2.0f), clamp(refl, 0.0f, 1.0f), clamp(abs, 0.0f, 1.0f));
	}

	/**
	 * 基础表：<b>按方块声音组的恒等比较</b>（{@link BlockSoundGroup} 的单例没有 equals 覆写，
	 * 用 {@code ==} 就是"同一个材质类别"）。
	 */
	private static float[] baseFor(BlockSoundGroup g) {
		if (g == null || g == BlockSoundGroup.INTENTIONALLY_EMPTY) {
			return DEFAULT;
		}
		// ---- 金属 / 矿石类：硬、极反射、几乎不吸声 ----
		if (g == BlockSoundGroup.METAL || g == BlockSoundGroup.ANVIL || g == BlockSoundGroup.NETHERITE
				|| g == BlockSoundGroup.LODESTONE || g == BlockSoundGroup.CHAIN || g == BlockSoundGroup.COPPER
				|| g == BlockSoundGroup.NETHER_ORE || g == BlockSoundGroup.NETHER_GOLD_ORE
				|| g == BlockSoundGroup.ANCIENT_DEBRIS || g == BlockSoundGroup.GILDED_BLACKSTONE
				|| g == BlockSoundGroup.BONE || g == BlockSoundGroup.LANTERN) {
			return new float[] {1.00f, 0.85f, 0.12f};
		}
		// ---- 玻璃 / 水晶：透声但很亮（高频反射强） ----
		if (g == BlockSoundGroup.GLASS || g == BlockSoundGroup.AMETHYST_BLOCK
				|| g == BlockSoundGroup.AMETHYST_CLUSTER || g == BlockSoundGroup.SMALL_AMETHYST_BUD
				|| g == BlockSoundGroup.MEDIUM_AMETHYST_BUD || g == BlockSoundGroup.LARGE_AMETHYST_BUD
				|| g == BlockSoundGroup.FROGLIGHT || g == BlockSoundGroup.SHROOMLIGHT) {
			return new float[] {0.50f, 0.90f, 0.10f};
		}
		// ---- 羊毛 / 苔藓 / 雪：强吸声，几乎不反射 ----
		if (g == BlockSoundGroup.WOOL || g == BlockSoundGroup.MOSS_CARPET || g == BlockSoundGroup.MOSS_BLOCK
				|| g == BlockSoundGroup.SNOW || g == BlockSoundGroup.POWDER_SNOW
				|| g == BlockSoundGroup.AZALEA_LEAVES || g == BlockSoundGroup.CHERRY_LEAVES
				|| g == BlockSoundGroup.SCULK_VEIN || g == BlockSoundGroup.PINK_PETALS) {
			return new float[] {0.45f, 0.12f, 0.90f};
		}
		// ---- 树叶 / 藤蔓 / 作物：稀疏、挡一点、反射中等偏低 ----
		if (g == BlockSoundGroup.GRASS || g == BlockSoundGroup.WET_GRASS || g == BlockSoundGroup.CROP
				|| g == BlockSoundGroup.STEM || g == BlockSoundGroup.VINE || g == BlockSoundGroup.WEEPING_VINES
				|| g == BlockSoundGroup.WEEPING_VINES_LOW_PITCH || g == BlockSoundGroup.CAVE_VINES
				|| g == BlockSoundGroup.NETHER_WART || g == BlockSoundGroup.NETHER_SPROUTS
				|| g == BlockSoundGroup.NETHER_STEM || g == BlockSoundGroup.FUNGUS || g == BlockSoundGroup.ROOTS
				|| g == BlockSoundGroup.HANGING_ROOTS || g == BlockSoundGroup.GLOW_LICHEN
				|| g == BlockSoundGroup.LILY_PAD || g == BlockSoundGroup.SPORE_BLOSSOM
				|| g == BlockSoundGroup.BIG_DRIPLEAF || g == BlockSoundGroup.SMALL_DRIPLEAF
				|| g == BlockSoundGroup.AZALEA || g == BlockSoundGroup.FLOWERING_AZALEA
				|| g == BlockSoundGroup.CHERRY_SAPLING || g == BlockSoundGroup.BAMBOO_SAPLING
				|| g == BlockSoundGroup.SWEET_BERRY_BUSH || g == BlockSoundGroup.CORAL
				|| g == BlockSoundGroup.HANGING_SIGN || g == BlockSoundGroup.NETHER_WOOD_HANGING_SIGN
				|| g == BlockSoundGroup.BAMBOO_WOOD_HANGING_SIGN || g == BlockSoundGroup.CHERRY_WOOD_HANGING_SIGN
				|| g == BlockSoundGroup.SCAFFOLDING || g == BlockSoundGroup.LADDER) {
			return new float[] {0.35f, 0.28f, 0.65f};
		}
		// ---- 沙 / 土 / 砾 / 泥 / 菌岩：松散地面，吸声较强 ----
		if (g == BlockSoundGroup.SAND || g == BlockSoundGroup.GRAVEL || g == BlockSoundGroup.SOUL_SAND
				|| g == BlockSoundGroup.SOUL_SOIL || g == BlockSoundGroup.NYLIUM || g == BlockSoundGroup.MUD
				|| g == BlockSoundGroup.PACKED_MUD || g == BlockSoundGroup.MUD_BRICKS
				|| g == BlockSoundGroup.ROOTED_DIRT || g == BlockSoundGroup.MUDDY_MANGROVE_ROOTS
				|| g == BlockSoundGroup.MANGROVE_ROOTS || g == BlockSoundGroup.SUSPICIOUS_SAND
				|| g == BlockSoundGroup.SUSPICIOUS_GRAVEL || g == BlockSoundGroup.WART_BLOCK
				|| g == BlockSoundGroup.FROGSPAWN) {
			return new float[] {0.75f, 0.22f, 0.72f};
		}
		// ---- 木头 / 竹子 / 书架：中等遮挡、中等反射（木板墙的听感应与石墙明显不同） ----
		if (g == BlockSoundGroup.WOOD || g == BlockSoundGroup.BAMBOO || g == BlockSoundGroup.BAMBOO_WOOD
				|| g == BlockSoundGroup.NETHER_WOOD || g == BlockSoundGroup.CHERRY_WOOD
				|| g == BlockSoundGroup.CHISELED_BOOKSHELF || g == BlockSoundGroup.DECORATED_POT
				|| g == BlockSoundGroup.DECORATED_POT_SHATTER || g == BlockSoundGroup.CANDLE) {
			return new float[] {0.82f, 0.45f, 0.55f};
		}
		// ---- 黏液 / 蜂蜜：软、吸声 ----
		if (g == BlockSoundGroup.SLIME || g == BlockSoundGroup.HONEY) {
			return new float[] {0.60f, 0.20f, 0.75f};
		}
		// ---- 深板岩系列：比普通石更密实一点 ----
		if (g == BlockSoundGroup.DEEPSLATE || g == BlockSoundGroup.DEEPSLATE_BRICKS
				|| g == BlockSoundGroup.DEEPSLATE_TILES || g == BlockSoundGroup.POLISHED_DEEPSLATE
				|| g == BlockSoundGroup.BASALT || g == BlockSoundGroup.NETHER_BRICKS
				|| g == BlockSoundGroup.TUFF || g == BlockSoundGroup.CALCITE) {
			return new float[] {1.00f, 0.55f, 0.40f};
		}
		// ---- 幽匿系列：软而吸声 ----
		if (g == BlockSoundGroup.SCULK || g == BlockSoundGroup.SCULK_CATALYST
				|| g == BlockSoundGroup.SCULK_SENSOR || g == BlockSoundGroup.SCULK_SHRIEKER) {
			return new float[] {0.65f, 0.25f, 0.70f};
		}
		// ---- 石头（默认大类）：硬、反射中等、吸声弱 ----
		if (g == BlockSoundGroup.STONE || g == BlockSoundGroup.NETHERRACK
				|| g == BlockSoundGroup.GILDED_BLACKSTONE || g == BlockSoundGroup.POINTED_DRIPSTONE
				|| g == BlockSoundGroup.DRIPSTONE_BLOCK) {
			return new float[] {1.00f, 0.52f, 0.45f};
		}
		return DEFAULT;
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// ------------------------------------------------------------ 诊断：材质表抽样

	/** 诊断用：把几张常见方块的实际参数打出来，方便"数值 ↔ 听感"对齐。 */
	public static List<String> sampleTable(BlockView world) {
		List<String> out = new ArrayList<>();
		String[] names = {"stone", "oak_planks", "glass", "white_wool", "dirt", "oak_leaves",
				"iron_block", "water", "cobblestone", "oak_log", "sand", "bedrock", "deepslate", "moss_block"};
		for (String n : names) {
			try {
				net.minecraft.block.Block b = net.minecraft.registry.Registries.BLOCK.get(new net.minecraft.util.Identifier(n));
				if (b == null) {
					continue;
				}
				BlockState st = b.getDefaultState();
				Params p = of(st);
				out.add(n + "=(" + fmt(p.occlusion()) + "," + fmt(p.reflectivity()) + "," + fmt(p.absorption()) + ")");
			} catch (Throwable ignored) {
				// 名字不存在就算了
			}
		}
		return out;
	}

	private static String fmt(float v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}
