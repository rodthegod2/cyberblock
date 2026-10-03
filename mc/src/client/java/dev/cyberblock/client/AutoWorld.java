package dev.cyberblock.client;

import dev.cyberblock.Cyberblock;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * First run: "--quickPlaySingleplayer cyberblock" opens the world, but only if it exists. When it doesn't, quick play
 * ends on an error screen; this creates the Night City world (the cyberblock:nightcity preset) instead.
 */
public final class AutoWorld {
	public static final String WORLD = "cyberblock";
	private static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(Cyberblock.ID, "nightcity"));
	private static boolean requested;
	/** True once this session created the world: it is new, so it gets the starter blocks. */
	static volatile boolean created;

	private AutoWorld() {
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (requested || minecraft.level != null || !(screen instanceof TitleScreen || screen instanceof DisconnectedScreen)
			|| !asksForWorld() || minecraft.getLevelSource().levelExists(WORLD)) {
			return;
		}

		requested = true;
		Cyberblock.LOG.info("creating world {}", WORLD);
		created = true;
		LevelSettings settings = new LevelSettings("Cyberblock", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(), screen);
	}

	private static boolean asksForWorld() {
		List<String> args = Arrays.asList(FabricLoader.getInstance().getLaunchArguments(true));
		int i = args.indexOf("--quickPlaySingleplayer");
		return i >= 0 && i + 1 < args.size() && args.get(i + 1).equals(WORLD);
	}
}
