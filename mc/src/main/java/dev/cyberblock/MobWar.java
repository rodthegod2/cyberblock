// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (MobWar)
package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.mixin.MobAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 3: Minecraft's mobs against Night City. Hostile mobs hunt the pedestrians' proxies (ProxyTargetGoal) and their hits
 * reach the NPC as {"t":"hit"} with "mob" (ProxyEntity); the host keeps a puppet for every fighter near Steve ({"t":"mobs"},
 * 10 Hz) and sends the puppets' damage back ({"t":"mobdmg"}). Fighters are held off Night City's unknown ground (MobRules.held).
 * Server thread only, except where noted.
 */
public final class MobWar {
	private static final EntityTypeTest<Entity, Mob> MOBS = EntityTypeTest.forClass(Mob.class);
	/** The fighters held right now. Read from every living entity's tick (HeldMobMixin), on both sides: concurrent. */
	private static final Set<LivingEntity> held = ConcurrentHashMap.newKeySet();
	private static int ticks;
	/** The last mobs list had someone in it: one empty list follows when the last one goes. */
	private static boolean reported;
	private static int bad;

	private MobWar() {
	}

	/** Hostile and goal-driven: it joins the fight. */
	public static boolean fighter(final Entity e) {
		return MobRules.fighter(e instanceof Mob, e instanceof Enemy, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
	}

	/** Any thread (HeldMobMixin): this server-side mob is held off unknown ground (entities are equal by id: the client's copy is not). */
	public static boolean isHeld(final LivingEntity e) {
		return !held.isEmpty() && !e.level().isClientSide() && held.contains(e);
	}

	/** Server thread, every entity that joins the world: fighters hunt proxies too, from at least 48 blocks. */
	static void onEntityLoad(final Entity e, final ServerLevel level) {
		if (!fighter(e)) {
			return;
		}

		Mob mob = (Mob) e;
		AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (range != null && range.getBaseValue() < MobRules.RANGE) {
			range.setBaseValue(MobRules.RANGE);
		}

		GoalSelector targets = ((MobAccessor) mob).cyberblock$targetSelector();
		if (targets.getAvailableGoals().stream().noneMatch(w -> w.getGoal() instanceof ProxyTargetGoal)) {
			// the same priority as vanilla's player target: whoever comes first is fought, and Steve still is as in vanilla
			targets.addGoal(2, new ProxyTargetGoal(mob));
		}
	}

	/** Every server tick. */
	static void tick(final MinecraftServer s) {
		held.removeIf(e -> e.isRemoved() || !e.isAlive());
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (!Cyberblock.active || players.isEmpty()) {
			reported = false; // the host drops the list by itself when it gets old
			return;
		}

		ServerLevel level = s.overworld();
		ServerPlayer steve = players.get(0);
		boolean report = ++ticks % 2 == 0;
		List<MobRules.Seen> seen = new ArrayList<>();
		for (Mob mob : level.getEntities(MOBS, mob -> mob.isAlive() && fighter(mob))) {
			hold(level, mob, steve);
			double distanceSq = steve.distanceToSqr(mob);
			if (report && distanceSq <= MobRules.RANGE * MobRules.RANGE) {
				seen.add(new MobRules.Seen(mob.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), mob.getX(), mob.getY(), mob.getZ(),
					mob.getYRot(), mob.getBbWidth(), mob.getBbHeight(), mob.getHealth(), mob.getMaxHealth(), distanceSq));
			}
		}

		if (report && (!seen.isEmpty() || reported)) {
			Cyberblock.events.accept(Wire.mobs(MobRules.nearest(seen)));
			reported = !seen.isEmpty();
		}
	}

	/** Holds a fighter that left the probed ground, and frees it once it is back in range of it. */
	private static void hold(final ServerLevel level, final Mob mob, final ServerPlayer steve) {
		if (MobRules.held(mob.getX() - steve.getX(), mob.getZ() - steve.getZ(), groundBelow(level, mob.blockPosition()))) {
			if (held.add(mob)) {
				mob.getNavigation().stop();
			}

			mob.setDeltaMovement(Vec3.ZERO);
		} else {
			held.remove(mob);
		}
	}

	private static boolean groundBelow(final ServerLevel level, final BlockPos feet) {
		BlockPos.MutableBlockPos pos = feet.mutable();
		for (int dy = 0; dy <= MobRules.GROUND_DEPTH; dy++) {
			if (!level.getBlockState(pos.setY(feet.getY() - dy)).isAir()) {
				return true;
			}
		}

		return false;
	}

	/** Server thread, as the world stops: nobody is held. */
	static void clear() {
		held.clear();
		reported = false;
	}

	/** Link thread: {"t":"mobdmg"}. */
	public static void damage(final JsonObject message) {
		MobRules.Damage d = MobRules.parseDamage(message);
		if (d == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("ignoring a bad mobdmg message (seen {} times)", bad);
			}

			return;
		}

		MinecraftServer s = WorldBridge.server();
		if (s != null && d.frac() > 0.0F) {
			s.execute(() -> hurt(s.overworld(), d));
		}
	}

	/**
	 * A puppet in Night City was hurt: the mob loses frac of its max health, every hit counting (automatic fire lands several
	 * inside vanilla's cooldown). Hurt by the NPC's proxy when there is one, so the mob turns on that NPC; else generic damage.
	 */
	private static void hurt(final ServerLevel level, final MobRules.Damage d) {
		if (!Cyberblock.active || !(level.getEntity(d.id()) instanceof Mob mob) || !mob.isAlive() || !fighter(mob)) {
			return;
		}

		DamageSource source = d.by() != 0L && ProxyManager.byId(d.by()) instanceof LivingEntity proxy
			? level.damageSources().mobAttack(proxy) : level.damageSources().generic();
		mob.damageCooldownTime = 0;
		mob.hurtServer(level, source, d.frac() * mob.getMaxHealth());
	}
}
