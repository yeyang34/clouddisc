package dev.clouddisc.audio;

import net.minecraft.block.BlockState;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
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
 *   <li>{@code occlusion}：挡住声音的强度（累加到"沿连线遮挡值"，再算 {@code exp(-acc*k)}）</li>
 *   <li>{@code reflectivity}：反射率（混响射线每次命中按它累积能量）</li>
 *   <li>{@code absorption}：吸声系数（留给后面做频谱倾斜/干燥度，现在只记录）</li>
 * </ul>
 *
 * <h2>怎么推的</h2>
 * <ol>
 *   <li><b>基础值取"方块声音组"</b>（{@link BlockState#getSoundGroup()}，即 {@link BlockSoundGroup}
 *       的那些单例：石/木/羊毛/玻璃/金属…）。理由是：声音组本来就是"这个方块听起来像什么材质"
 *       的官方分类，用它当材质类别最省事、也最不容易跑偏。</li>
 *   <li><b>几何不再"打折"，改成精确判定</b>（0.12.7）：老版本用 {@code isOpaqueFullCube} 为假就
 *       ×{@link #NON_FULL_BLOCK_OCCLUSION} 来近似"楼梯/栅栏/玻璃板有缝"，但关着的门也踩这条，
 *       结果门的遮挡被砍掉一半。现在由 {@link #blocksRay}（射线 vs 碰撞形状求交）回答
 *       "这条线到底有没有被挡住"：真挡住了就按材质值算，没挡住就一分不算。</li>
 *   <li><b>液体</b>：{@code getFluidState()} 非空 → 遮挡大幅下调（水几乎不挡声，但很吸声）。</li>
 *   <li><b>硬度微调</b>：硬度 &lt; 0（基岩/屏障这类"不可破坏"）算满遮挡；
 *       硬度 == 0（火把/作物/花这类一碰就碎）→ 遮挡 ×0.35。</li>
 * </ol>
 *
 * <p><b>缓存</b>：{@link IdentityHashMap}（{@code BlockState} 是单例，恒等比较最快）。
 * 只在"第一次见到这个状态"时算一次，之后就是一次哈希查找。
 *
 * <h2>0.12.6 的两件事</h2>
 * <ol>
 *   <li><b>每个类别带上"来源"标签</b>（{@link Params#source()}）：这样
 *       {@link #probeLine} 能打出一行"这个方块到底命中了哪一组、是不是掉进了兜底默认值"。
 *       —— 这是判断"材质表有没有退化成默认值"的唯一依据。</li>
 *   <li><b>拉开三档对比</b>（用户最直接的验收点）：玻璃 <b>0.20</b>（只轻微闷）、
 *       木板 <b>0.55</b>（中间）、石头 <b>1.00</b>（最闷）。0.12.5 时玻璃 0.20 / 木板 0.82 /
 *       石头 1.00，而当时 {@code exp(-occ*3)} 又会让木板和石头同时饱和到"几乎一样闷"，
 *       三档听不出区别（详见 docs/物理声效实现计划.md 的"调参记录"）。</li>
 * </ol>
 *
 * <h2>0.12.7 的几何判定（修"门关着和开着一样"）</h2>
 * <p>0.12.6 之前，遮挡值里有一条 {@code isOpaqueFullCube()} 为假就"×0.5"的<b>几何打折</b>。
 * 关着的木门正好两条都踩：它不是 opaque（{@code ×0.8}），也不是"不透明完整方块"（{@code ×0.5}）
 * —— 木门基础 0.55 → 实际只累加 <b>0.22</b>，截止 {@code exp(-0.22×4.5) = 0.37}，只是"稍微暗一点"。
 * <p>0.12.7 起改为<b>几何问题交给射线精确判定</b>：只有射线<b>真的与这一格的碰撞形状求交命中</b>
 * （{@link #blocksRay}）才累加，累加值就是<b>材质值本身</b>（不再做 {@code ×0.5}）。
 * 于是：关着的门（射线真的穿过门板）= 0.44，门开着（碰撞形状为空）= 0；
 * 楼梯/栅栏的缝（射线从空隙里过）= 0，不再被当成"实心格子"白算一笔。
 */
public final class BlockAcoustics {
	/**
	 * 0.12.7 起<b>不再参与遮挡累加</b>：几何由 {@link #blocksRay} 精确判定
	 * （射线真的命中碰撞形状才算挡），这里只保留一个数值给探针日志做"这个形状不满一格"的提示。
	 */
	public static final float NON_FULL_BLOCK_OCCLUSION = 0.5f;
	/**
	 * 兜底材质（认不出声音组时）。
	 * <p>0.12.6 起把反射率 0.40 → <b>0.55</b>（混响更明显），并给了一个可辨认的
	 * {@link #SOURCE_DEFAULT 来源标签} —— 材质探针一旦打出这个标签，就说明"这张方块根本没进材质表"。
	 */
	private static final float[] DEFAULT = {0.90f, 0.55f, 0.50f};
	/** 兜底来源标签（材质探针里看到它就说明没匹配上任何声音组）。 */
	public static final String SOURCE_DEFAULT = "默认值（未识别的声音组）";

	/** (occlusion, reflectivity, absorption, source)。{@code source} 只用于诊断日志，不参与听感计算。 */
	public record Params(float occlusion, float reflectivity, float absorption, String source) {
	}

	/** 材质基础档：来源标签 + 三元组（只在本文件内部用）。 */
	private record Base(String label, float occlusion, float reflectivity, float absorption) {
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
	 * 实际用于"沿连线"累加的遮挡值 = 材质基础值（已含声音组 / 液体 / 硬度 / 非不透明这些<b>材质级</b>修正）。
	 *
	 * <p><b>0.12.7 改了这里</b>：不再做"非完整方块 ×{@value #NON_FULL_BLOCK_OCCLUSION}"的<b>几何</b>打折。
	 * 原因（就是"门关着和开着一样闷"的一半根因）：关着的木门既不是 opaque（派生时 ×0.8）、
	 * 又不是"不透明完整方块"（这里再 ×0.5），0.55 → 只剩 0.22，截止 0.37 —— 只是"稍微暗一点"。
	 * 现在几何交给 {@link #blocksRay}：<b>射线真的穿不过这一格的碰撞形状才累加</b>，
	 * 累加的就是材质值本身（关着的门 0.44、门开着 0、楼梯的缝 0）。
	 */
	public static float occlusionOf(BlockState state) {
		return of(state).occlusion();
	}

	/**
	 * <b>0.12.7 新增 / 0.12.8 加固</b>：这一格确实在射线上（DDA 已确认），射线是否<b>真的命中它的碰撞形状</b>。
	 *
	 * <p>为什么需要它：DDA 只告诉我们"射线穿过了哪一格"，而"格子里有碰撞体积"不等于
	 * "射线被挡住了" —— 楼梯、栅栏、玻璃板、活板门都有碰撞体积，但射线完全可能从缝隙里过。
	 * 老实现把这种格子整格当成实心累加，再靠 {@code isOpaqueFullCube} 打个对折来"补偿"，
	 * 结果就是"关着的门被判成一条缝"。
	 *
	 * <p><b>0.12.8 的加固</b>：真正的几何判定在 {@link RayShape#hits}——<b>满格体素（石头/玻璃/关着的门）
	 * 直接判挡，根本不依赖求交实现</b>；只有不满格/多盒的形状（楼梯、栅栏、半砖…）才逐盒求交。
	 * 这样"一格厚墙/一扇关着的门"不可能因为某个引擎分支不成立而静默变成"不挡"。
	 */
	public static boolean blocksRay(BlockState state, BlockView world, BlockPos pos, Vec3d from, Vec3d to) {
		VoxelShape shape;
		try {
			shape = state.getCollisionShape(world, pos);
		} catch (Throwable t) {
			return true; // 取不到形状：保守地按"挡"处理（宁可闷一点，也不要漏挡）
		}
		return RayShape.hits(shape, pos, from, to);
	}

	/**
	 * <b>0.12.8 新增</b>：这一格对这条射线的遮挡贡献，<b>生产路径与离线自检共用同一个函数</b>
	 * （见 {@code tools/OcclusionWalkTest.java}）。
	 *
	 * @return {@code > 0} = 挡住（累加这个材质值）；{@code 0} = 空气/空碰撞形状；
	 *         {@code -1} = 有碰撞形状但射线没被打到（楼梯的缝…）—— 不累加，只计入"实心格"统计
	 */
	public static double occlusionOnRay(BlockState state, BlockView world, BlockPos pos, Vec3d from, Vec3d to) {
		if (state == null || state.isAir()) {
			return 0.0;
		}
		boolean fluid = false;
		try {
			fluid = !state.getFluidState().isEmpty();
		} catch (Throwable ignored) {
			// 取不到就当不是流体
		}
		if (!fluid) {
			// 几何判定：射线真的被这一格的碰撞形状挡住才算
			if (!blocksRay(state, world, pos, from, to)) {
				// 安全网（0.12.7 的教训）：万一碰撞形状取不到/被别的东西弄成空，
				// 而方块本身又是"不透明完整方块"（石头/木板/原木…），仍然按"挡"算。
				// 正常情况永远走不到这里（实心方块一定有满格碰撞形状）；
				// 这一条只是为了让"整条遮挡链路静默归零"不可能再发生。
				boolean opaqueFullCube = false;
				try {
					opaqueFullCube = state.isOpaqueFullCube(world, pos);
				} catch (Throwable ignored) {
					opaqueFullCube = false;
				}
				if (!opaqueFullCube) {
					return -1.0;
				}
			}
		}
		float occ = occlusionOf(state);
		return occ > 0.0f ? occ : -1.0;
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

		Base base = baseFor(g);
		float occ = base.occlusion();
		float refl = base.reflectivity();
		float abs = base.absorption();
		String source = base.label();

		// "听起来像草木、其实是实心方块"的例外：草方块 / 湿草 / 苔藓块用的是草木那组声音，
		// 但它们本身是完整实心方块，挡声应当按"地面"算，不能按灌木算。
		// 判据很好找：树叶不是 opaque，草方块是。
		boolean plantLike = g == BlockSoundGroup.GRASS || g == BlockSoundGroup.WET_GRASS
				|| g == BlockSoundGroup.MOSS_BLOCK;
		if (plantLike && opaque) {
			occ = Math.max(occ, 0.85f);
			refl = 0.25f;
			abs = Math.max(abs, 0.65f);
			source = source + "｜实心草本特例(遮挡按地面≥0.85)";
		}

		// 液体：水几乎不挡声（声音能穿过），但能量会被吸收
		if (liquid) {
			occ *= 0.15f;
			refl = Math.min(refl, 0.08f);
			abs = Math.max(abs, 0.85f);
			source = source + "｜液体(遮挡×0.15)";
		} else {
			// 不透明方块（玻璃/门/活板门/栅栏/铁栏杆…）：材质本身就"透一点声"，遮挡按材质打折。
			// 注意这**不是**几何判定 —— 几何由 blocksRay 精确回答"这条线有没有被挡住"，
			// 所以关着的门（不透明=false）走这里 ×0.8 → 0.44，仍然远高于"一条缝"。
			if (!opaque) {
				occ *= 0.8f;
				source = source + "｜非不透明方块(遮挡×0.8)";
			}
			if (hardness < 0.0f) {
				// 基岩 / 屏障 / 命令方块这类"打不动的"：实心且不吸声
				occ = Math.max(occ, 1.0f);
				refl = Math.max(refl, 0.5f);
				abs = Math.min(abs, 0.35f);
				source = source + "｜不可破坏(遮挡≥1.0)";
			} else if (hardness == 0.0f) {
				// 火把 / 作物 / 花 / 火：几乎没有实体
				occ *= 0.35f;
				refl *= 0.7f;
				abs = Math.max(abs, 0.7f);
				source = source + "｜硬度0(遮挡×0.35)";
			}
		}
		return new Params(clamp(occ, 0.0f, 2.0f), clamp(refl, 0.0f, 1.0f), clamp(abs, 0.0f, 1.0f), source);
	}

	/**
	 * 基础表：<b>按方块声音组的恒等比较</b>（{@link BlockSoundGroup} 的单例没有 equals 覆写，
	 * 用 {@code ==} 就是"同一个材质类别"）。
	 *
	 * <p><b>0.12.6 的遮挡值调整（拉开对比，配合 k=4.5）</b>：目标是"玻璃只轻微闷、木板中间、石头最闷"。
	 * 旧值括号里是 0.12.5 的数：
	 * <pre>
	 *   玻璃 0.50 → 0.25（0.12.7 改：删掉"非完整方块×0.5"后，乘 ×0.8 仍实际 0.20，截止 ≈ 0.41 不变）
	 *   木板 0.82 → 0.55（截止 ≈ 0.08）
	 *   石头 1.00（不变，截止 ≈ 0.01）
	 *   沙土 0.75 → 0.70    深板岩/石头保持 1.00    黏液 0.60 → 0.50
	 *   幽匿 0.65 → 0.55    树叶 0.35 → 0.30       羊毛 0.45（不变）
	 * </pre>
	 * 旧值下木板(0.82)与石头(1.00)在 {@code exp(-occ*3)} 里都掉到 0.05 以下，听感几乎一样 ——
	 * 这是"三档分不出来"的直接原因。
	 */
	private static Base baseFor(BlockSoundGroup g) {
		if (g == null || g == BlockSoundGroup.INTENTIONALLY_EMPTY) {
			return new Base(SOURCE_DEFAULT, DEFAULT[0], DEFAULT[1], DEFAULT[2]);
		}
		// ---- 金属 / 矿石类：硬、极反射、几乎不吸声 ----
		if (g == BlockSoundGroup.METAL || g == BlockSoundGroup.ANVIL || g == BlockSoundGroup.NETHERITE
				|| g == BlockSoundGroup.LODESTONE || g == BlockSoundGroup.CHAIN || g == BlockSoundGroup.COPPER
				|| g == BlockSoundGroup.NETHER_ORE || g == BlockSoundGroup.NETHER_GOLD_ORE
				|| g == BlockSoundGroup.ANCIENT_DEBRIS || g == BlockSoundGroup.GILDED_BLACKSTONE
				|| g == BlockSoundGroup.BONE || g == BlockSoundGroup.LANTERN) {
			return new Base("声音组 METAL(金属/矿石)", 1.00f, 0.85f, 0.12f);
		}
		// ---- 玻璃 / 水晶：透声但很亮（高频反射强）----
		// 0.12.7：基础值 0.50 → 0.25。因为"非完整方块 ×0.5"这条几何打折已经删掉
		// （几何改由 blocksRay 精确判定），乘上派生的"非不透明 ×0.8"后仍是 **0.20** ——
		// 与 0.12.6 实际下发值完全一致，玻璃那一档的听感不变。
		if (g == BlockSoundGroup.GLASS || g == BlockSoundGroup.AMETHYST_BLOCK
				|| g == BlockSoundGroup.AMETHYST_CLUSTER || g == BlockSoundGroup.SMALL_AMETHYST_BUD
				|| g == BlockSoundGroup.MEDIUM_AMETHYST_BUD || g == BlockSoundGroup.LARGE_AMETHYST_BUD
				|| g == BlockSoundGroup.FROGLIGHT || g == BlockSoundGroup.SHROOMLIGHT) {
			return new Base("声音组 GLASS(玻璃/水晶)", 0.25f, 0.90f, 0.10f);
		}
		// ---- 羊毛 / 苔藓 / 雪：强吸声，几乎不反射 ----
		if (g == BlockSoundGroup.WOOL || g == BlockSoundGroup.MOSS_CARPET || g == BlockSoundGroup.MOSS_BLOCK
				|| g == BlockSoundGroup.SNOW || g == BlockSoundGroup.POWDER_SNOW
				|| g == BlockSoundGroup.AZALEA_LEAVES || g == BlockSoundGroup.CHERRY_LEAVES
				|| g == BlockSoundGroup.SCULK_VEIN || g == BlockSoundGroup.PINK_PETALS) {
			return new Base("声音组 WOOL(羊毛/雪/苔藓)", 0.45f, 0.12f, 0.90f);
		}
		// ---- 树叶 / 藤蔓 / 作物：稀疏、挡一点、反射中等偏低（0.35 → 0.30）----
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
			return new Base("声音组 GRASS(草木/树叶)", 0.30f, 0.28f, 0.65f);
		}
		// ---- 沙 / 土 / 砾 / 泥 / 菌岩：松散地面，吸声较强（0.75 → 0.70）----
		if (g == BlockSoundGroup.SAND || g == BlockSoundGroup.GRAVEL || g == BlockSoundGroup.SOUL_SAND
				|| g == BlockSoundGroup.SOUL_SOIL || g == BlockSoundGroup.NYLIUM || g == BlockSoundGroup.MUD
				|| g == BlockSoundGroup.PACKED_MUD || g == BlockSoundGroup.MUD_BRICKS
				|| g == BlockSoundGroup.ROOTED_DIRT || g == BlockSoundGroup.MUDDY_MANGROVE_ROOTS
				|| g == BlockSoundGroup.MANGROVE_ROOTS || g == BlockSoundGroup.SUSPICIOUS_SAND
				|| g == BlockSoundGroup.SUSPICIOUS_GRAVEL || g == BlockSoundGroup.WART_BLOCK
				|| g == BlockSoundGroup.FROGSPAWN) {
			return new Base("声音组 SAND(沙/土/砾)", 0.70f, 0.22f, 0.72f);
		}
		// ---- 木头 / 竹子 / 书架：中等遮挡、中等反射（0.82 → 0.55，有意调到"玻璃与石头之间"）----
		if (g == BlockSoundGroup.WOOD || g == BlockSoundGroup.BAMBOO || g == BlockSoundGroup.BAMBOO_WOOD
				|| g == BlockSoundGroup.NETHER_WOOD || g == BlockSoundGroup.CHERRY_WOOD
				|| g == BlockSoundGroup.CHISELED_BOOKSHELF || g == BlockSoundGroup.DECORATED_POT
				|| g == BlockSoundGroup.DECORATED_POT_SHATTER || g == BlockSoundGroup.CANDLE) {
			return new Base("声音组 WOOD(木头/木板)", 0.55f, 0.30f, 0.55f);
		}
		// ---- 黏液 / 蜂蜜：软、吸声（0.60 → 0.50）----
		if (g == BlockSoundGroup.SLIME || g == BlockSoundGroup.HONEY) {
			return new Base("声音组 SLIME(黏液/蜂蜜)", 0.50f, 0.20f, 0.75f);
		}
		// ---- 深板岩系列：比普通石更密实一点 ----
		if (g == BlockSoundGroup.DEEPSLATE || g == BlockSoundGroup.DEEPSLATE_BRICKS
				|| g == BlockSoundGroup.DEEPSLATE_TILES || g == BlockSoundGroup.POLISHED_DEEPSLATE
				|| g == BlockSoundGroup.BASALT || g == BlockSoundGroup.NETHER_BRICKS
				|| g == BlockSoundGroup.TUFF || g == BlockSoundGroup.CALCITE) {
			return new Base("声音组 DEEPSLATE(深板岩系)", 1.00f, 0.55f, 0.40f);
		}
		// ---- 幽匿系列：软而吸声（0.65 → 0.55）----
		if (g == BlockSoundGroup.SCULK || g == BlockSoundGroup.SCULK_CATALYST
				|| g == BlockSoundGroup.SCULK_SENSOR || g == BlockSoundGroup.SCULK_SHRIEKER) {
			return new Base("声音组 SCULK(幽匿系)", 0.55f, 0.25f, 0.70f);
		}
		// ---- 石头（默认大类）：硬、反射中等、吸声弱 ----
		if (g == BlockSoundGroup.STONE || g == BlockSoundGroup.NETHERRACK
				|| g == BlockSoundGroup.GILDED_BLACKSTONE || g == BlockSoundGroup.POINTED_DRIPSTONE
				|| g == BlockSoundGroup.DRIPSTONE_BLOCK) {
			return new Base("声音组 STONE(石头)", 1.00f, 0.60f, 0.45f);
		}
		return new Base(SOURCE_DEFAULT, DEFAULT[0], DEFAULT[1], DEFAULT[2]);
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// ------------------------------------------------------------ 诊断：材质探针

	/**
	 * <b>材质探针（诊断用）</b>：一行文本，说清"这个方块实际命中了什么、算出来是多少"。
	 *
	 * <p>格式（{@code physicsSoundDebug=true} 时由 {@link Acoustics} 每轮评估最多打 6 条）：
	 * <pre>
	 * minecraft:stone → 遮挡=1.00 反射率=0.60 吸声=0.45（来源=声音组 STONE(石头)）
	 * minecraft:glass → 遮挡=0.20 反射率=0.90 吸声=0.10（来源=…GLASS…｜非不透明方块(遮挡×0.8)）
	 * minecraft:oak_door → 遮挡=0.44 反射率=0.30 吸声=0.55（来源=…WOOD…｜非不透明方块(遮挡×0.8)）
	 * </pre>
	 * <b>看到"来源=默认值"就说明材质表退化了</b>（声音组的恒等比较没匹配上，全走了兜底）。
	 *
	 * <p><b>0.12.7</b>：这里打的 {@code 遮挡=} 就是射线累加真正用的那个数
	 * （材质值，<b>不含</b>"非完整方块打折"）—— 探针数字和听感终于对得上了。
	 * 另外补一句"碰撞形状"提示（满格 / 不满格），便于判断"这格是不是只挡了一部分"。
	 */
	public static String probeLine(BlockState state, BlockView world, BlockPos pos) {
		Params p = of(state);
		String src = p.source();
		if (!isFullCellShape(state, world, pos)) {
			src = src + "｜碰撞形状不满格（几何由射线求交判定）";
		}
		return blockId(state) + " → 遮挡=" + fmt2(occlusionOf(state))
				+ " 反射率=" + fmt2(p.reflectivity())
				+ " 吸声=" + fmt2(p.absorption())
				+ "（来源=" + src + "）";
	}

	/** 诊断用：碰撞形状的包围盒是不是填满整格（满格 = 实心/门这类"整格挡住视线"的方块）。 */
	private static boolean isFullCellShape(BlockState state, BlockView world, BlockPos pos) {
		try {
			if (state.getCollisionShape(world, pos).isEmpty()) {
				return false;
			}
			net.minecraft.util.math.Box b = state.getCollisionShape(world, pos).getBoundingBox();
			return b.minX <= 1.0E-7 && b.minY <= 1.0E-7 && b.minZ <= 1.0E-7
					&& b.maxX >= 1.0 - 1.0E-7 && b.maxY >= 1.0 - 1.0E-7 && b.maxZ >= 1.0 - 1.0E-7;
		} catch (Throwable t) {
			return true;
		}
	}

	/** 方块注册名（形如 {@code minecraft:stone}，中文名可能为空时用这个）。 */
	public static String blockId(BlockState state) {
		try {
			return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
		} catch (Throwable t) {
			return "<未知方块>";
		}
	}

	/** 诊断用：把几张常见方块的实际参数打出来，方便"数值 ↔ 听感"对齐（含来源标签）。 */
	public static List<String> sampleTable(BlockView world) {
		List<String> out = new ArrayList<>();
		String[] names = {"stone", "oak_planks", "glass", "white_wool", "dirt", "oak_leaves",
				"iron_block", "water", "cobblestone", "oak_log", "sand", "bedrock", "deepslate", "moss_block",
				"hay_block", "slime_block", "sculk", "gravel", "netherrack", "obsidian"};
		for (String n : names) {
			try {
				net.minecraft.block.Block b = net.minecraft.registry.Registries.BLOCK.get(new net.minecraft.util.Identifier(n));
				if (b == null) {
					continue;
				}
				BlockState st = b.getDefaultState();
				Params p = of(st);
				out.add(n + "=(" + fmt2(p.occlusion()) + "," + fmt2(p.reflectivity()) + ","
						+ fmt2(p.absorption()) + "|" + p.source() + ")");
			} catch (Throwable ignored) {
				// 名字不存在就算了
			}
		}
		return out;
	}

	private static String fmt2(float v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}
