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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jspecify.annotations.Nullable;

/**
 * The ripperdoc's and the medic's screens (megaplan task 7-N1). Ripperdoc: the role's {@code ripperdoc} catalogue with Minecraft
 * eddie prices ({@code install_implant}: the plain implants; {@code surgery}: the ones with a surgery), installed through the existing
 * install (cyber/Ripperdoc; a player who carries the implant pays nothing). A surgery is the timing bar (Surgery), paid only when it
 * works; a miss costs the catalogue's damage (never a life) and its debuff, and the ripperdoc will not operate again until the debuff
 * is over. Medic: Steve healed to full for {@code heal_per_heart} per missing heart (Steve's health is the only health, so nothing
 * goes DOWN: section 1.9 has no heal message). Server thread.
 */
final class Clinic {
	/** A surgery whose bar is running on the player's screen. */
	record Pending(int npc, Catalogue.Offer offer, Catalogue catalogue, double lo, double hi, int period, long sentMs) {
	}

	private final Map<UUID, Pending> pending = new HashMap<>();
	/** Until when (ms) a failed surgery's patient must recover before the next. */
	private final Map<UUID, Long> recovering = new HashMap<>();

	/** The implants this ripperdoc installs: the plain ones with install_implant, the surgeries with surgery. */
	static List<Catalogue.Offer> offers(final NpcRole role) {
		Catalogue c = NpcContent.catalogue(role);
		if (c == null) {
			return List.of();
		}

		return c.offers().stream().filter(o -> role.offers(o.surgery() ? "surgery" : "install_implant")).toList();
	}

	private boolean recovering(final ServerPlayer player) {
		Long until = this.recovering.get(player.getUUID());
		return until != null && System.currentTimeMillis() < until;
	}

	private static int price(final ServerPlayer player, final Catalogue.Offer offer) {
		return Ripperdoc.owns(player, offer.cyberware()) ? 0 : offer.price();
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
			for (Catalogue.Offer o : offers(v.role)) {
				String id = o.cyberware().id();
				boolean in = installed.contains(id);
				int price = price(v.player, o);
				boolean on = !in && !wait && (price <= eddies || v.player.isCreative());
				rows.add(new NpcWire.Row("i." + id, Cyberblock.ID + ":" + id, 1, "", "", "", price, in ? "installed" : o.surgery() ? "surgery" : "install", on));
			}

			return rows;
		}

