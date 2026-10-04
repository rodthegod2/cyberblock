package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Ripperdoc;
import dev.cyberblock.net.ServerNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jspecify.annotations.Nullable;

/**
 * The ripperdoc's and the medic's screens (megaplan task 7-N1). Ripperdoc: the {@code ripperdoc} catalogue (the role's
 * {@code implants}, or all of it) with Minecraft eddie prices, installed through the existing install (cyber/Ripperdoc; a player who
 * carries the implant pays nothing); an implant with a {@code risk} is a surgery: the timing bar (Surgery), paid only when it works,
 * and a failure costs health (never a life) and a 30 s debuff during which the ripperdoc will not operate again. Medic: heals Steve
 * to full for {@code heal_price} per missing heart. Server thread.
 */
final class Clinic {
	/** A surgery whose bar is running on the player's screen. */
	record Pending(int npc, Implant implant, double lo, double hi, int period, long sentMs) {
	}

	private final Map<UUID, Pending> pending = new HashMap<>();
	/** Until when (ms) a failed surgery's patient must recover before the next. */
	private final Map<UUID, Long> recovering = new HashMap<>();

	/** The implants this ripperdoc installs, in content id order. */
	static List<Implant> catalogue(final NpcRole role) {
		Map<Identifier, Implant> all = NpcContent.implants();
		if (role.implants().isEmpty()) {
			return List.copyOf(all.values());
		}

		List<Implant> out = new ArrayList<>();
		for (Identifier id : role.implants()) {
			Implant i = all.get(id);
			if (i != null) {
				out.add(i);
			}
		}

		return out;
	}

	private boolean recovering(final ServerPlayer player) {
		Long until = this.recovering.get(player.getUUID());
		return until != null && System.currentTimeMillis() < until;
	}

	final Visit.Service ripperdoc = new Visit.Service() {
		@Override
		public List<String> tabs(final Visit v) {
			return List.of("implants");
		}

		@Override
		public List<NpcWire.Row> rows(final Visit v) {
			Set<String> installed = CyberData.installedIds(v.player);
			int eddies = Eddies.shown(v.player);
			boolean wait = Clinic.this.recovering(v.player);
			List<NpcWire.Row> rows = new ArrayList<>();
			for (Implant i : catalogue(v.role)) {
				String id = i.cyberware().id();
				boolean in = installed.contains(id);
				int price = Ripperdoc.owns(v.player, i.cyberware()) ? 0 : i.price();
				boolean on = !in && !wait && (price <= eddies || v.player.isCreative());
				rows.add(new NpcWire.Row("i." + id, Cyberblock.ID + ":" + id, 1, "", "", price, in ? "installed" : i.surgery() ? "surgery" : "install", on));
			}

			return rows;
		}

		@Override
		public String press(final Visit v, final String row) {
			Implant implant = null;
			for (Implant i : catalogue(v.role)) {
				if (row.equals("i." + i.cyberware().id())) {
					implant = i;
				}
			}

			if (implant == null) {
				return "gone";
			}

			if (CyberData.installedIds(v.player).contains(implant.cyberware().id())) {
				return "installed";
			}

			if (Clinic.this.recovering(v.player)) {
				return "recovering";
			}

			int price = price(v, implant);
			if (!v.player.isCreative() && Eddies.balance(v.player) < price) {
				v.say("poor");
				return "poor";
			}

			if (implant.surgery()) {
				Clinic.this.startSurgery(v, implant);
				return "";
			}

			return Clinic.this.install(v, implant, price) ? "ok" : "installed";
		}
	};

	private static int price(final Visit v, final Implant implant) {
		return Ripperdoc.owns(v.player, implant.cyberware()) ? 0 : implant.price();
	}

	private boolean install(final Visit v, final Implant implant, final int price) {
		if (!Eddies.pay(v.player, price)) {
			return false;
		}

		boolean in = Ripperdoc.installAtRipperdoc(v.player, implant.cyberware());
		Cyberblock.LOG.info("npc: {} had {} installed at {} for {} eddies", v.player.getPlainTextName(), implant.cyberware().id(), v.roleId, price);
		v.say("thanks");
		return in;
	}

