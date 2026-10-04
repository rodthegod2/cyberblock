package dev.cyberblock.replica;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * The Night City replica blocks (megaplan task 3-R1): {@code cyberblock:nc_<material>}, solid full blocks placed from the hosts'
 * probes. The material codes are shared with the scripts, the DLL and the art (shared/vectors.json key {@code materials}): 0 unknown,
 * 1..10 the ten materials of {@link #MATERIALS} in that order, 11..26 the neon colours in the vanilla dye order (light level 15).
 * Like barriers they are unbreakable in survival and spared by explosions; no loot, no item, no mob spawns. Registered on both sides
 * at startup ({@link #register}); 3A ships their blockstates, models, textures and lang.
 */
public final class ReplicaBlocks {
	/** Codes 1..10. */
	public static final List<String> MATERIALS = List.of("concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal", "brick", "tile",
		"ground", "water");
	public static final int UNKNOWN = 0;
	public static final int NEON_FIRST = MATERIALS.size() + 1;
	/** Codes are 0..CODES-1. */
	public static final int CODES = NEON_FIRST + DyeColor.values().length;
	public static final int CONCRETE_LIGHT = 1, CONCRETE_DARK = 2, ASPHALT = 3, SIDEWALK = 4, GLASS = 5, METAL = 6, BRICK = 7, TILE = 8, GROUND = 9,
		WATER = 10;

	/** A block of the replica, with its material code. */
	public interface Replica {
		int material();
	}

	static final class Solid extends Block implements Replica {
		private final int material;

		Solid(final BlockBehaviour.Properties properties, final int material) {
			super(properties);
			this.material = material;
		}

		@Override
		public int material() {
			return this.material;
		}
	}

	/** Glass and water: see-through, no faces between two of them (vanilla TransparentBlock). */
	static final class Clear extends TransparentBlock implements Replica {
		private final int material;

		Clear(final BlockBehaviour.Properties properties, final int material) {
			super(properties);
			this.material = material;
		}

		@Override
		public int material() {
			return this.material;
		}
	}

	private static final Block[] BLOCKS = new Block[CODES];

	private ReplicaBlocks() {
	}

	/** The block id path of a code ({@code nc_asphalt}, {@code nc_neon_light_blue}), or null for 0 and codes out of range. */
	public static @Nullable String name(final int code) {
		if (code <= UNKNOWN || code >= CODES) {
			return null;
		}

		return code < NEON_FIRST ? "nc_" + MATERIALS.get(code - 1) : "nc_neon_" + DyeColor.values()[code - NEON_FIRST].getSerializedName();
	}

	/** The code of a block id ({@code cyberblock:nc_asphalt} or {@code nc_asphalt}), 0 when it is no replica block. */
	public static int code(final String id) {
		String path = id.toLowerCase(Locale.ROOT);
		if (path.startsWith(Cyberblock.ID + ":")) {
			path = path.substring(Cyberblock.ID.length() + 1);
		}

		for (int code = 1; code < CODES; code++) {
			if (path.equals(name(code))) {
				return code;
			}
		}

		return UNKNOWN;
	}

	/** Every block id path, code order (1..26). */
	public static List<String> names() {
		List<String> out = new ArrayList<>();
		for (int code = 1; code < CODES; code++) {
			out.add(name(code));
		}

		return out;
	}

	/** Common init, once: registers the 26 blocks. */
	public static void register() {
		for (int code = 1; code < CODES; code++) {
			BLOCKS[code] = Registry.register(BuiltInRegistries.BLOCK, key(code), create(code));
		}
	}

	private static ResourceKey<Block> key(final int code) {
		return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Cyberblock.ID, name(code)));
	}

	/** A new block of this material, not registered yet. */
	static Block create(final int code) {
		BlockBehaviour.Properties p = properties(code).setId(key(code));
		return code == GLASS || code == WATER ? new Clear(p, code) : new Solid(p, code);
	}

	/** Unbreakable in survival and by explosions (as a barrier), no loot, never a spawn spot; colour, sound and light per material. */
	private static BlockBehaviour.Properties properties(final int code) {
		BlockBehaviour.Properties p = BlockBehaviour.Properties.of().strength(-1.0F, 3600000.8F).noLootTable().isValidSpawn(Blocks::never);
		if (code >= NEON_FIRST) {
			return p.mapColor(DyeColor.values()[code - NEON_FIRST]).lightLevel(state -> 15).sound(SoundType.GLASS);
		}

		return switch (code) {
			case CONCRETE_LIGHT -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).sound(SoundType.STONE);
			case CONCRETE_DARK -> p.mapColor(MapColor.COLOR_GRAY).sound(SoundType.STONE);
			case ASPHALT -> p.mapColor(MapColor.COLOR_BLACK).sound(SoundType.STONE);
			case SIDEWALK -> p.mapColor(MapColor.STONE).sound(SoundType.STONE);
			case GLASS -> p.mapColor(MapColor.ICE).sound(SoundType.GLASS).noOcclusion().isRedstoneConductor(Blocks::never).isSuffocating(Blocks::never);
			case METAL -> p.mapColor(MapColor.METAL).sound(SoundType.METAL);
			case BRICK -> p.mapColor(MapColor.COLOR_RED).sound(SoundType.MUD_BRICKS);
			case TILE -> p.mapColor(MapColor.TERRACOTTA_WHITE).sound(SoundType.STONE);
			case GROUND -> p.mapColor(MapColor.DIRT).sound(SoundType.GRAVEL);
			case WATER -> p.mapColor(MapColor.WATER).sound(SoundType.GLASS).noOcclusion().isRedstoneConductor(Blocks::never).isSuffocating(Blocks::never);
			default -> throw new IllegalArgumentException("no replica material " + code);
		};
	}

	/** The registered block of a code (null for 0, codes out of range, or before {@link #register}). */
	public static @Nullable Block block(final int code) {
		return code <= UNKNOWN || code >= CODES ? null : BLOCKS[code];
	}

	/** True for a replica block. */
	public static boolean is(final BlockState state) {
		return state.getBlock() instanceof Replica;
	}

	/** The material code of a replica block, 0 for anything else. */
	public static int material(final BlockState state) {
		return state.getBlock() instanceof Replica r ? r.material() : UNKNOWN;
	}
}
