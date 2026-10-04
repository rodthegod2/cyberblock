package dev.cyberblock.net;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of the custom-payload protocol (megaplan section 1.3): registers the payload types and the server receivers, and
 * sends S2C payloads. Receivers run on the server thread (see Payloads for the verified Fabric names and threads).
 */
public final class ServerNet {
	private ServerNet() {
	}

	/** Common init, once (Cyberblock.onInitialize): payload types for both sides, then the server receivers. */
	public static void init() {
		Payloads.register();
	}

	/** Sends a payload to a player that has the Cyberblock mod; false (and nothing sent) when its client cannot receive it. */
	static boolean send(final ServerPlayer player, final CustomPacketPayload payload) {
		if (!ServerPlayNetworking.canSend(player, payload.type())) {
			return false;
		}

		ServerPlayNetworking.send(player, payload);
		return true;
	}

	/** cyberblock:ui to one player (server data for a screen); a json over the cap is dropped and logged. */
	public static void ui(final ServerPlayer player, final String t, final String json) {
		if (!Payloads.fits(t, Payloads.T_CAP) || !Payloads.fits(json, Payloads.DOWN_CAP)) {
			Cyberblock.LOG.warn("ui {} for {} over the cap: dropped", t, player.getPlainTextName());
			return;
		}

		send(player, new Payloads.Ui(t, json));
	}
}