	private void startSurgery(final Visit v, final Implant implant) {
		double[] zone = Surgery.zone(implant.risk(), ThreadLocalRandom.current());
		int period = Surgery.period(implant.risk());
		Pending p = new Pending(v.npc.getId(), implant, zone[0], zone[1], period, System.currentTimeMillis());
		this.pending.put(v.player.getUUID(), p);
		Cyberblock.LOG.info("npc: surgery for {} on {} (risk {}, zone {}..{}, {} ms)", v.player.getPlainTextName(), implant.cyberware().id(),
			implant.risk(), String.format("%.2f", zone[0]), String.format("%.2f", zone[1]), period);
		ServerNet.ui(v.player, NpcWire.T_SURGERY, NpcWire.surgery(new NpcWire.Surgery(p.npc(), implant.cyberware().id(), (float) zone[0],
			(float) zone[1], period, Surgery.TIMEOUT_MS)));
	}

	/** The player cut (or closed the bar: abort). The result code, null when no surgery of this NPC was running. */
	@Nullable String cut(final Visit v, final boolean abort) {
		Pending p = this.pending.remove(v.player.getUUID());
		if (p == null || p.npc() != v.npc.getId()) {
			return null;
		}

		if (abort) {
			return "cancelled";
		}

		long now = System.currentTimeMillis();
		int ping = v.player.connection.latency();
		long t = Surgery.elapsed(p.sentMs(), now, ping);
		double at = Surgery.position(t, p.period());
		boolean late = t > Surgery.TIMEOUT_MS + 2000L;
		boolean ok = !late && Surgery.hit(at, p.lo(), p.hi());
		Cyberblock.LOG.info("npc: surgery cut by {} at {} ({} ms, ping {} ms; zone {}..{}) -> {}", v.player.getPlainTextName(), String.format("%.3f", at), t, ping,
			String.format("%.2f", p.lo()), String.format("%.2f", p.hi()), ok ? "ok" : "failed");
		if (ok) {
			int price = price(v, p.implant());
			if (!this.install(v, p.implant(), price)) {
				v.say("poor");
				return "poor";
			}

			v.say("surgery_ok");
			return "surgery_ok";
		}

		this.fail(v.player, p.implant());
		v.say("surgery_fail");
		return "surgery_fail";
	}

	/** A failed surgery: health lost (never death), a 30 s debuff, no implant and nothing paid. */
	private void fail(final ServerPlayer player, final Implant implant) {
		float damage = Surgery.damage(implant.risk(), player.getHealth());
		if (damage > 0.0F) {
			player.setHealth(player.getHealth() - damage);
		}

		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.8F);
		player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, Surgery.DEBUFF_TICKS, 0));
		player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Surgery.DEBUFF_TICKS, 0));
		player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0));
		this.recovering.put(player.getUUID(), System.currentTimeMillis() + Surgery.DEBUFF_TICKS * 50L);
		Cyberblock.LOG.info("npc: surgery on {} failed: -{} health, 30 s debuff", player.getPlainTextName(), damage);
	}

	final Visit.Service medic = new Visit.Service() {
		@Override
		public List<String> tabs(final Visit v) {
			return List.of("heal");
		}

		@Override
		public List<NpcWire.Row> rows(final Visit v) {
			int hearts = hearts(v.player.getMaxHealth() - v.player.getHealth());
			int price = hearts * v.role.healPrice();
			return List.of(new NpcWire.Row("heal", "minecraft:golden_apple", Math.min(hearts, 64), "", "", price, "heal",
				hearts > 0 && (price <= Eddies.shown(v.player) || v.player.isCreative())));
		}

		@Override
		public String press(final Visit v, final String row) {
			int hearts = hearts(v.player.getMaxHealth() - v.player.getHealth());
			if (!row.equals("heal") || hearts == 0) {
				return "none";
			}

			int price = hearts * v.role.healPrice();
			if (!Eddies.pay(v.player, price)) {
				v.say("poor");
				return "poor";
			}

			v.player.setHealth(v.player.getMaxHealth());
			v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.6F);
			Cyberblock.LOG.info("npc: {} healed {} hearts for {} eddies", v.player.getPlainTextName(), hearts, price);
			v.say("heal");
			return "healed";
		}
	};

	/** Whole hearts for this much missing health (a half heart counts as one). */
	static int hearts(final float missing) {
		return missing <= 0.01F ? 0 : (int) Math.ceil(missing / 2.0F - 0.005F);
	}

	void forget(final UUID player) {
		this.pending.remove(player);
		this.recovering.remove(player);
	}

	void clear() {
		this.pending.clear();
		this.recovering.clear();
	}
}
