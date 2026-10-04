package dev.cyberblock.npc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A hub NPC (megaplan task 7-N1): a server-side figure with an {@code npc_role} that always stands at its place in the Afterlife hub
 * (a {@code figures} entry of the hub layout), whether or not any host is online. It never walks, cannot be hurt or pushed, turns to
 * look at players, and is never saved (HubNpcs puts it back whenever its place is loaded). Its role, skin and slim model are synced
 * for the renderer and the role screens.
 */
public final class HubNpc extends PathfinderMob {
	private static final EntityDataAccessor<String> DATA_ROLE = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DATA_SLIM = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.BOOLEAN);
	/** Which hub figure it is (the layout's figure id); server side. */
	private String spot = "";
	/** The shop it sells from (a vendor; null: its role's shop); server side. */
	private @Nullable Identifier shop;

	public HubNpc(final EntityType<? extends HubNpc> type, final Level level) {
		super(type, level);
		this.setPermanentlyInvulnerable(true);
		this.setPersistenceRequired();
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
		super.defineSynchedData(entityData);
		entityData.define(DATA_ROLE, "");
		entityData.define(DATA_SKIN, "");
		entityData.define(DATA_SLIM, false);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
	}

	/** Server: what it is (role id, name, skin texture id, slim model), which hub figure it stands for and the shop it sells from. */
	void setup(final Identifier role, final String name, final String skin, final boolean slim, final String spot, final @Nullable Identifier shop) {
		this.entityData.set(DATA_ROLE, role.toString());
		this.entityData.set(DATA_SKIN, skin);
		this.entityData.set(DATA_SLIM, slim);
		this.spot = spot;
		this.shop = shop;
		this.setCustomName(name.isEmpty() ? null : Component.literal(name));
		this.setCustomNameVisible(!name.isEmpty());
	}

	public @Nullable Identifier role() {
		return Identifier.tryParse(this.entityData.get(DATA_ROLE));
	}

	/** The skin texture id ("" for the neutral figure skin); valid on the client. */
	public String skin() {
		return this.entityData.get(DATA_SKIN);
	}

	public boolean slim() {
		return this.entityData.get(DATA_SLIM);
	}

	String spot() {
		return this.spot;
	}

	@Nullable Identifier shop() {
		return this.shop;
	}

	String npcName() {
		Component name = this.getCustomName();
		return name == null ? "" : name.getString();
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
		return false;
	}

	/**
	 * A right-click is the role screen (the server opens it from UseEntityCallback first), sneaking or not: nothing held is ever used on
	 * it, so no name tag renames it and no lead ties it (Mob.interact would apply both before mobInteract).
	 */
	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(final Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(final double distSqr) {
		return false;
	}
}
