package dev.cyberblock.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;

/**
 * The hub's rules as pure functions (megaplan 7-H1, sections 1.9 {@code hub} and {@code park}/{@code parked}): what a linked player's
 * DLL is told when the player's place and what the DLL last heard differ, who may enter, and the {@code parked} answer.
 */
final class HubRules {
	static final int REASON_MAX = 128;
	/** Between two uses of a hub portal by one player. */
	static final long PORTAL_COOLDOWN_MS = 2_000L;

	/** {@code parked} {on, ok, reason}: the DLL's answer to every {@code park}. */
	record Parked(boolean on, boolean ok, String reason) {
	}

	private HubRules() {
	}

	/** {"t":"hub","in":...}: the DLL shows full-screen Minecraft (the loading-cover path, no depth blending) or the blend again. */
	static String hub(final boolean in) {
		return "{\"t\":\"hub\",\"in\":" + in + "}";
	}

	/** {"t":"park","on":...}: the scripts park V (invisible, invulnerable, unseen, saving and camera locked) or release V. */
	static String park(final boolean on) {
		return "{\"t\":\"park\",\"on\":" + on + "}";
	}

	/**
	 * What a linked player's DLL must hear, in order: entering the hub, {@code hub in:true} then {@code park on:true}; leaving it (by any
	 * way: the portal, the command, a death, a teleport), V released first, then the picture back. Nothing when the DLL already knows.
	 *
	 * @param inHub the player is in the Afterlife now
	 * @param told what this session's DLL was last told (null: nothing since its last hello, so it is out of the hub)
	 */
	static List<String> sync(final boolean inHub, final @Nullable Boolean told) {
		boolean dllIn = Boolean.TRUE.equals(told);
		if (inHub == dllIn) {
			return List.of();
		}

		return inHub ? List.of(hub(true), park(true)) : List.of(park(false), hub(false));
	}

	/**
	 * Why a player may not enter the hub now (a lang key), or null.
	 *
	 * @param drives the player's Cyberpunk is active (it has a V to park)
	 */
	static @Nullable String enterRefusal(final boolean hubMissing, final boolean inHub, final boolean drives, final boolean held, final boolean vehicle) {
		if (hubMissing) {
			return "cyberblock.hub.missing";
		}

		if (inHub) {
			return "cyberblock.hub.already";
		}

		if (drives && held) {
			return "cyberblock.hub.held";
		}

		return drives && vehicle ? "cyberblock.hub.vehicle" : null;
	}

	/** A valid {@code parked}, or null: on and ok booleans, reason a string of at most 128 bytes (absent = ""). */
	static @Nullable Parked parked(final JsonObject m) {
		if (!(m.get("on") instanceof JsonPrimitive on) || !on.isBoolean() || !(m.get("ok") instanceof JsonPrimitive ok) || !ok.isBoolean()) {
			return null;
		}

		String reason = "";
		JsonElement r = m.get("reason");
		if (r != null && !r.isJsonNull()) {
			if (!(r instanceof JsonPrimitive rp) || !rp.isString() || rp.getAsString().getBytes(StandardCharsets.UTF_8).length > REASON_MAX) {
				return null;
			}

			reason = rp.getAsString();
		}

		return new Parked(on.getAsBoolean(), ok.getAsBoolean(), reason);
	}

	/**
	 * A refused park ({@code on:true, ok:false}) takes the player out of the hub at once (shared/protocol.md "The cover only stays up
	 * over a parked V"): full-screen Minecraft must never hide a V that is neither invisible nor invulnerable.
	 */
	static boolean exitsHub(final Parked answer) {
		return answer.on() && !answer.ok();
	}

	/**
	 * The Phase 0 (g) path line (megaplan 4.1 g, unconfirmed in game until the CET checks G1-G6): which way parking is known to work.
	 * Only a park that worked or one never answered says it; refusals have their own line and take the player out.
	 *
	 * @param answered a {@code parked on:true, ok:true} came; false: none came in time
	 */
	static String parkPath(final boolean answered) {
		return answered ? "confirmed by the parked answer (V parked by the Phase 0 g method or its fast-travel-point fallback)"
			: "unconfirmed: no parked answer came, so the documented park (invisible, invulnerable, saving and camera locked) is assumed";
	}

	/** Whether the hub takes this player's {@code mayBuild}: in the Afterlife and not in creative. */
	static boolean grounded(final boolean inHub, final boolean creative) {
		return inHub && !creative;
	}

	/**
	 * Items refused to non-creative players in the Afterlife besides {@code mayBuild}: blocks, fluids, fire, bone meal, and everything
	 * that puts an entity down (a creeper or an end crystal would break the hub).
	 */
	static final List<Class<? extends Item>> REFUSED_ITEMS = List.of(BlockItem.class, BucketItem.class, SpawnEggItem.class, BoatItem.class,
		MinecartItem.class, ArmorStandItem.class, HangingEntityItem.class, EndCrystalItem.class, FlintAndSteelItem.class,
		FireChargeItem.class, BoneMealItem.class);

	/**
	 * Whether an item of this class is refused: on a block, every {@link #REFUSED_ITEMS} one; in the air the same except plain block
	 * items (carrots and berries are block items, and eating them is fine), but lily pads, which place on water from the air with no
	 * ability check (as spawn eggs and boats spawn).
	 */
	static boolean refusedItem(final Class<? extends Item> item, final boolean onBlock) {
		if (!onBlock && BlockItem.class.isAssignableFrom(item)) {
			return PlaceOnWaterBlockItem.class.isAssignableFrom(item);
		}

		return REFUSED_ITEMS.stream().anyMatch(c -> c.isAssignableFrom(item));
	}

	/** The facing of a block at (x, z) that looks toward (tx, tz): the larger of the two distances wins (ties go north-south). */
	static String facingToward(final double x, final double z, final double tx, final double tz) {
		double dx = tx - x, dz = tz - z;
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? "east" : "west";
		}

		return dz >= 0 ? "south" : "north";
	}
}
