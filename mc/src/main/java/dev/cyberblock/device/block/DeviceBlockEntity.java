package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity of the eye camera and the turret: it saves nothing (the block state is the device's whole state, the server
 * re-sends it), it only gives the client a per-block tick (the camera's particle cone, the turret's aim) and the turret its renderer.
 * The aim fields are client-side render state, in degrees (yaw as Entity yaw: 0 = south, 90 = west).
 */
public final class DeviceBlockEntity extends BlockEntity {
	public float yaw;
	public float yawO;
	public float pitch;
	public float pitchO;
	/** Client ticks since the block entity was loaded. */
	public int age;

	public DeviceBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
		super(type, pos, state);
	}
}
