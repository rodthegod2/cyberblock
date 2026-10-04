package dev.cyberblock.client.device;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.client.replica.ReplicaRender;
import dev.cyberblock.device.block.DeviceBlocks;
import dev.cyberblock.device.block.EyeCameraBlock;
import dev.cyberblock.device.block.ShopWire;
import dev.cyberblock.device.block.TurretBlock;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;

/**
 * The client side of the device blocks (megaplan task 4-B1): on an active host's screen every device block renders as nothing, as
 * the replica blocks do (3-C1's {@link ReplicaRender#registerHostHidden}), and so do the camera cones, the turret heads and the
 * screens' item frames; guests see them all. Also the vending screen's {@code cyberblock:ui shop} route.
 */
public final class DeviceClient {
	private DeviceClient() {
	}

	/** From the client entrypoint, after the common init registered the blocks. */
	public static void init() {
		Block[] blocks = DeviceBlocks.all().toArray(Block[]::new);
		ReplicaRender.registerHostHidden(blocks);
		EyeCameraBlock.clientTicker = CameraCone::tick;
		TurretBlock.clientTicker = TurretRenderer::tick;
		BlockEntityRenderers.register(DeviceBlocks.TURRET_ENTITY, TurretRenderer::new);
		EntityRenderers.register(EntityTypes.ITEM_FRAME, ScreenFrameRenderer::new);
		UiRoutes.listen(ShopWire.T_SHOP, VendingScreen::onUi);
		Cyberblock.LOG.info("device: {} device block types hidden while Cyberpunk is the picture (with camera cones, turret heads and screens); vending screen ready",
			blocks.length);
	}
}
