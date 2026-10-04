package dev.cyberblock.mode.downed.mixin;

import dev.cyberblock.mode.downed.Downed;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A downed player crawls (megaplan 8-T2): Player.updatePlayerPose runs every tick on the server and on every client (the local player
 * and the others), so each side keeps the swimming pose while {@link Downed#isDown} says so (the server's state, the client's downlist).
 */
@Mixin(Player.class)
abstract class DownedPoseMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void cyberblock$crawl(final CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Downed.isDown(self) && !self.isSpectator()) {
			if (self.getPose() != Pose.SWIMMING) {
				self.setPose(Pose.SWIMMING);
			}

			ci.cancel();
		}
	}
}
