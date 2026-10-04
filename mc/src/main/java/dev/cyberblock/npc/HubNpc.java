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
import org.jspecify.annotations.Nullable;

/**
 * A hub NPC (megaplan task 7-N1): a server-side figure with a role from {@code npc_role} content that always stands at its spot in
 * the Afterlife hub, whether or not any host is online. It never walks, cannot be hurt or pushed, turns to look at players, and is
 * never saved (HubNpcs puts it back whenever its spot is loaded). Its role, skin and slim model are synced for the renderer and the
 * role screens.
 */
public final class HubNpc extends PathfinderMob {
	private static final EntityDataAccessor<String> DATA_ROLE = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DATA_SLIM = SynchedEntityData.defineId(HubNpc.class, EntityDataSerializers.BOOLEAN);
	/** Which spot (role id # index) it stands for; server side. */
	private String spot = "";

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

	/** Server: what it is (role id, name, skin texture id, slim model) and which spot it stands for. */
	void setup(final Identifier role, final String name, final String skin, final boolean slim, final String spot) {
		this.entityData.set(DATA_ROLE, role.toString());
		this.entityData.set(DATA_SKIN, skin);
		this.entityData.set(DATA_SLIM, slim);
		this.spot = spot;
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

	String npcName() {
		Component name = this.getCustomName();
		return name == null ? "" : name.getString();
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
		return false;
	}

	/** A right-click is the role screen (the server opens it from UseEntityCallback first); the held item is never used on it. */
	@Override
	protected InteractionResult mobInteract(final Player player, final InteractionHand hand) {
		return InteractionResult.SUCCESS;
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
