package dev.cyberblock.client.mode.van;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.hack.screen.FeedTexture;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Developer screenshots of the Hacker in the Van (megaplan 8-V1 "screenshots"): only when the environment variable
 * CYBERBLOCK_VAN_SHOTS is set (a dev client started by hand against a server with -Dcyberblock.devtools=true; never in a player's
 * game). It sits in a Van ({@code /cyberblock test van sit}), waits for targets and the photo feed, and saves into the run folder's
 * screenshots (8v_*.png): the Van screen, the Hack Wheel on a person, a car and a device, the drone's view with its HUD and the
 * aimed target, the wheel from the drone, and the Van with its drone from outside. Client thread.
 */
final class VanShots {
	private static final int STEP = 60;
	private static boolean enabled;
	private static int ticks;
	private static int step;
	private static int at;

	private VanShots() {
	}

	static void init() {
		enabled = System.getenv("CYBERBLOCK_VAN_SHOTS") != null;
		if (enabled) {
			Cyberblock.LOG.info("shots: CYBERBLOCK_VAN_SHOTS is set: the Van screenshot tour runs once targets are in sight");
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(VanShots::tick);
		}
	}

	private static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!enabled || player == null || step < 0) {
			return;
		}

		ticks++;
		VanWire.State s = VanClient.state();
		if (ticks - at < STEP) {
			return;
		}

		switch (step) {
			case 0 -> {
				if (ticks < 400) {
					return; // the host's figures and devices need a few lists first
				}

				player.connection.sendCommand("cyberblock test van sit");
			}
			case 1 -> {
				boolean ready = s != null && s.targets().stream().anyMatch(t -> t.kind() == VanRules.Kind.PERSON) && s.host() != null
					&& FeedTexture.age(s.host()) >= 0;
				if (!ready && ticks - at < 1200) {
					return;
				}

				if (!(minecraft.gui.screen() instanceof VanScreen)) {
					VanClient.openScreen(minecraft);
					at = ticks;
					return;
				}

				if (s != null && s.centre() != null && minecraft.level != null) {
					int cx = (int) Math.floor(s.centre().x), cz = (int) Math.floor(s.centre().z);
					VanMini.Columns columns = VanMini.of(minecraft.level);
					int[] here = columns.at((int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
					var detail = VanMini.detail(cx - 64, cz - 64, 129, columns);
					long known = 0;
					for (short h : detail.heights()) {
						known += h == dev.cyberblock.holo.HoloWire.UNKNOWN ? 0 : 1;
					}

					net.minecraft.core.BlockPos feet = player.blockPosition();
					var chunk = minecraft.level.getChunk(feet.getX() >> 4, feet.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
					Cyberblock.LOG.info("shots: miniature around V {} {}: {} of {} columns known; under the netrunner {} (chunk {}, surface {}, below the feet {})",
						cx, cz, known, 129 * 129, here == null ? "unknown" : here[0] + "/" + here[1], chunk == null ? "not loaded" : "loaded",
						chunk == null ? "-" : chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, feet.getX() & 15, feet.getZ() & 15),
						minecraft.level.getBlockState(feet.below()));
				}

				shot(minecraft, "8v_van_screen");
			}
			case 2 -> wheel(minecraft, s, VanRules.Kind.PERSON);
			case 3 -> {
				shot(minecraft, "8v_wheel_person");
				wheel(minecraft, s, VanRules.Kind.CAR);
			}
			case 4 -> {
				shot(minecraft, "8v_wheel_car");
				wheel(minecraft, s, VanRules.Kind.DEVICE);
			}
			case 5 -> {
				shot(minecraft, "8v_wheel_device");
				minecraft.gui.setScreen(null);
				VanClient.fly(minecraft);
			}
			case 6 -> {
				if (VanClient.drone(minecraft) == null && ticks - at < 600) {
					return;
				}

				player.connection.sendCommand("cyberblock test van fly 6 3 10");
			}
			case 7 -> {
				// look at the nearest target the drone sees, so the view's middle picks it
				VanWire.Target t = s == null ? null : s.targets().stream().filter(x -> x.seen() == VanRules.Seen.DRONE).findFirst()
					.orElse(s.targets().isEmpty() ? null : s.targets().getFirst());
				var drone = VanClient.drone(minecraft);
				if (t != null && drone != null) {
					Vec3 d = VanClient.place(minecraft, t).subtract(drone.getEyePosition());
					player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
					player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
				}
			}
			case 8 -> {
				shot(minecraft, "8v_drone_view");
				VanWire.Target t = VanClient.aimed();
				if (t != null) {
					minecraft.gui.setScreen(new VanWheelScreen(t, true));
				}
			}
			case 9 -> {
				shot(minecraft, "8v_wheel_drone");
				minecraft.gui.setScreen(null);
				VanClient.stopPiloting(minecraft, true);
				minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
			case 10 -> {
				shot(minecraft, "8v_van_outside");
			}
			case 11 -> {
				// a step after the last shot: the screenshot is written off the client thread
				minecraft.options.setCameraType(CameraType.FIRST_PERSON);
				VanClient.dock();
				Cyberblock.LOG.info("shots: done");
				step = -1;
				return;
			}
			default -> {
			}
		}

		step++;
		at = ticks;
	}

	private static void wheel(final Minecraft minecraft, final VanWire.@Nullable State s, final VanRules.Kind kind) {
		VanWire.Target t = s == null ? null : s.targets().stream().filter(x -> x.kind() == kind).findFirst().orElse(null);
		if (t == null) {
			Cyberblock.LOG.info("shots: no {} target for the wheel", kind.wire());
			return;
		}

		VanWheelScreen w = new VanWheelScreen(t, false);
		minecraft.gui.setScreen(w);
		Cyberblock.LOG.info("shots: wheel on {} {}: {}", kind.wire(), t.name(), w.labels());
	}

	private static void shot(final Minecraft minecraft, final String name) {
		Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> Cyberblock.LOG.info("shots: {} ({})", name, message.getString()));
	}
}
