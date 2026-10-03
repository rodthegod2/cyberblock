package dev.cyberblock.mixin;

import dev.cyberblock.ExplosionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A creeper Steve lights with flint and steel or a fire charge is his explosion (the demolitions perk). */
@Mixin(Creeper.class)
abstract class CreeperMixin {
	@Inject(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Creeper;ignite()V"))
	private void cyberblock$litBy(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
		if (player instanceof ServerPlayer) {
			ExplosionEvents.litBySteve((Creeper) (Object) this);
		}
	}
}
