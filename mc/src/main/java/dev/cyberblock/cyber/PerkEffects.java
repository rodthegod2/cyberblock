package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The perks' Minecraft effects that are not mixins (task C7): attribute modifiers, the Sandevistan, optical camo, cold
 * blood and smart arrows. Server thread.
 */
public final class PerkEffects {
	private static final Identifier IRON_SKIN = Identifier.fromNamespaceAndPath(Cyberblock.ID, "iron_skin");
	private static final Identifier JUGGERNAUT = Identifier.fromNamespaceAndPath(Cyberblock.ID, "juggernaut");
	private static final Map<UUID, PerkRules.Camo> camo = new HashMap<>();
	private static long sandevistanFiredMs = Long.MIN_VALUE;
	/** Wall time the slowed tick rate ends, or 0 when it is not slowed. */
	private static long sandevistanEndsMs;

	private PerkEffects() {
	}

	/** Every 20 ticks, on respawn and on purchase: the attribute modifiers match the perks (transient: never saved). */
	public static void apply(final ServerPlayer player) {
		modifier(player, Attributes.MAX_HEALTH, IRON_SKIN, 8.0, CyberData.has(player, Perk.IRON_SKIN));
		modifier(player, Attributes.KNOCKBACK_RESISTANCE, JUGGERNAUT, 1.0, CyberData.has(player, Perk.JUGGERNAUT));
	}

	private static void modifier(final ServerPlayer player, final Holder<Attribute> attribute, final Identifier id, final double amount, final boolean on) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}

		if (on && !instance.hasModifier(id)) {
			instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
		} else if (!on && instance.hasModifier(id)) {
			instance.removeModifier(id);
			if (player.getHealth() > player.getMaxHealth()) {
				player.setHealth(player.getMaxHealth());
			}
		}
	}

	/** Every server tick. */
	static void tick(final MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (sandevistanEndsMs != 0L && (now >= sandevistanEndsMs || !Cyberblock.active)) {
			endSandevistan(server);
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.tickCount % 20 == 0) {
				apply(player);
			}

			opticalCamo(player, now);
		}
	}

	/** A double-tapped sprint (from the host's `key sprint`): Speed II and half-speed ticks for 5 s, `perkfx` for Cyberpunk's slow-mo. */
	public static void sandevistan(final MinecraftServer server, final ServerPlayer player) {
		long now = System.currentTimeMillis();
		if (!CyberData.has(player, Perk.SANDEVISTAN) || !PerkRules.sandevistanReady(now, sandevistanFiredMs)) {
			return;
		}

		sandevistanFiredMs = now;
		sandevistanEndsMs = now + PerkRules.SANDEVISTAN_MS;
		// the 5 s run at the slow tick rate: that many ticks
		int ticks = Math.round(PerkRules.SANDEVISTAN_MS / 1000.0F * PerkRules.SLOW_TICK_RATE);
		player.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
		server.tickRateManager().setTickRate(PerkRules.SLOW_TICK_RATE);
		Cyberblock.events.accept(CyberWire.perkfx("sandevistan", PerkRules.SANDEVISTAN_MS / 1000.0));
	}

	/** Back to normal ticks (the 5 s are over, the host went, the player left or the server stops). */
	public static void endSandevistan(final MinecraftServer server) {
		if (sandevistanEndsMs != 0L) {
			sandevistanEndsMs = 0L;
			server.tickRateManager().setTickRate(PerkRules.NORMAL_TICK_RATE);
		}
	}

	private static void opticalCamo(final ServerPlayer player, final long now) {
		if (!CyberData.has(player, Perk.OPTICAL_CAMO)) {
			camo.remove(player.getUUID());
			return;
		}

		boolean crouched = Bridge.hostCrouching || player.isShiftKeyDown();
		if (camo.computeIfAbsent(player.getUUID(), id -> new PerkRules.Camo()).update(crouched, player.getX(), player.getY(), player.getZ(), now)) {
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, PerkRules.CAMO_TICKS, 0, false, false, true));
		}
	}

	/** Cyberpunk reports a kill (`killed`): cold blood gives Speed I for 10 s. */
	public static void coldBlood(final ServerPlayer player) {
		if (CyberData.has(player, Perk.COLD_BLOOD)) {
			player.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));
		}
	}

	/** Smart arrows, every tick of a flying arrow of a player with the perk: turn toward the nearest proxy in the cone. */
	public static void steer(final ServerLevel level, final AbstractArrow arrow) {
		Vec3 v = arrow.getDeltaMovement();
		Vec3 at = arrow.position();
		ProxyEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (ProxyEntity proxy : level.getEntitiesOfClass(ProxyEntity.class, arrow.getBoundingBox().inflate(PerkRules.HOMING_RANGE), e -> !e.isRemoved())) {
			Vec3 d = centre(proxy).subtract(at);
			double distance = d.lengthSqr();
			if (distance < bestDistance && PerkRules.inCone(v.x, v.y, v.z, d.x, d.y, d.z)) {
				best = proxy;
				bestDistance = distance;
			}
		}

		if (best != null) {
			Vec3 d = centre(best).subtract(at);
			double[] steered = PerkRules.steer(v.x, v.y, v.z, d.x, d.y, d.z);
			arrow.setDeltaMovement(steered[0], steered[1], steered[2]);
			arrow.needsSync = true;
		}
	}

	private static Vec3 centre(final ProxyEntity proxy) {
		return proxy.position().add(0.0, proxy.getBbHeight() / 2.0, 0.0);
	}

	/** The player left or the server stops: forget per-player state and restore the tick rate. */
	static void reset(final @Nullable MinecraftServer server) {
		camo.clear();
		if (server != null) {
			endSandevistan(server);
		}
	}
}
