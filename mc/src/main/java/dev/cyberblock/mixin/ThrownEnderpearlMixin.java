package dev.cyberblock.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cyberblock.Wire;
import dev.cyberblock.WorldBridge;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Layer 4: while his host drives, Steve's ender pearl moves V, not Steve: the thrower's session hears {"t":"teleport"} with the landing spot
 * and moves V there, and Steve follows V's camera as always. Vanilla's 5 points of pearl damage and the fall reset still apply
 * (the wrapped teleport hands back the same player). No endermite either. While V cannot be moved (held by the game, or in a
 * vehicle) the pearl is refused: no teleport is sent, the wrapped teleport hands back null so vanilla skips the damage, the pearl
 * comes back and Steve is told why.
 */
@Mixin(ThrownEnderpearl.class)
abstract class ThrownEnderpearlMixin {
	@WrapOperation(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;"))
	private @Nullable ServerPlayer cyberblock$hostTeleports(final ServerPlayer player, final TeleportTransition transition,
		final Operation<ServerPlayer> original) {
		HostSession s = HostSessions.of(player);
		if (s == null || !WorldBridge.hostSees(player)) {
			return original.call(player, transition);
		}

		if (s.held() || s.vehicle()) {
			player.sendOverlayMessage(Component.literal("Pearl blocked: V cannot be moved right now"));
			if (!player.hasInfiniteMaterials()) {
				player.getInventory().placeItemBackInInventory(new ItemStack(Items.ENDER_PEARL), Prediction.SERVER_ONLY);
			}

			return null;
		}

		Vec3 p = transition.position();
		s.send(Wire.teleport(p.x, p.y, p.z, player.getYRot()));
		return player;
	}

	@WrapOperation(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean cyberblock$noEndermite(final ServerLevel level, final Entity endermite, final Operation<Boolean> original) {
		return !this.cyberblock$hostSees() && original.call(level, endermite);
	}

	/** The pearl's thrower is a host whose session is active in this world. */
	private boolean cyberblock$hostSees() {
		return ((ThrownEnderpearl) (Object) this).getOwner() instanceof ServerPlayer thrower && WorldBridge.hostSees(thrower);
	}
}
