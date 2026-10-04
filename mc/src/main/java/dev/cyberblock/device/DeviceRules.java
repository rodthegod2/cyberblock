package dev.cyberblock.device;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.Permissions.DeviceType;
import dev.cyberblock.replica.ReplicaBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The device layer's rules (megaplan 4-M1), pure: where a device block may go, when a device is gone, what a player's use of a device
 * block asks Night City for, and what the device shows once Night City said yes. The permission check (the owner's devices toggle and
 * the type bit for guests) is Permissions.allowsDevice.
 */
public final class DeviceRules {
	/** A device missing from every devices list for 10 s is gone (server ticks). */
	public static final long GONE_TICKS = 200;
	/** A devact with no devresult after 10 s is answered "no answer" (server ticks). */
	public static final long ANSWER_TICKS = 200;
	/** A player's device uses at most every 4 ticks (5 a second). */
	public static final long USE_GAP_TICKS = 4;

	/** What a use or a request turns into. */
	public enum Outcome {
		/** devact DOWN with this act, to the owner. */
		SEND,
		/** The vending machine's Minecraft shop opens (or a buy goes through it). */
		SHOP,
		/** Nothing goes to Night City; the player reads the message. */
		REFUSED
	}

	/** The decision: an act to send, the shop, or a refusal with its lang key and English fallback. */
	public record Use(Outcome outcome, String act, String key, String fallback) {
		static Use send(final String act) {
			return new Use(Outcome.SEND, act, "", "");
		}

		static final Use SHOP = new Use(Outcome.SHOP, "buy", "", "");
		static final Use QUEST_LOCKED = refused("cyberblock.device.quest_locked", "Locked by Night City (a quest or a scene): it cannot be used now");
		static final Use UNSUPPORTED = refused("cyberblock.device.unsupported", "Night City cannot do that from Minecraft yet");
		static final Use LOCKED = refused("cyberblock.device.locked", "Locked: it needs Breach Protocol or a Device Relay");
		static final Use BROKEN = refused("cyberblock.device.broken", "This device is broken");
		static final Use PHASE6 = refused("cyberblock.device.breach_later", "Breach Protocol and the Traffic Tool come in a later update");

		static Use refused(final String key, final String fallback) {
			return new Use(Outcome.REFUSED, "", key, fallback);
		}
	}

	private DeviceRules() {
	}

	/**
	 * May a device block go into this cell? Never over another device's block or a player's block: only into air, a replica block,
	 * its own earlier block (still the same block), or a leftover of the very block it wants when that is a cyberblock block (a crash
	 * left it in the save). Cyberblock blocks have no item, so no player owns one; a vanilla block (a stand-in, a door, a datapack's
	 * choice) may be a player's and is never claimed.
	 *
	 * @param ownedByMe null when no device holds the cell, else whether this device does
	 * @param placedHere what this device set there before, or null
	 */
	public static boolean mayPlace(final BlockState current, final BlockState want, final @Nullable Boolean ownedByMe, final @Nullable BlockState placedHere) {
		if (Boolean.FALSE.equals(ownedByMe)) {
			return false;
		}

		if (current.isAir() || ReplicaBlocks.is(current)) {
			return true;
		}

		return placedHere != null && current.getBlock() == placedHere.getBlock() || current.getBlock() == want.getBlock() && unownable(current);
	}

	/** A block no player can own: a cyberblock block (none has an item). */
	static boolean unownable(final BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(Cyberblock.ID);
	}

	/** The device left every list 10 s ago. */
	public static boolean gone(final long lastSeen, final long now) {
		return now - lastSeen >= GONE_TICKS;
	}

	/** A right-click on the device's block (sneaking with an empty hand: the second act, a turret's make-friendly). */
	public static Use use(final Device d, final boolean sneaking) {
		if (d.questLocked()) {
			return Use.QUEST_LOCKED;
		}

		return switch (d.type()) {
			case DOOR -> d.locked() ? Use.LOCKED : Use.send(d.open() ? "close" : "open");
			case GATE -> Use.send(d.open() ? "close" : "open");
			// cameras and turrets use on/off, not disable: a DISABLED device reads as quest-locked (fl bit 4) and could not come back
			case LIGHT, CAMERA -> Use.send(d.on() ? "off" : "on");
			case TURRET -> sneaking ? Use.send("friendly") : Use.send(d.on() ? "off" : "on");
			case SCREEN -> Use.send("distract");
			case VENDING -> d.broken() ? Use.BROKEN : Use.SHOP;
			case TERMINAL, TRAFFIC -> Use.PHASE6;
			case ELEVATOR, CONTAINER -> Use.UNSUPPORTED; // a lift call is unproven; container contents are not readable
		};
	}

	/**
	 * An explicit act from {@code cyberblock:act t:"devact"}: the Night City acts of the section 1.9 amendment go (unlocking and
	 * opening a locked door need Breach, Phase 6), buy is the vending machine's Minecraft shop, call and breach are greyed.
	 */
	public static Use request(final Device d, final String act) {
		if (act.equals("use")) {
			return use(d, false);
		}

		if (act.equals("buy")) {
			return d.type() != DeviceType.VENDING ? Use.UNSUPPORTED : d.questLocked() ? Use.QUEST_LOCKED : d.broken() ? Use.BROKEN : Use.SHOP;
		}

		if (!DeviceWire.NIGHT_CITY_ACTS.contains(act)) {
			return Use.UNSUPPORTED;
		}

		if (d.questLocked()) {
			return Use.QUEST_LOCKED;
		}

		boolean opens = act.equals("unlock") || act.equals("open") || act.equals("toggle") && !d.open();
		return d.type() == DeviceType.DOOR && d.locked() && opens ? Use.LOCKED : Use.send(act);
	}

	/** What the device shows once Night City answered ok to this act (devresult ok), or null when the act changes nothing shown. */
	public static @Nullable Device expected(final Device d, final String act) {
		boolean door = d.type() == DeviceType.DOOR;
		return switch (act) {
			case "open", "on", "enable" -> d.withState(Device.ST_ON, d.fl());
			case "close", "off", "disable" -> d.withState(Device.ST_OFF, d.fl());
			case "toggle" -> d.withState(d.st() == Device.ST_ON ? Device.ST_OFF : Device.ST_ON, d.fl());
			case "lock" -> d.withState(door ? Device.DOOR_LOCKED : d.st(), d.fl() | Device.LOCKED);
			case "unlock" -> d.withState(door && d.st() == Device.DOOR_LOCKED ? Device.DOOR_CLOSED : d.st(), d.fl() & ~Device.LOCKED);
			default -> null;
		};
	}
}
