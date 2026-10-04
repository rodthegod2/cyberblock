package dev.cyberblock.mode.van;

import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Hacker in the Van's Van (megaplan task 8-V1): a placeable vehicle entity (drawn with Phase 5's van model) with one netrunner
 * seat. Right-click it to sit in (guests: an active host's Steve follows V and cannot); sitting, a right-click opens the Van screen,
 * and Shift gets out as from any seat. The Van keeps which host it is linked to and its drone's battery (saved with the world); its
 * drone, when one flies, is {@link #drone}. Three hits break it into its item (one in creative). Server logic lives in VanModule.
 */
public final class VanEntity extends Entity {
	/** The dock's charge 0..1, for the client (the HUD and the screen while the state is on its way). */
	private static final EntityDataAccessor<Float> CHARGE = SynchedEntityData.defineId(VanEntity.class, EntityDataSerializers.FLOAT);
	/** Hits that break a Van, and how long (ticks) a hit counts. */
	static final int HITS = 3, HIT_MEMORY = 40;
	private @Nullable UUID host;
	private int battery = VanRules.BATTERY;
	/** The drone in the air (entity id, -1: docked); not saved: drones never outlive a restart. */
	int drone = -1;
	private int hits;
	private int lastHit;

	public VanEntity(final EntityType<? extends VanEntity> type, final Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		builder.define(CHARGE, 1.0F);
	}

	public float charge() {
		return this.entityData.get(CHARGE);
	}

	@Nullable UUID host() {
		return this.host;
	}

	void setHost(final @Nullable UUID host) {
		this.host = host;
	}

	int battery() {
		return this.battery;
	}

	void setBattery(final int battery) {
		this.battery = Math.clamp(battery, 0, VanRules.BATTERY);
		// whole percents: the client hears of a change about once a second, not every tick
		this.entityData.set(CHARGE, Math.round(VanRules.share(this.battery) * 100.0F) / 100.0F);
	}

	/** The netrunner in the seat (server side), or null. */
	@Nullable ServerPlayer netrunner() {
		return this.getFirstPassenger() instanceof ServerPlayer p ? p : null;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel) {
			// a parked vehicle: it falls onto the ground and stays put
			Vec3 v = this.getDeltaMovement();
			v = this.onGround() ? new Vec3(0.0, Math.max(v.y, 0.0), 0.0) : v.add(0.0, -0.04, 0.0);
			if (!this.onGround() || v.lengthSqr() > 1e-6) {
				this.move(MoverType.SELF, v);
			}

			this.setDeltaMovement(v.multiply(0.5, 0.98, 0.5));
			VanModule.tickVan(this);
		}
	}

	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		if (player.isSecondaryUseActive() || player.isSpectator()) {
			return InteractionResult.PASS;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}

		return VanModule.use(serverPlayer, this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
	}

	@Override
	protected boolean canAddPassenger(final Entity passenger) {
		return this.getPassengers().isEmpty() && passenger instanceof Player;
	}

	/** The seat is low inside the van's body: the netrunner sits hidden in the back. */
	@Override
	protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
		return new Vec3(0.0, 0.35, 0.0);
	}

	@Override
	protected void removePassenger(final Entity passenger) {
		super.removePassenger(passenger);
		if (passenger instanceof ServerPlayer p) {
			VanModule.left(p, this);
		}
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float amount) {
		if (this.isRemoved() || !(source.getEntity() instanceof Player player) || this.hasPassenger(player)) {
			return false;
		}

		if (this.tickCount - this.lastHit > HIT_MEMORY) {
			this.hits = 0;
		}

		this.lastHit = this.tickCount;
		if (player.getAbilities().instabuild || ++this.hits >= HITS) {
			if (!player.getAbilities().instabuild) {
				this.spawnAtLocation(level, new ItemStack(VanModule.VAN_ITEM));
			}

			VanModule.broken(this, player);
			this.discard();
		}

		return true;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(final @Nullable Entity other) {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public ItemStack getPickResult() {
		return new ItemStack(VanModule.VAN_ITEM);
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		String h = input.getStringOr("host", "");
		try {
			this.host = h.isEmpty() ? null : UUID.fromString(h);
		} catch (IllegalArgumentException e) {
			this.host = null;
		}

		this.setBattery(input.getIntOr("battery", VanRules.BATTERY));
	}

	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		output.putString("host", this.host == null ? "" : this.host.toString());
		output.putInt("battery", this.battery);
	}
}
