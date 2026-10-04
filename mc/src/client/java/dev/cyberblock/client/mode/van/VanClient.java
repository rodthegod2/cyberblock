package dev.cyberblock.client.mode.van;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.mode.van.VanDrone;
import dev.cyberblock.mode.van.VanEntity;
import dev.cyberblock.mode.van.VanModule;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import dev.cyberblock.net.Payloads;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Hacker in the Van on the client (megaplan task 8-V1). Seated in a Van, a right-click opens the Van screen (the host's area,
 * its photo feed, the targets, the drone buttons); the server's {@code van} state keeps it current. Flying the drone, the camera
 * sits in it and turns with the mouse at once ({@link VanDrone#clientView}), WASD, Space and Ctrl (sprint) fly it, the view's
 * middle picks a target (outlined) and a right-click opens the Hack Wheel on it; a right-click on nothing goes back to the screen.
 * Shift gets out of the Van as from any seat (the drone docks). Client thread.
 */
public final class VanClient {
	/** The screen's feed and watch renewals (ticks; the server keeps them 5 s). */
	static final int RENEW_TICKS = 40;
	/** A launched drone may take this long (ticks) to reach this client before the camera gives up on it. */
	static final int DRONE_WAIT_TICKS = 60;
	private static VanWire.@Nullable State state;
	private static long stateAt;
	/** The camera is (or should be) in the drone. */
	private static boolean piloting;
	private static int waitTicks;
	private static VanWire.@Nullable Target aimed;
	private static int ticks;
	private static int bad;

	private VanClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		EntityRenderers.register(VanModule.VAN, VanRenderer::new);
		EntityRenderers.register(VanModule.DRONE, DroneRenderer::new);
		UiRoutes.listen(VanWire.UI_STATE, VanClient::onState);
		UiRoutes.listen(VanWire.UI_FEED, json -> {
			JsonObject m = JsonParser.parseString(json).getAsJsonObject();
			JsonObject frame = new JsonObject();
			frame.addProperty("w", ActWire.FEED_SIDE);
			frame.addProperty("h", ActWire.FEED_SIDE);
			frame.add("px", m.get("px"));
			byte[] px = ActWire.parseFeed(frame);
			if (px != null) {
				VanFeed.put(UUID.fromString(m.get("host").getAsString()), px);
			}
		});
		VanDrone.clientView = VanClient::view;
		ClientTickEvents.START_CLIENT_TICK.register(VanClient::input);
		ClientTickEvents.END_CLIENT_TICK.register(VanClient::tick);
		HudElementRegistry.addLast(VanHud.ID, VanHud::draw);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(VanClient::clear));
		VanShots.init();
		Cyberblock.LOG.info("van: the Hacker in the Van's client is ready (Van screen, Hack Wheel, drone camera)");
	}

	private static void onState(final String json) {
		VanWire.State s;
		try {
			s = VanWire.parseState(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			s = null;
		}

		if (s == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("van: bad state dropped ({} so far)", bad);
			}

			return;
		}

		state = s;
		stateAt = System.nanoTime();
		Minecraft minecraft = Minecraft.getInstance();
		if (s.van() < 0) {
			stopPiloting(minecraft, false);
			if (minecraft.gui.screen() instanceof VanScreen || minecraft.gui.screen() instanceof VanWheelScreen) {
				minecraft.gui.setScreen(null);
			}

			return;
		}

		if (s.open() && !(minecraft.gui.screen() instanceof VanScreen)) {
			openScreen(minecraft);
		}
	}

	/** The newest state (null before the first, or after leaving the Van). */
	static VanWire.@Nullable State state() {
		VanWire.State s = state;
		return s == null || s.van() < 0 ? null : s;
	}

	/** Seconds since the last state (for the screen's "waiting" line). */
	static double stateAge() {
		return stateAt == 0L ? Double.MAX_VALUE : (System.nanoTime() - stateAt) / 1e9;
	}

	static boolean piloting() {
		return piloting;
	}

	static VanWire.@Nullable Target aimed() {
		return aimed;
	}

	static boolean seated(final Minecraft minecraft) {
		return minecraft.player != null && minecraft.player.getVehicle() instanceof VanEntity;
	}

	/** A request to the server; false when it has no Cyberblock channel. */
	static boolean send(final String t, final String json) {
		if (!ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			return false;
		}

		ClientPlayNetworking.send(new Payloads.Act(t, json));
		return true;
	}

	static void openScreen(final Minecraft minecraft) {
		minecraft.gui.setScreen(new VanScreen());
	}

	/** Fly: launch (or take back) the drone; the camera follows once its entity is here. */
	static void fly(final Minecraft minecraft) {
		VanWire.State s = state();
		if (s == null) {
			return;
		}

		send(VanWire.T_VAN, s.drone() == null ? VanWire.launch() : VanWire.pilot(true));
		piloting = true;
		waitTicks = 0;
		minecraft.gui.setScreen(null);
	}

	static void dock() {
		send(VanWire.T_VAN, VanWire.dock());
		stopPiloting(Minecraft.getInstance(), false);
	}

	/** The camera back to the netrunner (the drone keeps hovering until docked); tells the server unless it already knows. */
	static void stopPiloting(final Minecraft minecraft, final boolean tell) {
		if (piloting && tell) {
			send(VanWire.T_VAN, VanWire.pilot(false));
		}

		piloting = false;
		aimed = null;
		if (minecraft.player != null && minecraft.getCameraEntity() instanceof VanDrone) {
			minecraft.setCameraEntity(minecraft.player);
		}
	}

	/** The drone this client flies now (its entity is here), else null. */
	static @Nullable VanDrone drone(final Minecraft minecraft) {
		VanWire.State s = state();
		if (s == null || s.drone() == null || minecraft.level == null) {
			return null;
		}

		return minecraft.level.getEntity(s.drone().eid()) instanceof VanDrone d && !d.isRemoved() ? d : null;
	}

	/** VanDrone.clientView: the local pilot's own view turns the camera at once (the server follows from his rotation). */
	private static float @Nullable [] view(final VanDrone drone, final float partialTicks) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (!piloting || player == null || minecraft.getCameraEntity() != drone) {
			return null;
		}

		return new float[] {player.getViewYRot(partialTicks), player.getViewXRot(partialTicks)};
	}

	/**
	 * Start of each tick, before vanilla handles the keys: seated, a right-click is the Van's (the screen; flying, the wheel on the
	 * aimed target or back to the screen) and an attack does nothing, so neither reaches the world from inside the Van.
	 */
	private static void input(final Minecraft minecraft) {
		if (!seated(minecraft) || minecraft.gui.screen() != null) {
			return;
		}

		boolean used = false;
		while (minecraft.options.keyUse.consumeClick()) {
			used = true;
		}

		if (minecraft.options.keyUse.isDown()) {
			minecraft.options.keyUse.setDown(false);
		}

		while (piloting && minecraft.options.keyAttack.consumeClick()) {
			// flying: no punching from inside the Van
		}

		if (!used) {
			return;
		}

		VanWire.Target t = aimed;
		if (piloting && t != null) {
			minecraft.gui.setScreen(new VanWheelScreen(t, true));
		} else {
			stopPiloting(minecraft, true);
			openScreen(minecraft);
		}
	}

	private static void tick(final Minecraft minecraft) {
		ticks++;
		if (!seated(minecraft)) {
			if (piloting || minecraft.getCameraEntity() instanceof VanDrone) {
				stopPiloting(minecraft, false);
			}

			return;
		}

		VanDrone drone = drone(minecraft);
		if (piloting && drone == null && ++waitTicks > DRONE_WAIT_TICKS) {
			stopPiloting(minecraft, true);
		}

		if (piloting && drone != null) {
			waitTicks = 0;
			if (minecraft.getCameraEntity() != drone) {
				minecraft.setCameraEntity(drone);
				Cyberblock.LOG.info("van: camera in the drone");
			}

			aim(minecraft, drone);
		} else if (minecraft.getCameraEntity() instanceof VanDrone && minecraft.player != null) {
			minecraft.setCameraEntity(minecraft.player);
		}

		VanWire.State s = state();
		if (minecraft.gui.screen() instanceof VanScreen && s != null && ticks % RENEW_TICKS == 0) {
			watch(s, true);
		}
	}

	/** The Van screen's photo feed: watched on the linked host (6-I1's feed act keeps the DLL's feed on; the van op routes frames here). */
	static void watch(final VanWire.State s, final boolean on) {
		send(VanWire.T_VAN, VanWire.screen(on));
		if (s.host() != null) {
			send(ActWire.T_FEED, ActWire.feedWatch(s.host(), on));
		}
	}

	/** Flying: the target in the middle of the drone's view, outlined. */
	private static void aim(final Minecraft minecraft, final VanDrone drone) {
		VanWire.State s = state();
		LocalPlayer player = minecraft.player;
		if (s == null || player == null || minecraft.level == null) {
			aimed = null;
			return;
		}

		Vec3 eye = drone.getEyePosition();
		aimed = VanAim.pick(s.targets(), eye, VanAim.look(player.getYRot(), player.getXRot()), t -> place(minecraft, t));
		if (aimed != null) {
			Entity e = aimed.kind() == VanRules.Kind.DEVICE ? null : minecraft.level.getEntity(aimed.eid());
			AABB box = e != null ? e.getBoundingBox() : new AABB(aimed.p(), aimed.p()).inflate(0.5, 0.0, 0.5).expandTowards(0.0, 1.0, 0.0);
			Gizmos.cuboid(box.inflate(0.1), GizmoStyle.stroke(aimed.hostile() ? 0xE0FF5555 : 0xE055FFFF, 2.5F)).setAlwaysOnTop();
		}
	}

	/** A target's place now: the tracked entity's centre, else the state's position (a device: its block's middle). */
	static Vec3 place(final Minecraft minecraft, final VanWire.Target t) {
		Entity e = t.kind() == VanRules.Kind.DEVICE || minecraft.level == null ? null : minecraft.level.getEntity(t.eid());
		return e != null ? e.getBoundingBox().getCenter() : t.p().add(0.0, t.kind() == VanRules.Kind.DEVICE ? 0.5 : 0.9, 0.0);
	}

	private static void clear() {
		state = null;
		stateAt = 0L;
		piloting = false;
		aimed = null;
		VanFeed.clear();
	}
}
