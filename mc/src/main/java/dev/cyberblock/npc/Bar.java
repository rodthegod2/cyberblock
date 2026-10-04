package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;

/**
 * The bartender's screen (megaplan task 7-N1): drinks from the role's {@code drinks} (a short potion effect each, paid in Minecraft
 * eddies) and rumours (a {@code rumour} line from {@code dialogue} content, free). Server thread.
 */
final class Bar implements Visit.Service {
	@Override
	public List<String> tabs(final Visit v) {
		return List.of("drinks", "rumours");
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		if (v.tab.equals("rumours")) {
			return List.of(new NpcWire.Row("ask", "minecraft:paper", 1, "", "", -1, "ask", true));
		}

		int eddies = Eddies.shown(v.player);
		List<NpcWire.Row> rows = new ArrayList<>();
		List<NpcRole.Drink> drinks = v.role.drinks();
		for (int i = 0; i < drinks.size(); i++) {
			NpcRole.Drink d = drinks.get(i);
			rows.add(new NpcWire.Row("d" + i, "minecraft:honey_bottle", 1, d.name(), "", d.price(), "drink", d.price() <= eddies || v.player.isCreative()));
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		if (row.equals("ask")) {
			v.say("rumour");
			return "";
		}

		int i = row.matches("d\\d{1,2}") ? Integer.parseInt(row.substring(1)) : -1;
		if (i < 0 || i >= v.role.drinks().size()) {
			return "gone";
		}

		NpcRole.Drink d = v.role.drinks().get(i);
		Holder<MobEffect> effect = effect(d);
		if (effect == null) {
			Cyberblock.LOG.warn("npc: drink {} names no effect {}", d.name(), d.effect());
			return "gone";
		}

		if (!Eddies.pay(v.player, d.price())) {
			v.say("poor");
			return "poor";
		}

		v.player.addEffect(new MobEffectInstance(effect, d.seconds() * 20, d.amp()));
		v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
		Cyberblock.LOG.info("npc: {} drank {} ({} {} s) for {} eddies", v.player.getPlainTextName(), d.name(), d.effect(), d.seconds(), d.price());
		v.say("thanks");
		return "drank";
	}

	private static @Nullable Holder<MobEffect> effect(final NpcRole.Drink d) {
		return BuiltInRegistries.MOB_EFFECT.get(d.effect()).<Holder<MobEffect>>map(h -> h).orElse(null);
	}
}
