// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (ProxyTargetGoal)
package dev.cyberblock;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Hunt a pedestrian's proxy, a hostile one first and else the nearest (human proxies only: cars are not prey), as long as it stands where Night City's ground is
 * probed: a mob that ran after an NPC beyond that would walk off into the void. Proxies are invisible and often behind Night City
 * walls Minecraft cannot see, so neither invisibility nor line of sight counts.
 */
final class ProxyTargetGoal extends NearestAttackableTargetGoal<ProxyEntity> {
	ProxyTargetGoal(final Mob mob) {
		super(mob, ProxyEntity.class, 10, false, false, (target, level) -> target.getType() == ProxyEntities.HUMAN && onProbedGround(target, level));
		this.targetConditions.ignoreInvisibilityTesting().ignoreLineOfSight();
	}

	/** The same search as vanilla's, but a hostile pedestrian is taken before a peaceful one, and then the nearest (vanilla: the nearest). */
	@Override
	protected void findTarget() {
		ServerLevel level = getServerLevel(this.mob);
		double range = this.getFollowDistance();
		this.targetConditions.range(range);
		ProxyEntity best = null;
		double bestDistanceSq = 0.0;
		for (ProxyEntity proxy : this.mob.level().getEntitiesOfClass(ProxyEntity.class, this.getTargetSearchArea(range), e -> true)) {
			if (!this.targetConditions.test(level, this.mob, proxy)) {
				continue;
			}

			double distanceSq = proxy.distanceToSqr(this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
			if (best == null || MobRules.preferred(proxy.isHostile(), distanceSq, best.isHostile(), bestDistanceSq)) {
				best = proxy;
				bestDistanceSq = distanceSq;
			}
		}

		this.target = best;
	}

	/** Within MobRules.HOLD_RANGE (horizontally) of a player: Steve stands in the middle of the ground the host probes. */
	private static boolean onProbedGround(final LivingEntity target, final ServerLevel level) {
		for (Player player : level.players()) {
			if (MobRules.proxyInReach(target.getX() - player.getX(), target.getZ() - player.getZ())) {
				return true;
			}
		}

		return false;
	}
}
