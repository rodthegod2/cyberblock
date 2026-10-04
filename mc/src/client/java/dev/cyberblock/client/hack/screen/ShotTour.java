package dev.cyberblock.client.hack.screen;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostState;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Developer screenshots of the interaction map screens (megaplan 6-I1 "screenshots of each screen with the fake host"): only when
 * the environment variable CYBERBLOCK_SHOTS is set (a dev client started by hand; never in a player's game). Once a host with
 * cameras is known (or after 90 s) it opens each screen in turn and saves a screenshot into the run folder's screenshots
 * (6i_*.png): the Net Map, the Net Map with a feed panel (a test pattern, labelled as such: the fake host sends no `feed`), the
 * Vehicle Panel on the nearest car figure, the Traffic Tool, the Comms Panel, the Comms toast and waypoint it sent to this client's
 * own host, and, once the fake host has left (Cyberpunk no longer drives the camera), a camera feed. Client thread.
 */
final class ShotTour {
	private static final int STEP = 60;
	private static boolean enabled;
	private static int ticks = -1;
	private static int step;
	private static int at;
	private static NetState.@Nullable Cam cam;

	private ShotTour() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_SHOTS") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_SHOTS is set: the interaction map screenshot tour runs once a host is linked");
		}
	}

	static void tick(final Minecraft minecraft) {
		if (!enabled || minecraft.player == null || step < 0) {
			return;
		}

		ticks++;
		NetState state = ActionsClient.state();
		if (step == 0) {
			if (ticks % 40 == 0) {
				ActionsClient.requestState();
			}

			boolean ready = !state.hosts().isEmpty() && !state.cams().isEmpty() && car(minecraft) != null;
			if (ready && ticks > 200 || ticks > 1800) {
				cam = state.cams().isEmpty() ? null : state.cams().getFirst();
				next(minecraft);
			}

			return;
		}

		if (ticks - at < STEP) {
			return;
		}

		switch (step) {
			case 1 -> {
				shot(minecraft, "6i_netmap");
				if (minecraft.gui.screen() instanceof NetMapScreen map) {
					UUID host = map.shownHost();
					if (host != null) {
						FeedTexture.put(host, testPattern());
					}

					map.tour(true, NetMapScreen.Mode.VEHICLE);
				}
			}
			case 2 -> {
				shot(minecraft, "6i_netmap_feed_testpattern");
				ProxyEntity car = car(minecraft);
				minecraft.gui.setScreen(car == null ? new VehiclePanelScreen(-1, "") : new VehiclePanelScreen(car.getId(), car.label()));
				if (car != null) {
					ActionsClient.send(ActWire.T_RHACK, ActWire.vehicle(RemoteHack.VEH_ENGINE_OFF, car.getId()));
				}
			}
			case 3 -> {
				shot(minecraft, "6i_vehicle_panel");
				minecraft.gui.setScreen(new TrafficToolScreen());
			}
			case 4 -> {
				shot(minecraft, "6i_traffic_tool");
				CommsPanelScreen comms = new CommsPanelScreen();
				minecraft.gui.setScreen(comms);
				comms.type("Meet me at the noodle bar");
			}
			case 5 -> {
				shot(minecraft, "6i_comms_panel");
				NetState.Host h = ActionsClient.selectedHost();
				if (h != null) {
					Vec3 p = minecraft.player.position().add(12.0, 0.0, -6.0);
					ActionsClient.send(ActWire.T_COMMS, ActWire.comms(h.id(), "Meet me at the noodle bar", new int[] {(int) p.x, (int) p.y, (int) p.z}));
				}

				minecraft.gui.setScreen(null);
			}
			case 6 -> shot(minecraft, "6i_comms_toast_waypoint");
			case 7 -> {
				if (HostState.frame() != null && ticks - at < 1800) {
					return; // the camera feed needs Cyberpunk off this client's camera: wait for the fake host to leave
				}

				NetState.Cam c = cam;
				Vec3 p = c != null ? c.p() : minecraft.player.position().add(0.0, 4.0, 0.0);
				minecraft.gui.setScreen(new CameraFeedScreen(p, c != null ? c.yaw() : minecraft.player.getYRot(), "test"));
			}
			case 8 -> {
				shot(minecraft, "6i_camera_feed");
				minecraft.gui.setScreen(null);
				Cyberblock.LOG.info("shots: done");
				step = -1;
				return;
			}
			default -> {
			}
		}

		next(minecraft);
	}

	private static void next(final Minecraft minecraft) {
		step++;
		at = ticks;
		if (step == 1) {
			minecraft.gui.setScreen(new NetMapScreen());
		}
	}

	private static void shot(final Minecraft minecraft, final String name) {
		Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> Cyberblock.LOG.info("shots: {} ({})", name, message.getString()));
	}

	private static @Nullable ProxyEntity car(final Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			return null;
		}

		Vec3 p = minecraft.player.position();
		ProxyEntity best = null;
		for (Entity e : minecraft.level.getEntities((Entity) null, new AABB(p, p).inflate(128.0), e -> e.getType() == ProxyEntities.VEHICLE)) {
			if (e instanceof ProxyEntity proxy && !proxy.dead() && (best == null || e.distanceToSqr(p) < best.distanceToSqr(p))) {
				best = proxy;
			}
		}

		return best;
	}

	/** Colour bars over a gradient, in map colours (the dev tour's stand-in for a `feed` frame). */
	static byte[] testPattern() {
		int[] bars = {8, 15, 17, 18, 22, 25, 28, 29};
		byte[] px = new byte[ActWire.FEED_BYTES];
		for (int z = 0; z < ActWire.FEED_SIDE; z++) {
			for (int x = 0; x < ActWire.FEED_SIDE; x++) {
				int colour = z < 96 ? bars[x * bars.length / ActWire.FEED_SIDE] : 11;
				int shade = z < 96 ? 1 + z / 32 : x * 4 / ActWire.FEED_SIDE;
				px[z * ActWire.FEED_SIDE + x] = (byte) (colour * 4 + Math.min(3, shade));
			}
		}

		return px;
	}
}
