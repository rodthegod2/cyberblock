package dev.cyberblock.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.ArmorStandItem;
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
	 * The refusals that come while the DLL draws no cover: Cyberblock is not On (the compositor draws nothing while Off) or there is no
	 * V yet (a load). The scripts park V by themselves once Cyberblock is On with a player (7S Hub.reds, every 1 s while the hub lasts),
	 * so the player stays in the hub. Built by the DLL from Park.reds' {@code park: on refused (<why>)} (world::parked_from_log).
	 */
	static final Set<String> NO_COVER = Set.of("blocked (Cyberblock is not active)", "blocked (no player)");

	/**
	 * A refused park ({@code on:true, ok:false}) takes the player out of the hub at once (shared/protocol.md "The cover only stays up
	 * over a parked V"): full-screen Minecraft must never hide a V that is neither invisible nor invulnerable. Not for the
	 * {@link #NO_COVER} refusals: nothing is hidden then, and leaving would keep a host whose Cyberpunk is Off out of the hub for good.
	 */
	static boolean exitsHub(final Parked answer) {
		return answer.on() && !answer.ok() && !NO_COVER.contains(answer.reason());
	}

	/**
	 * The Phase 0 (g) path line (megaplan 4.1 g, unconfirmed in game until the CET checks G1-G6): which way parking is known to work.
	 * Only a park that worked or one never answered says it; refusals have their own lines (see {@link #exitsHub}).
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
	 * Items refused to non-creative players in the Afterlife besides {@code mayBuild}: fluids, fire, bone meal, everything that puts an
	 * entity down (a creeper or an end crystal would break the hub), and lily pads, which place on water from the air with no ability
	 * check. Plain block items are not here: placing one on a block is refused by {@code mayBuild} ({@code ItemStack.useOn}), and
	 * holding one (a carrot, seeds, a stack of stone) must neither stop eating nor stop a right-click opening the Holo-Table.
	 */
	static final List<Class<? extends Item>> REFUSED_ITEMS = List.of(BucketItem.class, SpawnEggItem.class, BoatItem.class,
		MinecartItem.class, ArmorStandItem.class, HangingEntityItem.class, EndCrystalItem.class, FlintAndSteelItem.class,
		FireChargeItem.class, BoneMealItem.class, PlaceOnWaterBlockItem.class);

	/** Whether an item of this class is one of the {@link #REFUSED_ITEMS} (or a subclass). */
	static boolean refusedItem(final Class<? extends Item> item) {
		return REFUSED_ITEMS.stream().anyMatch(c -> c.isAssignableFrom(item));
	}

	/**
	 * Whether a right-click on a block is refused: a refused item, unless the block has its own use in the hub (a hub block or the
	 * Holo-Table), which works whatever the player holds.
	 */
	static boolean refusedOnBlock(final Class<? extends Item> item, final boolean usableBlock) {
		return !usableBlock && refusedItem(item);
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
