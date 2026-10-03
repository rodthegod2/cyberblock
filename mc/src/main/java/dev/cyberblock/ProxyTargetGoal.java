// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (ProxyTargetGoal)
package dev.cyberblock;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/**
 * Hunt the nearest pedestrian's proxy (human proxies only: cars are not prey). Proxies are invisible and often behind
 * Night City walls Minecraft cannot see, so neither invisibility nor line of sight counts.
 */
final class ProxyTargetGoal extends NearestAttackableTargetGoal<ProxyEntity> {
	ProxyTargetGoal(final Mob mob) {
		super(mob, ProxyEntity.class, 10, false, false, (target, level) -> target.getType() == ProxyEntities.HUMAN);
		this.targetConditions.ignoreInvisibilityTesting().ignoreLineOfSight();
	}
}
