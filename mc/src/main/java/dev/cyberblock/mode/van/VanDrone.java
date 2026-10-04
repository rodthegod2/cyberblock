package dev.cyberblock.mode.van;

import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A Van's drone (megaplan task 8-V1): a small flying entity the netrunner flies through the replica as his eyes, never farther than
 * {@link VanRules#DRONE_RANGE} from its Van, while the Van's battery lasts. The server moves it from the pilot's own keys (vanilla's
 * player input while seated) and turns it with his view; on his client the camera sits in it and turns with his mouse at once
 * ({@link #clientView}). Never saved; one hit brings it down (the Van's battery is then empty). Server logic lives in VanModule.
 */
public final class VanDrone extends Entity {
	/** Battery share 0..1 (for the pilot's HUD and the light on the model). */
	private static final EntityDataAccessor<Float> BATTERY = SynchedEntityData.defineId(VanDrone.class, EntityDataSerializers.FLOAT);
	/**
	 * The client's camera override: (drone, {yaw, pitch}) from the local pilot's own view, or null for any other drone. The client
	 * entrypoint installs it; on a dedicated server it is never called.
	 */
	public static volatile BiFunction<VanDrone, Float, float @Nullable []> clientView = (drone, partial) -> null;
	/** The Van it belongs to (entity id) and who flies it. */
	int van = -1;
	@Nullable UUID pilot;

	public VanDrone(final EntityType<? extends VanDrone> type, final Level level) {
		super(type, level);
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		builder.define(BATTERY, 1.0F);
	}

	public float battery() {
		return this.entityData.get(BATTERY);
	}

	void setBattery(final float share) {
		this.entityData.set(BATTERY, share);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel) {
			VanModule.tickDrone(this);
		}
	}

	@Override
	public float getViewYRot(final float partialTicks) {
		float[] view = clientView.apply(this, partialTicks);
		return view != null ? view[0] : super.getViewYRot(partialTicks);
	}

	@Override
	public float getViewXRot(final float partialTicks) {
		float[] view = clientView.apply(this, partialTicks);
		return view != null ? view[1] : super.getViewXRot(partialTicks);
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float amount) {
		if (this.isRemoved() || source.getEntity() == null) {
			return false;
		}

		VanModule.shotDown(this, source.getEntity());
		return true;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
	}
}
