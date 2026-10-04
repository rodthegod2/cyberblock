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
 * prices.repair_gear} each. {@code repair_vehicle}, for a host: shown greyed as not available yet. Cyberpunk does not answer a car
 * repair on the wire (the DLL has no repair parser yet, and 7S's script can refuse one: no car of V's near, a destroyed car, a repair
 * already running), so nothing is sent and nothing is charged until a repair DOWN and its answer UP have section 1.9 rows; the server
 * logs that at start, and the gear repair is the fallback. Server thread.
 */
final class Garage implements Visit.Service {
	static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** The car row's second line while Cyberpunk cannot confirm a repair. */
	static final String CAR_UNAVAILABLE = "Not available yet: your Cyberpunk game cannot confirm a car repair. Armour and elytra repairs work.";

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
			rows.add(new NpcWire.Row("vehicle", "minecraft:minecart", 1, "", "", CAR_UNAVAILABLE, -1, "repair_vehicle", false));
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
			// not available yet: nothing sent to Cyberpunk, nothing charged (said once in the log at server start)
			return "unavailable";
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
}
