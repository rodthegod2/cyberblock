// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (MobWar)
package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.mixin.MobAccessor;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 3: Minecraft's mobs against Night City. Hostile mobs hunt the pedestrians' proxies (ProxyTargetGoal) and their hits
 * reach the NPC as {"t":"hit"} with "mob" (ProxyEntity); each active host keeps a puppet for every fighter near its Steve
 * ({"t":"mobs"}, 10 Hz) and sends the puppets' damage back ({"t":"mobdmg"}). Fighters are held off Night City's unknown ground
 * (MobRules.held, around the nearest host's Steve). Server thread only, except where noted.
 */
public final class MobWar {
	private static final EntityTypeTest<Entity, Mob> MOBS = EntityTypeTest.forClass(Mob.class);
	/** The follow range a fighter gets on top of its own, so that it can hunt proxies from MobRules.RANGE away (never saved). */
	private static final Identifier PROXY_RANGE = Identifier.fromNamespaceAndPath(Cyberblock.ID, "proxy_range");
	/** The fighters held right now. Read from every living entity's tick (HeldMobMixin), on both sides: concurrent. */
	private static final Set<LivingEntity> held = ConcurrentHashMap.newKeySet();
	private static int ticks;
	/** Hosts whose last mobs list had someone in it: one empty list follows when the last one goes. */
	private static final Set<UUID> reported = new HashSet<>();
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
		// a transient modifier, not a new base value: the mob's saved attributes stay as vanilla wrote them
		AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
		double boost = range == null ? 0.0 : MobRules.followRangeBoost(range.getBaseValue());
		if (boost > 0.0) {
			range.addOrUpdateTransientModifier(new AttributeModifier(PROXY_RANGE, boost, AttributeModifier.Operation.ADD_VALUE));
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
		ServerLevel level = s.overworld();
		// each active host with its Steve in this world (a session whose player is not online this tick is skipped)
		Map<HostSession, ServerPlayer> hosts = new LinkedHashMap<>();
		for (HostSession h : HostSessions.active()) {
			ServerPlayer steve = h.player();
			if (steve != null && steve.level() == level) {
				hosts.put(h, steve);
			}
		}

		reported.removeIf(owner -> hosts.keySet().stream().noneMatch(h -> h.uuid().equals(owner))); // a host drops the list by itself when it gets old
		if (hosts.isEmpty()) {
			return;
		}

		boolean report = ++ticks % 2 == 0;
		Map<HostSession, List<MobRules.Seen>> seen = new HashMap<>();
		for (Mob mob : level.getEntities(MOBS, mob -> mob.isAlive() && fighter(mob))) {
			hold(level, mob, nearest(hosts.values(), mob));
			if (!report) {
				continue;
			}

			for (Map.Entry<HostSession, ServerPlayer> h : hosts.entrySet()) {
				double distanceSq = h.getValue().distanceToSqr(mob);
				if (distanceSq <= MobRules.RANGE * MobRules.RANGE) {
					seen.computeIfAbsent(h.getKey(), k -> new ArrayList<>()).add(new MobRules.Seen(mob.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(),
						mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getBbWidth(), mob.getBbHeight(), mob.getHealth(), mob.getMaxHealth(), distanceSq));
				}
			}
		}

		if (!report) {
			return;
		}

		for (HostSession h : hosts.keySet()) {
			List<MobRules.Seen> list = seen.getOrDefault(h, List.of());
			if (!list.isEmpty() || reported.contains(h.uuid())) {
				h.send(Wire.mobs(MobRules.nearest(list)));
				if (list.isEmpty()) {
					reported.remove(h.uuid());
				} else {
					reported.add(h.uuid());
				}
			}
		}
	}

	/** The Steve (of the hosts' players, at least one) nearest to the mob, horizontally (the probed ground is around him). */
	private static ServerPlayer nearest(final Collection<ServerPlayer> steves, final Mob mob) {
		ServerPlayer best = steves.iterator().next();
		double bestSq = Double.MAX_VALUE;
		for (ServerPlayer p : steves) {
			double dx = p.getX() - mob.getX(), dz = p.getZ() - mob.getZ();
			if (dx * dx + dz * dz < bestSq) {
				best = p;
				bestSq = dx * dx + dz * dz;
			}
		}

		return best;
	}

	/**
	 * Holds a fighter that left the probed ground, and frees it once it is back in range of it. A mob held mid-hunt forgets the NPC
	 * it was after (the goal does not run while it is held, and the NPC may be far outside the probed ground by the time it is free).
	 */
	private static void hold(final ServerLevel level, final Mob mob, final ServerPlayer steve) {
		if (MobRules.held(mob.getX() - steve.getX(), mob.getZ() - steve.getZ(), groundBelow(level, mob.blockPosition()))) {
			if (held.add(mob)) {
				mob.getNavigation().stop();
				if (mob.getTarget() instanceof ProxyEntity) {
					mob.setTarget(null);
				}
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
		reported.clear();
	}

	/** A host's session ended: no mob hunts its proxies any more (they are going). */
	static void end(final UUID owner) {
		reported.remove(owner);
		MinecraftServer s = WorldBridge.server();
		if (s == null) {
			return;
		}

		for (Mob mob : s.overworld().getEntities(MOBS, mob -> mob.getTarget() instanceof ProxyEntity p && owner.equals(p.owner()))) {
			mob.setTarget(null);
		}
	}

	/** {"t":"mobdmg"} from a host: one of its puppets was hurt. */
	static void damage(final HostSession host, final JsonObject message) {
		MobRules.Damage d = MobRules.parseDamage(message);
		if (d == null) {
			if (bad++ % 100 == 0) {
				Cyberblock.LOG.warn("ignoring a bad mobdmg message (seen {} times)", bad);
			}

			return;
		}

		MinecraftServer s = WorldBridge.server();
		if (s != null && d.frac() > 0.0F && host.active()) {
			hurt(s.overworld(), host.uuid(), d);
		}
	}

	/**
	 * A puppet in Night City was hurt: the mob loses frac of its max health, every hit counting (automatic fire lands several
	 * inside vanilla's cooldown). Hurt by the NPC's proxy when there is one, so the mob turns on that NPC; else generic damage.
	 */
	private static void hurt(final ServerLevel level, final UUID owner, final MobRules.Damage d) {
		if (!(level.getEntity(d.id()) instanceof Mob mob) || !mob.isAlive() || !fighter(mob)) {
			return;
		}

		DamageSource source = d.by() != 0L && ProxyManager.byId(owner, d.by()) instanceof LivingEntity proxy
			? level.damageSources().mobAttack(proxy) : level.damageSources().generic();
		mob.damageCooldownTime = 0;
		mob.hurtServer(level, source, d.frac() * mob.getMaxHealth());
	}
}