		@Override
		public String press(final Visit v, final String row) {
			Catalogue.Offer offer = offers(v.role).stream().filter(o -> row.equals("i." + o.cyberware().id())).findFirst().orElse(null);
			Catalogue catalogue = NpcContent.catalogue(v.role);
			if (offer == null || catalogue == null) {
				return "gone";
			}

			if (CyberData.installedIds(v.player).contains(offer.cyberware().id())) {
				return "installed";
			}

			if (Clinic.this.recovering(v.player)) {
				return "recovering";
			}

			int price = price(v.player, offer);
			if (!v.player.isCreative() && Eddies.balance(v.player) < price) {
				v.say("no_funds", "implant", Ripperdoc.name(offer.cyberware()).getString(), "price", Integer.toString(price));
				return "poor";
			}

			if (offer.surgery()) {
				Clinic.this.startSurgery(v, offer, catalogue);
				return "";
			}

			return Clinic.this.install(v, offer) ? "ok" : "installed";
		}
	};

	private boolean install(final Visit v, final Catalogue.Offer offer) {
		int price = price(v.player, offer);
		if (!Eddies.pay(v.player, price)) {
			return false;
		}

		boolean in = Ripperdoc.installAtRipperdoc(v.player, offer.cyberware());
		Cyberblock.LOG.info("npc: {} had {} installed by {} for {} eddies", v.player.getPlainTextName(), offer.cyberware().id(), v.roleId, price);
		v.say("installed", "implant", Ripperdoc.name(offer.cyberware()).getString(), "price", Integer.toString(price));
		return in;
	}

	private void startSurgery(final Visit v, final Catalogue.Offer offer, final Catalogue catalogue) {
		double[] zone = Surgery.zone(offer.window());
		int period = Surgery.periodMs(offer.periodTicks());
		Pending p = new Pending(v.npc.getId(), offer, catalogue, zone[0], zone[1], period, System.currentTimeMillis());
		this.pending.put(v.player.getUUID(), p);
		Cyberblock.LOG.info("npc: surgery for {} on {} (window {} %, {} ms)", v.player.getPlainTextName(), offer.cyberware().id(), offer.window(), period);
		ServerNet.ui(v.player, NpcWire.T_SURGERY, NpcWire.surgery(new NpcWire.Surgery(p.npc(), offer.cyberware().id(), (float) zone[0], (float) zone[1],
			period, Surgery.TIMEOUT_MS)));
	}

	/** The player cut (or closed the bar: abort). The result code, null when no surgery of this NPC was running. */
	@Nullable String cut(final Visit v, final boolean abort) {
		Pending p = this.pending.remove(v.player.getUUID());
		if (p == null || p.npc() != v.npc.getId()) {
			return null;
		}

		if (abort) {
			Cyberblock.LOG.info("npc: surgery on {} called off", v.player.getPlainTextName());
			return "cancelled";
		}

		long now = System.currentTimeMillis();
		int ping = v.player.connection.latency();
		long t = Surgery.elapsed(p.sentMs(), now, ping);
		double at = Surgery.position(t, p.period());
		boolean ok = t <= Surgery.TIMEOUT_MS + 2000L && Surgery.hit(at, p.lo(), p.hi());
		Cyberblock.LOG.info("npc: surgery cut by {} at {} ({} ms, ping {} ms; zone {}..{}) -> {}", v.player.getPlainTextName(), String.format("%.3f", at), t, ping,
			String.format("%.2f", p.lo()), String.format("%.2f", p.hi()), ok ? "ok" : "failed");
		String implant = Ripperdoc.name(p.offer().cyberware()).getString();
		if (ok) {
			if (!this.install(v, p.offer())) {
				v.say("no_funds", "implant", implant, "price", Integer.toString(price(v.player, p.offer())));
				return "poor";
			}

			v.say("surgery_ok", "implant", implant);
			return "surgery_ok";
		}

		this.fail(v.player, p.catalogue());
		v.say("surgery_fail", "implant", implant);
		return "surgery_fail";
	}

	/** A missed cut: the catalogue's damage (never below half a heart), its debuff; no implant and nothing paid. */
	private void fail(final ServerPlayer player, final Catalogue catalogue) {
		float damage = Surgery.damage(catalogue.failDamage(), player.getHealth());
		if (damage > 0.0F) {
			player.setHealth(player.getHealth() - damage);
		}

		int ticks = catalogue.debuffSeconds() * 20;
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.8F);
		if (ticks > 0) {
			player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0));
			player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 0));
			this.recovering.put(player.getUUID(), System.currentTimeMillis() + ticks * 50L);
		}

		Cyberblock.LOG.info("npc: surgery on {} failed: -{} health, {} s debuff", player.getPlainTextName(), damage, catalogue.debuffSeconds());
	}

	final Visit.Service medic = new Visit.Service() {
		@Override
		public List<String> tabs(final Visit v) {
			return List.of("heal");
		}

		@Override
		public List<NpcWire.Row> rows(final Visit v) {
			int hearts = hearts(v.player.getMaxHealth() - v.player.getHealth());
			int price = hearts * v.role.healPerHeart();
			return List.of(new NpcWire.Row("heal", "minecraft:golden_apple", hearts, "", "", "", price, "heal",
				hearts > 0 && (price <= Eddies.shown(v.player) || v.player.isCreative())));
		}

		@Override
		public String press(final Visit v, final String row) {
			int hearts = hearts(v.player.getMaxHealth() - v.player.getHealth());
			if (!row.equals("heal") || hearts == 0 || v.player.isDeadOrDying()) {
				return "none";
			}

			int price = hearts * v.role.healPerHeart();
			if (!Eddies.pay(v.player, price)) {
				v.say("no_funds", "price", Integer.toString(price));
				return "poor";
			}

			v.player.setHealth(v.player.getMaxHealth());
			v.player.level().playSound(null, v.player.getX(), v.player.getY(), v.player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.6F);
			Cyberblock.LOG.info("npc: {} healed {} hearts for {} eddies", v.player.getPlainTextName(), hearts, price);
			v.say("healed", "price", Integer.toString(price));
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
