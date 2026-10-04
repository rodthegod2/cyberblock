package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;

/**
 * The bartender's screen (megaplan task 7-N1): {@code drinks} (the role's drinks, a short potion effect each, paid in Minecraft
 * eddies; named by the lang key {@code cyberblock.drink.<key>}) and {@code rumours} (a {@code rumour} line of the role's dialogue,
 * free; the fixer's screen has the same tab). Server thread.
 */
final class Bar implements Visit.Service {
	@Override
	public List<String> tabs(final Visit v) {
		List<String> tabs = new ArrayList<>();
		if (v.role.offers("drinks")) {
			tabs.add("drinks");
		}

		if (v.role.offers("rumours")) {
			tabs.add("rumours");
		}

		return tabs;
	}

	/** The rumours tab: one row to ask. */
	static List<NpcWire.Row> rumourRows() {
		return List.of(new NpcWire.Row("ask", "minecraft:paper", 1, "", "", "", -1, "ask", true));
	}

	/** Asking for a rumour: the NPC says one (no result to show). */
	static String ask(final Visit v) {
		v.say("rumour");
		return "";
	}

	/** A drink's English name from its key ("neon_sour" → "Neon Sour"), the fallback of its lang key. */
	static String words(final String key) {
		StringBuilder b = new StringBuilder();
		for (String w : key.split("_")) {
			if (!w.isEmpty()) {
				b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}

		return b.toString();
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		if (v.tab.equals("rumours")) {
			return rumourRows();
		}

		int eddies = Eddies.shown(v.player);
		List<NpcWire.Row> rows = new ArrayList<>();
		List<NpcRole.Drink> drinks = v.role.drinks();
		for (int i = 0; i < drinks.size(); i++) {
			NpcRole.Drink d = drinks.get(i);
			rows.add(new NpcWire.Row("d" + i, "minecraft:honey_bottle", 1, "cyberblock.drink." + d.key(), words(d.key()), "", d.price(), "drink",
				d.price() <= eddies || v.player.isCreative()));
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		if (row.equals("ask") && v.role.offers("rumours")) {
			return ask(v);
		}

		int i = row.matches("d\\d{1,2}") ? Integer.parseInt(row.substring(1)) : -1;
		if (i < 0 || i >= v.role.drinks().size() || !v.role.offers("drinks")) {
			return "gone";
		}

		NpcRole.Drink d = v.role.drinks().get(i);
		Holder<MobEffect> effect = effect(d);
		if (effect == null) {
			Cyberblock.LOG.warn("npc: drink {} names no effect {}", d.key(), d.effect());
			return "gone";
		}

		if (!Eddies.pay(v.player, d.price())) {
			v.say("no_funds", "drink", words(d.key()), "price", Integer.toString(d.price()));
			return "poor";
		}

		v.player.addEffect(new MobEffectInstance(effect, d.seconds() * 20, d.amplifier()));
		v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
		Cyberblock.LOG.info("npc: {} drank {} ({} {} s) for {} eddies", v.player.getPlainTextName(), d.key(), d.effect(), d.seconds(), d.price());
		v.say("drink", "drink", words(d.key()), "price", Integer.toString(d.price()));
		return "drank";
	}

	private static @Nullable Holder<MobEffect> effect(final NpcRole.Drink d) {
		return BuiltInRegistries.MOB_EFFECT.get(d.effect()).<Holder<MobEffect>>map(h -> h).orElse(null);
	}
}
