package dev.cyberblock.hub;

import dev.cyberblock.Cyberblock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The Afterlife hub's dimension (megaplan 7-H1): {@code cyberblock:afterlife}, its own dimension type (always day: no day timeline and a
 * fixed time; no mob spawns: the void biome and a monster light level of 0), declared by the mod's datapack files
 * {@code data/cyberblock/dimension/afterlife.json} and {@code dimension_type/afterlife.json}. The layout it is built from is the
 * {@code hub} content file {@code data/cyberblock/cyberblock/hub/afterlife.json} (7A's art/gen_hub.py).
 */
public final class Afterlife {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Cyberblock.ID, "afterlife"));
	/** The layout file, as a resource path of the server's data. */
	public static final Identifier LAYOUT = Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock/hub/afterlife.json");

	private Afterlife() {
	}

	/** Is this level the Afterlife? */
	public static boolean is(final Level level) {
		return LEVEL.equals(level.dimension());
	}

	/** The Afterlife level, or null when the dimension is missing (its datapack files were removed). */
	public static @Nullable ServerLevel level(final MinecraftServer server) {
		return server.getLevel(LEVEL);
	}
}
