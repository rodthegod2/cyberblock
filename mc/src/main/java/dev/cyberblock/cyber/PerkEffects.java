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

/**
 * The perks' Minecraft effects that are not mixins (task C7): attribute modifiers, optical camo's re-cloak, cold blood and
 * smart arrows. Plan retest2 section 1.7: the Sandevistan, double jump and optical camo perks now upgrade their implant
 * (the Sandevistan itself is CyberwareEffects'). Server thread.
 */
public final class PerkEffects {
	private static final Identifier IRON_SKIN = Identifier.fromNamespaceAndPath(Cyberblock.ID, "iron_skin");
	private static final Identifier JUGGERNAUT = Identifier.fromNamespaceAndPath(Cyberblock.ID, "juggernaut");
	private static final Identifier DOUBLE_JUMP = Identifier.fromNamespaceAndPath(Cyberblock.ID, "double_jump");
	/** double_jump with any legs implant: this much more safe fall distance. */
	private static final double DOUBLE_JUMP_SAFE_FALL = 4.0;
	private static final Map<UUID, PerkRules.Camo> camo = new HashMap<>();

	private PerkEffects() {
	}

	/** Every 20 ticks, on respawn, on purchase and on every install: the attribute modifiers match the perks (transient: never saved). */
	public static void apply(final ServerPlayer player) {
		modifier(player, Attributes.MAX_HEALTH, IRON_SKIN, 8.0, CyberData.has(player, Perk.IRON_SKIN));
		modifier(player, Attributes.KNOCKBACK_RESISTANCE, JUGGERNAUT, 1.0, CyberData.has(player, Perk.JUGGERNAUT));
		boolean legs = CyberData.installed(player).stream().anyMatch(c -> c.slot() == CyberSlot.LEGS);
		modifier(player, Attributes.SAFE_FALL_DISTANCE, DOUBLE_JUMP, DOUBLE_JUMP_SAFE_FALL, legs && CyberData.has(player, Perk.DOUBLE_JUMP));
	}

	/** Adds or removes one transient modifier (max health is capped when it shrinks). */
	static void modifier(final ServerPlayer player, final Holder<Attribute> attribute, final Identifier id, final double amount, final boolean on) {
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
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.tickCount % 20 == 0) {
				apply(player);
			}

			opticalCamo(player, now);
		}
	}

	/** The optical_camo perk re-cloaks after 2 s crouched and still, only with the optical camo implant installed. */
	private static void opticalCamo(final ServerPlayer player, final long now) {
		if (!CyberData.has(player, Perk.OPTICAL_CAMO) || !CyberData.has(player, Cyberware.OPTICAL_CAMO)) {
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

	/** The player left or the server stops: forget per-player state. */
	static void reset() {
		camo.clear();
	}
}
