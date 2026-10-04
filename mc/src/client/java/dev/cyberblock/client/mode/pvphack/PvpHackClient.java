package dev.cyberblock.client.mode.pvphack;

import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.mode.pvphack.PvpHackWire;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The client side of hacking players (megaplan 8-X1): {@code cyberblock:ui pvphack} drives the Intrusion warning ({@link IntrusionHud}),
 * the glitch overlay ({@link GlitchOverlay}) and Ping's outlines for the hacker's team (a box drawn around the pinged player every
 * tick, as the scan box is). The client never closes a Breach screen itself: the server ends an intrusion's own short Breach. Client
 * thread.
 */
public final class PvpHackClient {
	/** Ping's outline: hot pink, translucent fill. */
	private static final int PING_STROKE = 0xFFFF3B8D;
	private static final int PING_FILL = 0x22FF3B8D;
	private static final Map<Integer, Long> pings = new HashMap<>();
	private static PvpHackWire.@Nullable Msg intrusion;
	private static long intrusionStartMs;
	private static long intrusionEndMs;
	private static long glitchStartMs;
	private static long glitchEndMs;
	private static int bad;

	private PvpHackClient() {
	}

	/** From the client entrypoint. */
	public static void init() {
		UiRoutes.listen(PvpHackWire.UI, PvpHackClient::onUi);
		HudElementRegistry.addLast(GlitchOverlay.ID, GlitchOverlay::draw);
		HudElementRegistry.addLast(IntrusionHud.ID, IntrusionHud::draw);
		ClientTickEvents.END_CLIENT_TICK.register(PvpHackClient::tick);
		PvpShots.init();
	}

	static void onUi(final String json) {
		PvpHackWire.Msg m;
		try {
			m = PvpHackWire.parse(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			m = null;
		}

		if (m == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("pvphack: bad ui message dropped ({} so far)", bad);
			}

			return;
		}

		long now = Util.getMillis();
		switch (m.kind()) {
			case INTRUSION -> {
				intrusion = m;
				intrusionStartMs = now;
				intrusionEndMs = now + m.ms();
			}
			// the warning goes; an intrusion's own short Breach that the upload beat is ended by the server (Breach.close), never closed
			// here: a Breach screen open now may be the player's own device or practice breach
			case END -> intrusion = null;
			case GLITCH -> {
				glitchStartMs = now;
				glitchEndMs = now + m.ms();
				Cyberblock.LOG.info("pvphack: glitch overlay for {} ms", m.ms());
			}
			case PING -> pings.put(m.eid(), now + m.ms());
		}
	}

	/** The intrusion warning shown now, or null. */
	static PvpHackWire.@Nullable Msg intrusion() {
		PvpHackWire.Msg m = intrusion;
		if (m != null && Util.getMillis() > intrusionEndMs) {
			intrusion = null; // the server's end message is on its way; never show a warning past its time
			return null;
		}

		return m;
	}

	/** 1 when the intrusion just began, 0 at its deadline. */
	static float intrusionLeft() {
		long span = Math.max(1L, intrusionEndMs - intrusionStartMs);
		return Math.clamp((intrusionEndMs - Util.getMillis()) / (float) span, 0.0F, 1.0F);
	}

	/** 1 when the glitch just began, falling to 0 when it ends; 0 when there is none. */
	static float glitchLeft() {
		long now = Util.getMillis();
		long span = glitchEndMs - glitchStartMs;
		return now >= glitchEndMs || span <= 0 ? 0.0F : Math.clamp((glitchEndMs - now) / (float) span, 0.0F, 1.0F);
	}

	static long glitchMsLeft() {
		return Math.max(0L, glitchEndMs - Util.getMillis());
	}

	/** Every client tick: Ping's outlines (gone when their time is up or the player is not in view). */
	private static void tick(final Minecraft minecraft) {
		PvpShots.tick(minecraft);
		if (pings.isEmpty()) {
			return;
		}

		long now = Util.getMillis();
		pings.values().removeIf(until -> now > until);
		if (minecraft.level == null) {
			pings.clear();
			return;
		}

		for (int id : pings.keySet()) {
			Entity e = minecraft.level.getEntity(id);
			if (e != null && !e.isRemoved()) {
				Gizmos.cuboid(e.getBoundingBox().inflate(0.08), GizmoStyle.strokeAndFill(PING_STROKE, 2.5F, PING_FILL));
			}
		}
	}
}
