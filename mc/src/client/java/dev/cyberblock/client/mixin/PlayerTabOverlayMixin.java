package dev.cyberblock.client.mixin;

import dev.cyberblock.client.skin.TabMarker;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Megaplan 5-K1: the player list marks linked players (26.3: PlayerTabOverlay.getNameForDisplay(PlayerInfo), checked with javap). */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void cyberblock$linkedMarker(final PlayerInfo info, final CallbackInfoReturnable<Component> cir) {
		cir.setReturnValue(TabMarker.decorate(cir.getReturnValue(), info.getProfile().id()));
	}
}
