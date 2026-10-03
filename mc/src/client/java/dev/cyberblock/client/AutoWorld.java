package dev.cyberblock.client;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Gets the player into the Night City world (the cyberblock:nightcity preset), creating it the first time:
 * <ul>
 * <li>tools/play.py: "--quickPlaySingleplayer cyberblock" opens the world, but only if it exists; when it doesn't, quick play ends
 * on an error screen, and this creates it instead;</li>
 * <li>a normal launcher installation (plan retest3 M5, F11): the title screen's "Play Cyberblock" button, and
 * -Dcyberblock.autostart=true, which presses it once per session as soon as the title screen shows.</li>
 * </ul>
 */
public final class AutoWorld {
	public static final String WORLD = "cyberblock";
	private static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(Cyberblock.ID, "nightcity"));
	/** The launcher profile's JVM argument (plan retest3 section 1.13). */
	private static final boolean AUTOSTART = Boolean.getBoolean("cyberblock.autostart");
	private static boolean requested;
	private static boolean autostarted;

	private AutoWorld() {
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (minecraft.level != null || requested) {
			return;
		}

		if (asksForWorld()) {
			if ((screen instanceof TitleScreen || screen instanceof DisconnectedScreen) && !minecraft.getLevelSource().levelExists(WORLD)) {
				create(minecraft, screen);
			}
		} else if (AUTOSTART && !autostarted && screen instanceof TitleScreen && minecraft.gui.overlay() == null) {
			// once per session: quitting to the title screen later leaves the player there
			autostarted = true;
			start(minecraft, screen, "autostart");
		}
	}

	/** Opens the Night City world, or creates it when there is none yet; failing or cancelling goes back to parent. Client thread. */
	public static void start(final Minecraft minecraft, final Screen parent, final String why) {
		Cyberblock.LOG.info("launcher: opening {} ({})", WORLD, why);
		if (minecraft.getLevelSource().levelExists(WORLD)) {
			minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(parent));
		} else {
			create(minecraft, parent);
		}
	}

	private static void create(final Minecraft minecraft, final Screen parent) {
		requested = true;
		Cyberblock.LOG.info("creating world {}", WORLD);
		LevelSettings settings = new LevelSettings("Cyberblock", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(), parent);
	}

	private static boolean asksForWorld() {
		List<String> args = Arrays.asList(FabricLoader.getInstance().getLaunchArguments(true));
		int i = args.indexOf("--quickPlaySingleplayer");
		return i >= 0 && i + 1 < args.size() && args.get(i + 1).equals(WORLD);
	}

	/** ScreenEvents.AFTER_INIT of the title screen: the "Play Cyberblock" button, where it covers nothing of vanilla's. */
	static void addTitleButton(final Minecraft minecraft, final TitleScreen screen, final int width, final int height) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		List<int[]> taken = new ArrayList<>();
		for (AbstractWidget widget : widgets) {
			taken.add(new int[] {widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()});
		}

		int[] at = place(width, height, taken);
		widgets.add(Button.builder(Component.translatableWithFallback("menu.cyberblock.play", "Play Cyberblock"),
			button -> start(minecraft, screen, "button")).bounds(at[0], at[1], at[2], BUTTON_HEIGHT).build());
	}

	static final int BUTTON_HEIGHT = 20;
	/** Vanilla's title screen column (TitleScreen.init: x = width/2 - 100, 200 wide, a row every 24). */
	private static final int COLUMN = 200, ROW = 24, SIDE = 100, GAP = 4;

	/**
	 * Where the button goes, as {x, y, width}: above vanilla's first button, else beside it (right, then left), else the top-left
	 * corner; the first place inside the screen that overlaps no widget (taken: {x, y, w, h}) and neither the logo nor the splash
	 * text (LogoRenderer draws them from y 30 to about 88 around the centre).
	 */
	static int[] place(final int width, final int height, final List<int[]> taken) {
		int top = Integer.MAX_VALUE;
		for (int[] r : taken) {
			// the topmost widget of the centre column is the Singleplayer button
			if (r[0] <= width / 2 && r[0] + r[2] >= width / 2) {
				top = Math.min(top, r[1]);
			}
		}

		List<int[]> blocked = new ArrayList<>(taken);
		blocked.add(new int[] {width / 2 - 128, 30, 256, 52}); // logo and edition
		blocked.add(new int[] {width / 2 + 60, 50, 140, 38}); // splash
		List<int[]> candidates = new ArrayList<>();
		if (top != Integer.MAX_VALUE) {
			candidates.add(new int[] {width / 2 - COLUMN / 2, top - ROW, COLUMN});
			candidates.add(new int[] {width / 2 + COLUMN / 2 + GAP, top, SIDE});
			candidates.add(new int[] {width / 2 - COLUMN / 2 - GAP - SIDE, top, SIDE});
		}

		for (int[] c : candidates) {
			if (fits(c, width, height, blocked)) {
				return c;
			}
		}

		return new int[] {GAP, GAP, SIDE};
	}

	private static boolean fits(final int[] c, final int width, final int height, final List<int[]> blocked) {
		if (c[0] < 0 || c[1] < 0 || c[0] + c[2] > width || c[1] + BUTTON_HEIGHT > height) {
			return false;
		}

		for (int[] r : blocked) {
			if (c[0] < r[0] + r[2] && r[0] < c[0] + c[2] && c[1] < r[1] + r[3] && r[1] < c[1] + BUTTON_HEIGHT) {
				return false;
			}
		}

		return true;
	}
}
