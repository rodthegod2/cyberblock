package dev.cyberblock.client.mixin;

import dev.cyberblock.client.HostCursor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The host's cursor is drawn on top of every open screen, so it is part of the exported overlay. */
@Mixin(Screen.class)
abstract class GuiCursorMixin {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
	private void cyberblock$drawCursor(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a, final CallbackInfo ci) {
		HostCursor.draw(graphics);
	}
}
