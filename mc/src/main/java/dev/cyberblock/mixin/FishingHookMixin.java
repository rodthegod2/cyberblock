package dev.cyberblock.mixin;

import dev.cyberblock.ProxyEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Layer 5: reeling in a fishing rod whose hook holds a proxy pulls the NPC or vehicle in Night City ({"t":"pull"}) instead of
 * vanilla's pull. The rest of retrieving stays vanilla (rod damage, the advancement trigger). Hooks catch proxies as any living
 * entity (Projectile.canHitEntity: alive and pickable).
 */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
	@Inject(method = "pullEntity", at = @At("HEAD"), cancellable = true)
	private void cyberblock$pullNpc(final Entity entity, final CallbackInfo ci) {
		if (entity instanceof ProxyEntity proxy && proxy.reelIn(((FishingHook) (Object) this).getOwner())) {
			ci.cancel();
		}
	}
}
