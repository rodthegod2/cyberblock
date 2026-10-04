package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The mechanic's screen (megaplan task 7-N1). {@code repair_gear}: worn armour and elytra repaired to full for {@code
 * prices.repair_gear} each. {@code repair_vehicle}, for a host: the host's current Cyberpunk car repaired for {@code
 * prices.repair_vehicle} through {@code {"t":"repair","id":N}} DOWN (7S's verified repair call, with its own runtime check and
 * fallback in the scripts). The server's runtime check is the session: a host whose Cyberpunk is On; otherwise the row says why and
 * the log names the fallback (gear repair only). Server thread.
 */
final class Garage implements Visit.Service {
	static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	@Override
	public List<String> tabs(final Visit v) {
		return List.of("repair");
	}

	private static boolean host(final ServerPlayer player) {
		HostSession s = HostSessions.of(player);
		return s != null && s.state() != HostSession.State.NONE;
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		int eddies = Eddies.shown(v.player);
		boolean creative = v.player.isCreative();
		List<NpcWire.Row> rows = new ArrayList<>();
		if (v.role.offers("repair_vehicle") && host(v.player)) {
			HostSession s = HostSessions.of(v.player);
			rows.add(new NpcWire.Row("vehicle", "minecraft:minecart", 1, "", "", "", v.role.repairVehicle(), "repair_vehicle",
				s != null && s.active() && (v.role.repairVehicle() <= eddies || creative)));
		}

		if (v.role.offers("repair_gear")) {
			for (EquipmentSlot slot : WORN) {
				ItemStack stack = v.player.getItemBySlot(slot);
				if (!stack.isEmpty() && stack.isDamageableItem()) {
					rows.add(new NpcWire.Row("slot." + slot.getName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 1, "", "", "",
						v.role.repairGear(), "repair", stack.isDamaged() && (v.role.repairGear() <= eddies || creative)));
				}
			}
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		if (row.equals("vehicle") && v.role.offers("repair_vehicle")) {
			return vehicle(v);
		}

		for (EquipmentSlot slot : WORN) {
			if (row.equals("slot." + slot.getName()) && v.role.offers("repair_gear")) {
				ItemStack stack = v.player.getItemBySlot(slot);
				if (stack.isEmpty() || !stack.isDamaged()) {
					return "none";
				}

				String item = stack.getHoverName().getString();
				if (!Eddies.pay(v.player, v.role.repairGear())) {
					v.say("no_funds", "item", item, "price", Integer.toString(v.role.repairGear()));
					return "poor";
				}

				stack.setDamageValue(0);
				v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6F, 1.2F);
				Cyberblock.LOG.info("npc: {} had {} repaired for {} eddies", v.player.getPlainTextName(), slot.getName(), v.role.repairGear());
				v.say("repaired", "item", item, "price", Integer.toString(v.role.repairGear()));
				return "repaired";
			}
		}

		return "gone";
	}

	/** The host's Cyberpunk car: the session check, then the DOWN request (each path logged). */
	private static String vehicle(final Visit v) {
		HostSession s = HostSessions.of(v.player);
		String check = vehicleCheck(s != null && s.state() != HostSession.State.NONE, s != null && s.active());
		if (!check.isEmpty()) {
			Cyberblock.LOG.info("npc: mechanic: {} cannot have a Cyberpunk car repaired ({}; fallback: armour and elytra only)", v.player.getPlainTextName(), check);
			return check;
		}

		if (!Eddies.pay(v.player, v.role.repairVehicle())) {
			v.say("no_funds", "price", Integer.toString(v.role.repairVehicle()));
			return "poor";
		}

		int id = s.newRequest(v.player.getUUID());
		s.send(Services.repair(id));
		Cyberblock.LOG.info("npc: mechanic: {} car repair {} sent to Cyberpunk (repair DOWN, {} eddies; the scripts check it and log their path)",
			v.player.getPlainTextName(), id, v.role.repairVehicle());
		v.say("repaired", "price", Integer.toString(v.role.repairVehicle()));
		return "repair_sent";
	}

	/** Why a host's car cannot be repaired now ("" when it can): not a host, Cyberpunk not On. Pure. */
	static String vehicleCheck(final boolean host, final boolean active) {
		return !host ? "not_host" : !active ? "not_active" : "";
	}
}
