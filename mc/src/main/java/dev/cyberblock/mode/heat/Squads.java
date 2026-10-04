package dev.cyberblock.mode.heat;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.mode.heat.HeatRules.Order;
import dev.cyberblock.mode.heat.HeatRules.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft squads Server Heat sends (megaplan 8-T1 and the Phase 0 (d) fallback): tough vanilla mobs, named and equipped as NCPD,
 * heavy units, drones and Max-Tac, that hunt Minecraft-only players in the replica (and hosts whose real wanted level did not follow).
 * Once a second {@link #reconcile} matches the live units to {@link HeatRules#plan}'s orders: missing ones spawn out of sight around the
 * target (a few per second), extra or stale ones vanish, hunters are pointed at their target. Every unit carries {@link #TAG}; one left
 * in a saved chunk is discarded when it loads. Overworld only (the replica). Server thread.
 *
 * <p>Units: NCPD officer and patrol = a pillager in iron helmet and chestplate (crossbow); heavy = a vindicator in full iron with
 * Resistance; drone = a vex with a 60 s life; Max-Tac = a vindicator in netherite with Speed, Strength and Resistance.
 */
final class Squads {
	static final String TAG = "cyberblock_heat";
	/** At most this many units at once on the server. */
	static final int CAP = 24;
	/** Spawned per reconcile (once a second). */
	static final int SPAWNS_PER_SECOND = 3;
	/** A unit this far from its target vanishes (a new one spawns closer). */
	static final double LEASH = 96.0;
	private static final int DRONE_LIFE_TICKS = 1200;
	private final Map<Mob, Key> units = new HashMap<>();
	private boolean peacefulLogged;

	private record Key(UUID target, Unit unit) {
	}

	int size() {
		return this.units.size();
	}

	boolean isUnit(final Entity e) {
		return e.entityTags().contains(TAG);
	}

	/** Is anyone sent after this player (its HUD says "hunted")? Patrols do not count. */
	boolean hunting(final UUID player) {
		return this.units.values().stream().anyMatch(k -> k.unit() != Unit.PATROL && k.target().equals(player));
	}

	/**
	 * Matches the live units to the orders. {@code mayAttack}: players that may be targeted right now (not downed, not spectating); a
	 * unit whose target may not be attacked waits (no target) instead of vanishing.
	 */
	void reconcile(final MinecraftServer server, final List<Order> orders, final Predicate<ServerPlayer> mayAttack) {
		ServerLevel level = server.overworld();
		Map<Key, Integer> want = new HashMap<>();
		for (Order o : orders) {
			want.merge(new Key(o.target(), o.unit()), o.count(), Integer::sum);
		}

		Map<Key, Integer> have = new HashMap<>();
		for (Iterator<Map.Entry<Mob, Key>> it = this.units.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Mob, Key> e = it.next();
			Mob mob = e.getKey();
			Key key = e.getValue();
			ServerPlayer target = server.getPlayerList().getPlayer(key.target());
			boolean far = target == null || target.level() != mob.level() || mob.distanceToSqr(target) > LEASH * LEASH;
			int n = have.getOrDefault(key, 0);
			if (mob.isRemoved() || !mob.isAlive()) {
				it.remove();
			} else if (far || n >= want.getOrDefault(key, 0)) {
				mob.discard();
				it.remove();
			} else {
				have.put(key, n + 1);
				this.aim(mob, key, target, mayAttack);
			}
		}

		if (orders.isEmpty()) {
			return;
		}

		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			if (!this.peacefulLogged) {
				this.peacefulLogged = true;
				Cyberblock.LOG.info("heat: the world is on peaceful: Minecraft squads cannot spawn (Heat still feeds hosts' real wanted level)");
			}

			return;
		}

		int budget = SPAWNS_PER_SECOND;
		for (Map.Entry<Key, Integer> w : want.entrySet()) {
			Key key = w.getKey();
			ServerPlayer target = server.getPlayerList().getPlayer(key.target());
			if (target == null || target.level() != level) {
				continue;
			}

			for (int i = have.getOrDefault(key, 0); i < w.getValue() && budget > 0 && this.units.size() < CAP; i++) {
				budget--;
				Mob mob = this.spawn(level, key.unit(), target.position());
				if (mob != null) {
					this.units.put(mob, key);
					this.aim(mob, key, target, mayAttack);
				}
			}
		}
	}

	private void aim(final Mob mob, final Key key, final @Nullable ServerPlayer target, final Predicate<ServerPlayer> mayAttack) {
		if (key.unit() == Unit.PATROL) {
			return; // a patrol walks its beat; it fights whoever its own AI picks
		}

		if (target != null && mayAttack.test(target)) {
			if (mob.getTarget() != target) {
				mob.setTarget(target);
			}
		} else if (mob.getTarget() instanceof ServerPlayer p && !mayAttack.test(p)) {
			mob.setTarget(null);
		}
	}

	/** Discards every unit (server stop, /cyberblock heat clear). */
	void clear() {
		this.units.keySet().forEach(Entity::discard);
		this.units.clear();
	}

	/** An entity with our tag that is not one of ours (left in a saved chunk): it goes. */
	boolean stale(final Entity e) {
		return this.isUnit(e) && !(e instanceof Mob m && this.units.containsKey(m));
	}

	private @Nullable Mob spawn(final ServerLevel level, final Unit unit, final Vec3 near) {
		BlockPos at = unit == Unit.DRONE ? air(level, near) : ground(level, near, unit == Unit.PATROL ? 8 : 18, unit == Unit.PATROL ? 16 : 30);
		if (at == null) {
			return null;
		}

		EntityType<? extends Mob> type = switch (unit) {
			case PATROL, OFFICER -> EntityTypes.PILLAGER;
			case HEAVY, MAXTAC -> EntityTypes.VINDICATOR;
			case DRONE -> EntityTypes.VEX;
		};
		Mob mob = type.create(level, EntitySpawnReason.EVENT);
		if (mob == null) {
			return null;
		}

		mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, ThreadLocalRandom.current().nextFloat() * 360.0F, 0.0F);
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
		equip(mob, unit);
		mob.addTag(TAG);
		mob.setCustomName(name(unit));
		mob.setCustomNameVisible(true);
		mob.setPersistenceRequired();
		return level.addFreshEntity(mob) ? mob : null;
	}

	static Component name(final Unit unit) {
		return switch (unit) {
			case PATROL, OFFICER -> Component.translatableWithFallback("cyberblock.heat.unit.officer", "NCPD Officer");
			case HEAVY -> Component.translatableWithFallback("cyberblock.heat.unit.heavy", "NCPD Heavy");
			case DRONE -> Component.translatableWithFallback("cyberblock.heat.unit.drone", "NCPD Drone");
			case MAXTAC -> Component.translatableWithFallback("cyberblock.heat.unit.maxtac", "Max-Tac");
		};
	}

	private static void equip(final Mob mob, final Unit unit) {
		switch (unit) {
			case PATROL, OFFICER -> {
				wear(mob, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
				wear(mob, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			}
			case HEAVY -> {
				wear(mob, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
				wear(mob, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
				wear(mob, EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
				wear(mob, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
				wear(mob, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
				mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0));
			}
			case MAXTAC -> {
				wear(mob, EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
				wear(mob, EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
				wear(mob, EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
				wear(mob, EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
				wear(mob, EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
				mob.addEffect(new MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 0));
				mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 0));
				mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MobEffectInstance.INFINITE_DURATION, 1));
			}
			case DRONE -> {
				if (mob instanceof Vex vex) {
					vex.setLimitedLife(DRONE_LIFE_TICKS);
				}
			}
		}
	}

	/** Gear that never drops: the squads are no armour shop. */
	private static void wear(final Mob mob, final EquipmentSlot slot, final ItemStack stack) {
		mob.setItemSlot(slot, stack);
		mob.setDropChance(slot, 0.0F);
	}

	/** A standing spot (sturdy floor, two free blocks) min..max blocks around {@code near}, out of arm's reach; null when none. */
	private static @Nullable BlockPos ground(final ServerLevel level, final Vec3 near, final int min, final int max) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int attempt = 0; attempt < 12; attempt++) {
			double a = r.nextDouble() * Math.PI * 2.0, d = min + r.nextDouble() * (max - min);
			int x = (int) Math.floor(near.x + Math.cos(a) * d), z = (int) Math.floor(near.z + Math.sin(a) * d);
			for (int y = (int) Math.floor(near.y) + 6; y >= (int) Math.floor(near.y) - 6; y--) {
				BlockPos p = new BlockPos(x, y, z);
				if (free(level, p) && free(level, p.above()) && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
					return p;
				}
			}
		}

		return null;
	}

	/** A free spot 4-7 blocks above a point near the target (drones fly). */
	private static @Nullable BlockPos air(final ServerLevel level, final Vec3 near) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int attempt = 0; attempt < 8; attempt++) {
			BlockPos p = BlockPos.containing(near.x + r.nextInt(-12, 13), near.y + r.nextInt(4, 8), near.z + r.nextInt(-12, 13));
			if (free(level, p) && free(level, p.above())) {
				return p;
			}
		}

		return null;
	}

	private static boolean free(final ServerLevel level, final BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
	}

	/** The live units by target, for the log line. */
	List<String> describe() {
		List<String> out = new ArrayList<>();
		Map<Key, Integer> n = new HashMap<>();
		this.units.values().forEach(k -> n.merge(k, 1, Integer::sum));
		n.forEach((k, v) -> out.add(k.unit().name().toLowerCase(java.util.Locale.ROOT) + " x" + v));
		return out;
	}
}
