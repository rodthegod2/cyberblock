package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The mechanic's screen (megaplan task 7-N1). Every player: worn armour and elytra repaired to full for {@code repair_price} each. A
 * host whose Cyberpunk is On: the vehicle V sits in is repaired in Cyberpunk for {@code vehicle_price}, through {@code
 * {"t":"repair","what":"vehicle"}} DOWN to the host's game; the scripts' repair call (7S, verified there) answers nothing on the wire,
 * so the server logs the request and its runtime check: the session must be Active with V in a vehicle, else the row says so and the
 * log names the fallback (gear only). Server thread.
 */
final class Garage implements Visit.Service {
	static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static boolean loggedPath;

	@Override
	public List<String> tabs(final Visit v) {
		return List.of("repair");
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		int eddies = Eddies.shown(v.player);
		boolean creative = v.player.isCreative();
		List<NpcWire.Row> rows = new ArrayList<>();
		HostSession s = HostSessions.of(v.player);
		if (s != null && s.state() != HostSession.State.NONE) {
			rows.add(new NpcWire.Row("vehicle", "minecraft:minecart", 1, "", "", v.role.vehiclePrice(), "repair_vehicle",
				s.active() && s.vehicle() && (v.role.vehiclePrice() <= eddies || creative)));
		}

		for (EquipmentSlot slot : WORN) {
			ItemStack stack = v.player.getItemBySlot(slot);
			if (!stack.isEmpty() && stack.isDamageableItem()) {
				rows.add(new NpcWire.Row("slot." + slot.getName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 1, "", "",
					v.role.repairPrice(), "repair", stack.isDamaged() && (v.role.repairPrice() <= eddies || creative)));
			}
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		if (row.equals("vehicle")) {
			return this.vehicle(v);
		}

		for (EquipmentSlot slot : WORN) {
			if (row.equals("slot." + slot.getName())) {
				ItemStack stack = v.player.getItemBySlot(slot);
				if (stack.isEmpty() || !stack.isDamaged()) {
					return "none";
				}

				if (!Eddies.pay(v.player, v.role.repairPrice())) {
					v.say("poor");
					return "poor";
				}

				stack.setDamageValue(0);
				v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6F, 1.2F);
				Cyberblock.LOG.info("npc: {} had {} repaired for {} eddies", v.player.getPlainTextName(), slot.getName(), v.role.repairPrice());
				v.say("repair");
				return "repaired";
			}
		}

		return "gone";
	}

	/** The host's Cyberpunk vehicle: the runtime check, then the DOWN request (logged with the path it took). */
	private String vehicle(final Visit v) {
		HostSession s = HostSessions.of(v.player);
		String check = vehicleCheck(s != null && s.state() != HostSession.State.NONE, s != null && s.active(), s != null && s.vehicle());
		if (!check.isEmpty()) {
			logPath(v.player.getPlainTextName() + ": " + check + " (fallback: armour and elytra repair only)");
			return check;
		}

		if (!Eddies.pay(v.player, v.role.vehiclePrice())) {
			v.say("poor");
			return "poor";
		}

		s.send(repairMessage());
		Cyberblock.LOG.info("npc: mechanic: {} vehicle repair sent to Cyberpunk (repair DOWN, {} eddies)", v.player.getPlainTextName(), v.role.vehiclePrice());
		logPath("vehicle repair goes to the host's Cyberpunk (repair DOWN; 7S applies the verified call)");
		v.say("repair");
		return "repair_sent";
	}

	/** Why a host's vehicle cannot be repaired now ("" when it can): not a host, Cyberpunk not On, V on foot. Pure. */
	static String vehicleCheck(final boolean host, final boolean active, final boolean inVehicle) {
		return !host ? "not_host" : !active ? "not_active" : !inVehicle ? "no_vehicle" : "";
	}

	static String repairMessage() {
		JsonObject m = new JsonObject();
		m.addProperty("t", "repair");
		m.addProperty("what", "vehicle");
		return m.toString();
	}

	private static void logPath(final String line) {
		if (!loggedPath || line.startsWith("vehicle")) {
			loggedPath = true;
			Cyberblock.LOG.info("npc: mechanic path: {}", line.toLowerCase(Locale.ROOT));
		}
	}
}
