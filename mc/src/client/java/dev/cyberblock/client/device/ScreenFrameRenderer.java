package dev.cyberblock.client.device;

import dev.cyberblock.client.replica.ReplicaRender;
import dev.cyberblock.device.block.ScreenFrames;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Vanilla's item frame renderer, except that a Night City screen (a frame whose map item carries {@link ScreenFrames#MARK}) draws
 * nothing on an active host's screen, like the device blocks: Cyberpunk shows the real TV there. Every other item frame is untouched.
 */
final class ScreenFrameRenderer extends ItemFrameRenderer<ItemFrame> {
	ScreenFrameRenderer(final EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public boolean shouldRender(final ItemFrame frame, final Frustum frustum, final double x, final double y, final double z, final float partialTicks) {
		return !(ReplicaRender.hiding() && ScreenFrames.isScreen(frame.getItem())) && super.shouldRender(frame, frustum, x, y, z, partialTicks);
	}
}
