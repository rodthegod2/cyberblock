package dev.cyberblock;

import dev.cyberblock.cyber.CyberwareEffects;
import dev.cyberblock.figure.FigureLook;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Stands in Minecraft for one Cyberpunk NPC or vehicle: invisible, no gravity, put at the host's position by ProxyManager and
 * never saved. Minecraft weapons and mobs can hit it, but it is never hurt here: a hit becomes a {"t":"hit"} for the host,
 * which applies it to the real NPC. Damage with no player or mob behind it (lava, suffocation, falling) is ignored, and so is every
 * explosion (the host's own Cyberpunk explosion hurts the NPC). A lead on it
 * (layer 5) is reported as {"t":"lead"} and the host drags the NPC; a fishing rod reeling it in is {"t":"pull"}. Everything it reports
 * goes to its owner, the host whose game the NPC is in, and a guest's hits, fire, leads and pulls only with that host's damage toggle
 * (plan 2026-10-04 sections 1.4 and 1.6).
 */
public final class ProxyEntity extends LivingEntity implements Leashable {
	/** Vanilla's damage cooldown: for this long only a hit stronger than the last one counts. */
	private static final int COOLDOWN_TICKS = 10;
	/** Minecraft only breaks a proxy's leash this far from its holder (vanilla: 12); the host gives up at length + 12 m for 2 s. */
	private static final double LEASH_SNAP = 32.0;
	private Leashable.@Nullable LeashData leashData;
	/** Who holds the leash as far as the host knows (null: nobody). */
	private @Nullable Entity reportedHolder;
	/** How long flint and steel or a fire charge set an NPC alight. */
	private static final float ITEM_IGNITE_SECONDS = 8.0F;
	/** Cyberpunk's NPC burns for the reported time: the same ignition is not reported again until it has run out (a longer one is). */
	private int igniteCooldown;
	/** How long the burn the host was last told of is: a fire has to be longer than this to be reported while it burns. */
	private int igniteReported;
	private int damageCooldown;
	private float lastDamage;
	private long proxyId;
	/** The host whose Cyberpunk game the NPC or vehicle is in (plan 2026-10-04 section 1.4). */
	private @Nullable UUID owner;
	/** Who last struck or used an item on it, and on which tick: the player behind a fire that starts with the blow. */
	private @Nullable Entity lastActor;
	private int lastActorTick = Integer.MIN_VALUE;
	/** The last player who held the leash: a lead tied to a fence is still theirs. */
	private @Nullable Entity leashActor;
	/** A new burn (seconds) to report on the next tick, once the blow that lit it (it may come after: arrows) has named its player. */
	private double pendingIgnite;
	/**
	 * Hostile to V as far as the host knows (its latest list): the mobs hunt these first, and the client outlines them (Kiroshi
	 * optics, plan retest2 R6), so it is synced entity data.
	 */
	private static final EntityDataAccessor<Boolean> DATA_HOSTILE = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.BOOLEAN);
	/** Ticks left of a Compass Ping (plan retest3 section 1.5): the client outlines a pinged proxy, so it is synced entity data. */
	private static final EntityDataAccessor<Integer> DATA_PINGED = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.INT);
	/**
	 * Dead in Night City (plan 2026-10-04 task 3-F1): a guest's figure lies down (a car stays a dark wreck) until ProxyManager
	 * removes it. The figure's look is synced entity data like this, set from the host's list.
	 */
	private static final EntityDataAccessor<Boolean> DATA_DEAD = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.BOOLEAN);
	/*
	 * The figure's look (megaplan task 5-F1, figure/FigureLook): a person's skin id or a vehicle's model id, the slim model, the
	 * host's anim bits, the scan's threat level (-1 unscanned, 0..4), a vehicle's paint (0xRRGGBB, -1 none), hp 0..1, the name tag's
	 * label, and whether it died in an explosion.
	 */
	private static final EntityDataAccessor<String> DATA_LOOK = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DATA_SLIM = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_ANIM = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_THREAT = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_COLOUR = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_HP = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<String> DATA_LABEL = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DATA_BLOWN = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.BOOLEAN);
	/** A Minecraft explosion this recent (ticks) that reached it means it died in that explosion. */
	static final int BLAST_TICKS = 40;
	/** Ticks since it died, on each side (the client counts from when it saw it die): how far the body has fallen. */
	private int deadTicks;
	/** No Minecraft explosion has reached it yet. */
	static final int NO_BLAST = Integer.MIN_VALUE;
	/** When a Minecraft explosion last reached it (server tick count), NO_BLAST for never. */
	private int blastTick = NO_BLAST;

	public ProxyEntity(final EntityType<? extends ProxyEntity> type, final Level level) {
		super(type, level);
		// invisible to vanilla; guests see it through client/figure, which draws it anyway
		this.setInvisible(true);
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
		super.defineSynchedData(entityData);
		entityData.define(DATA_HOSTILE, false);
		entityData.define(DATA_PINGED, 0);
		entityData.define(DATA_DEAD, false);
		entityData.define(DATA_LOOK, FigureLook.NEUTRAL.look());
		entityData.define(DATA_SLIM, false);
		entityData.define(DATA_ANIM, 0);
		entityData.define(DATA_THREAT, -1);
		entityData.define(DATA_COLOUR, -1);
		entityData.define(DATA_HP, 1.0F);
		entityData.define(DATA_LABEL, "");
		entityData.define(DATA_BLOWN, false);
	}

	/** Server thread: the look worked out from the host's tags (Figures.look); unchanged values are not sent again. */
	void look(final FigureLook look) {
		this.entityData.set(DATA_LOOK, look.look());
		this.entityData.set(DATA_SLIM, look.slim());
		this.entityData.set(DATA_ANIM, look.anim());
		this.entityData.set(DATA_COLOUR, look.colour());
		this.entityData.set(DATA_HP, look.hp());
		this.entityData.set(DATA_LABEL, look.label());
	}

	/** Server thread: the threat a scan of it named (-1 unscanned, 0 none .. 4 extreme). */
	void setThreat(final int threat) {
		this.entityData.set(DATA_THREAT, Math.clamp(threat, -1, 4));
	}

	/** A person's skin id or a vehicle's model id (FigureLook); valid on the client. */
	public String lookId() {
		return this.entityData.get(DATA_LOOK);
	}

	public boolean slim() {
		return this.entityData.get(DATA_SLIM);
	}

	/** The host's anim bits (FigureTags: 1 walk, 2 run, 4 aim, 8 fire, 16 ragdoll, 32 dead, 64 crouch, 128 combat). */
	public int anim() {
		return this.entityData.get(DATA_ANIM);
	}

	public int threat() {
		return this.entityData.get(DATA_THREAT);
	}

	/** A vehicle's paint 0xRRGGBB, -1 for none. */
	public int colour() {
		return this.entityData.get(DATA_COLOUR);
	}

	public float hp() {
		return this.entityData.get(DATA_HP);
	}

	/** The name tag's label (FigureLook). */
	public String label() {
		return this.entityData.get(DATA_LABEL);
	}

	/** It died in an explosion (a vehicle always does): the client bursts it. */
	public boolean blown() {
		return this.entityData.get(DATA_BLOWN);
	}

	/** Server thread: the host's latest list entry for it: its place and the synced figure data. */
	void apply(final ProxyDiff.Entry e) {
		this.setHostile(e.hostile());
		this.setPos(e.x(), e.y(), e.z());
		this.setYRot(e.yaw());
		this.setYHeadRot(e.yaw());
		this.setYBodyRot(e.yaw());
	}

	/**
	 * Server thread: it died in Night City (ProxyManager.bury). It stops being anything to hit, hunt, outline or report; it only lies
	 * there for the guests until ProxyManager removes it.
	 */
	void die(final boolean blown) {
		this.entityData.set(DATA_BLOWN, blown || blastRecent(this.tickCount, this.blastTick));
		this.entityData.set(DATA_DEAD, true);
		this.entityData.set(DATA_HP, 0.0F);
		this.setHostile(false);
		this.setPinged(0);
	}

	/** Whether an explosion at blastTick (NO_BLAST: never) reached it within BLAST_TICKS before now; the sentinel is checked, not subtracted. */
	static boolean blastRecent(final int now, final int blastTick) {
		return blastTick != NO_BLAST && now >= blastTick && now - blastTick <= BLAST_TICKS;
	}

	/** Dead in Night City; valid on the client too (the figure lies down or is a wreck). */
	public boolean dead() {
		return this.entityData.get(DATA_DEAD);
	}

	/** Ticks since it died on this side (0 while alive). */
	public int deadTicks() {
		return this.deadTicks;
	}

	/** A body or wreck is no target for the crosshair, a sword or an arrow. */
	@Override
	public boolean isPickable() {
		return !this.dead() && super.isPickable();
	}

	/** Mobs (ProxyTargetGoal) never hunt a body. */
	@Override
	public boolean canBeSeenAsEnemy() {
		return !this.dead() && super.canBeSeenAsEnemy();
	}

	/** The Cyberpunk entity id hash this proxy stands for. */
	void setProxyId(final long id) {
		this.proxyId = id;
	}

	long proxyId() {
		return this.proxyId;
	}

	void setOwner(final UUID owner) {
		this.owner = owner;
	}

	/** The host whose game this proxy belongs to (server side; null on the client). */
	public @Nullable UUID owner() {
		return this.owner;
	}

	/** The player or mob that struck it this tick or the last, else null. */
	private @Nullable Entity recentActor() {
		return this.tickCount - this.lastActorTick <= 1 ? this.lastActor : null;
	}

	void setHostile(final boolean hostile) {
		this.entityData.set(DATA_HOSTILE, hostile);
	}

	/** Hostile to V (the host's latest list); valid on the client too (Bridge.hostileProxy). */
	public boolean hostile() {
		return this.entityData.get(DATA_HOSTILE);
	}

	/** Server thread: pinged for this many ticks (0 or less clears it); Bridge.pingProxy. */
	public void setPinged(final int ticks) {
		this.entityData.set(DATA_PINGED, Math.max(0, ticks));
	}

	/** Pinged by a Compass Ping that has not run out; valid on the client too (Bridge.pingedProxy). */
	public boolean pinged() {
		return this.entityData.get(DATA_PINGED) > 0;
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
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
	protected void doPush(final Entity entity) {
	}

	/** The host moves it: nothing in Minecraft pushes a proxy anywhere. */
	@Override
	public void knockback(final double power, final double xd, final double zd, final DamageSource source, final float damage, final boolean comesFromEffect) {
	}

	@Override
	public void tick() {
		this.setDeltaMovement(Vec3.ZERO);
		super.tick();
		if (this.damageCooldown > 0) {
			this.damageCooldown--;
		}

		if (this.igniteCooldown > 0) {
			this.igniteCooldown--;
		}

		if (this.dead()) {
			this.deadTicks++;
			return;
		}

		if (this.level() instanceof ServerLevel) {
			if (this.pendingIgnite > 0.0) {
				ProxyManager.ignite(this, this.pendingIgnite, this.recentActor());
				this.pendingIgnite = 0.0;
			}

			this.reportLeash();
			int ping = this.entityData.get(DATA_PINGED);
			if (ping > 0) {
				this.entityData.set(DATA_PINGED, ping - 1);
			}
		}
	}

	/**
	 * Every vanilla way to set an entity on fire (lava, a fire block, a flaming arrow, fire aspect, the items below) ends here
	 * with positive ticks. The proxy never burns in Minecraft: the ignition goes to the host, which burns the real NPC.
	 */
	@Override
	public void setRemainingFireTicks(final int remainingTicks) {
		if (remainingTicks <= 0 || !(this.level() instanceof ServerLevel)) {
			super.setRemainingFireTicks(remainingTicks);
		} else if (!this.dead() && ProxyDamage.igniteDue(this.igniteCooldown, this.igniteReported, remainingTicks)) {
			this.pendingIgnite = remainingTicks / 20.0;
			this.igniteCooldown = remainingTicks;
			this.igniteReported = remainingTicks;
		}
	}

	/** Flint and steel and fire charges only light blocks in vanilla; aimed at an NPC they light the NPC. */
	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		ItemStack held = player.getItemInHand(hand);
		boolean flint = held.is(Items.FLINT_AND_STEEL);
		// a vehicle does not burn, nor does a body: the item stays unused (and is not worn or consumed)
		if (this.getType() == ProxyEntities.VEHICLE || this.dead() || !flint && !held.is(Items.FIRE_CHARGE)) {
			return super.interact(player, hand, location);
		}

		if (this.level() instanceof ServerLevel level) {
			this.lastActor = player;
			this.lastActorTick = this.tickCount;
			level.playSound(null, this.getX(), this.getY(), this.getZ(), flint ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.FIRECHARGE_USE,
				SoundSource.PLAYERS, 1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
			this.igniteForSeconds(ITEM_IGNITE_SECONDS);
			if (flint) {
				held.hurtAndBreak(1, player, hand);
			} else {
				held.consume(1, player);
			}
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
		Entity attacker = source.getEntity();
		this.lastActor = attacker;
		this.lastActorTick = this.tickCount;
		ProxyDamage.Actor actor = attacker instanceof Player ? ProxyDamage.Actor.PLAYER : attacker instanceof Mob ? ProxyDamage.Actor.MOB : ProxyDamage.Actor.OTHER;
		Entity direct = source.getDirectEntity();
		ProxyDamage.Medium medium = direct instanceof ThrownTrident ? ProxyDamage.Medium.TRIDENT
			: direct instanceof AbstractArrow ? ProxyDamage.Medium.ARROW
			: direct instanceof Projectile ? ProxyDamage.Medium.PROJECTILE : ProxyDamage.Medium.BODY;
		// no kind for an explosion (TNT, creeper, firework...): the host's real Cyberpunk explosion is what hurts the NPC, and this one is consumed
		boolean blast = source.is(DamageTypeTags.IS_EXPLOSION);
		if (blast) {
			// should the NPC die soon after, it died in this explosion (task 5-F1: a body that bursts)
			this.blastTick = this.tickCount;
		}

		String kind = ProxyDamage.kind(blast, source.is(DamageTypeTags.IS_FIRE), medium, actor);
		if (kind == null || this.dead() || !(damage > 0.0F) || !Float.isFinite(damage)) {
			return false;
		}

		float counted = ProxyDamage.afterCooldown(damage, this.lastDamage, this.damageCooldown > 0);
		if (!(counted > 0.0F)) {
			return false;
		}

		this.lastDamage = damage;
		this.damageCooldown = COOLDOWN_TICKS;
		// the headhunter perk: Steve's arrow in a pedestrian's head (its top 0.35 m) counts double
		if (medium == ProxyDamage.Medium.ARROW && attacker instanceof Player shooter && direct != null && this.getType() == ProxyEntities.HUMAN
			&& ProxyDamage.headshot(direct.getY(), this.getY(), this.getBbHeight()) && perk(shooter, "headhunter")) {
			counted *= ProxyDamage.HEADSHOT;
		}

		// layer 3: whatever a mob deals (blow, arrow, trident, a ghast's fireball) names the mob, so its puppet in Night City is the attacker
		int mob = attacker instanceof Mob ? attacker.getId() : -1;
		ProxyManager.hit(this, counted, kind, this.impulse(level, source, kind, medium, attacker, direct), mob, attacker);
		return true;
	}

	private Vec3 impulse(final ServerLevel level, final DamageSource source, final String kind, final ProxyDamage.Medium medium,
		final @Nullable Entity attacker, final @Nullable Entity direct) {
		if (kind.equals("fire")) {
			return Vec3.ZERO;
		}

		if (medium != ProxyDamage.Medium.BODY) {
			return direct == null ? Vec3.ZERO : ProxyDamage.projectile(direct.getDeltaMovement(), this.punch(level, source, direct));
		}

		if (attacker == null) {
			return Vec3.ZERO;
		}

		// vanilla pushes along the attacker's yaw; looking straight up or down there is no direction, then it is away from the attacker
		Vec3 look = attacker.getLookAngle();
		Vec3 along = Math.hypot(look.x, look.z) > 1.0E-3 ? look : this.position().subtract(attacker.position());
		double strength = attacker instanceof LivingEntity living ? this.knockbackLevel(level, source, living) : 0.0;
		if (!(attacker instanceof Player player)) {
			return ProxyDamage.melee(along.x, along.z, strength);
		}

		if (this.getType() == ProxyEntities.VEHICLE) {
			return ProxyDamage.meleeOn(along.x, along.z, strength, gorillaArms(player), true);
		}

		// on a person the implant's own ATTACK_KNOCKBACK modifier is its strength, all of it only on a charged swing (leftover bug QG)
		return ProxyDamage.melee(along.x, along.z, ProxyDamage.personLevel(strength, CyberwareEffects.gorillaKnockback(player),
			ProxyDamage.charged(player), perk(player, "gorilla_arms")));
	}

	/**
	 * Whether Gorilla Arms triples a vehicle's push (plan retest3 section 1.9): the gorilla_arms perk, or the gorilla_arms implant
	 * swung with an empty main hand (plan retest2 section 1.7), one shove, never two. People: ProxyDamage.personLevel.
	 */
	private static boolean gorillaArms(final Player attacker) {
		return perk(attacker, "gorilla_arms") || WorldModule.hasCyberware.test(attacker, "gorilla_arms") && attacker.getMainHandItem().isEmpty();
	}

	/** The Punch level of the bow an arrow came from (vanilla AbstractArrow.doKnockback asks the same), 0 for anything else. */
	private float punch(final ServerLevel level, final DamageSource source, final Entity direct) {
		ItemStack weapon = direct.getWeaponItem();
		return weapon == null || weapon.isEmpty() ? 0.0F : EnchantmentHelper.modifyKnockback(level, weapon, this, source, 0.0F);
	}

	/** Server thread: the attacking player owns this perk (plan section 1.5; the cyber track keeps each player's ranks). */
	private static boolean perk(final Player attacker, final String id) {
		return WorldModule.perkRank.applyAsInt(attacker, id) > 0;
	}

	@Override
	public Leashable.@Nullable LeashData getLeashData() {
		return this.leashData;
	}

	@Override
	public void setLeashData(final Leashable.@Nullable LeashData leashData) {
		this.leashData = leashData;
	}

	/** The host drags the NPC or vehicle along the leash (Tether.reds): the leash never moves the proxy itself. */
	@Override
	public boolean checkElasticInteractions(final Entity leashHolder, final Leashable.LeashData leashData) {
		return false;
	}

	/** The host decides when a tether snaps ({"t":"unlead"}); this is only the last resort. */
	@Override
	public double leashSnapDistance() {
		return LEASH_SNAP;
	}

	/** Server thread, every tick: the host hears of every change of who holds the leash (leashed, tied to a fence, untied, broken). */
	private void reportLeash() {
		Entity holder = this.isLeashed() ? this.getLeashHolder() : null;
		if (holder == this.reportedHolder) {
			return;
		}

		this.reportedHolder = holder;
		if (holder instanceof Player) {
			this.leashActor = holder;
		}

		String id = Long.toUnsignedString(this.proxyId);
		// letting go is never a guest's harm: a release always goes
		ProxyManager.toOwner(this, holder == null ? Wire.leadRelease(id)
			: Wire.lead(id, holder instanceof Player ? null : new double[] {holder.getX(), holder.getY(), holder.getZ()},
				Tethers.leadLength(this.getType() == ProxyEntities.VEHICLE)), holder == null ? null : this.leashActor);
	}

	/** Server thread, as ProxyManager removes the proxy: a leash the host was told of is reported released. */
	void reportRemoved() {
		Entity holder = this.reportedHolder;
		this.reportedHolder = null;
		if (holder != null) {
			ProxyManager.toOwner(this, Wire.leadRelease(Long.toUnsignedString(this.proxyId)), null);
		}
	}

	/** Server thread: the host gave the tether up ({"t":"unlead"}): the leash drops with its item, and the host already knows. */
	void unlead() {
		this.reportedHolder = null;
		if (this.isLeashed()) {
			this.dropLeash();
		}
	}

	/**
	 * Server thread, from FishingHook.pullEntity: reeling in a hooked proxy pulls the NPC or vehicle toward Steve's eyes in Night City
	 * (instead of vanilla's pull, which a proxy ignores). False when the host does not hear of it.
	 */
	public boolean reelIn(final @Nullable Entity angler) {
		if (angler == null || ProxyManager.idOf(this) == 0L) {
			return false;
		}

		Vec3 eyes = angler.getEyePosition();
		return ProxyManager.toOwner(this, Wire.pull(Long.toUnsignedString(this.proxyId), eyes.x, eyes.y, eyes.z,
			Tethers.pullStrength(this.getType() == ProxyEntities.VEHICLE)), angler);
	}

	/**
	 * What vanilla's LivingEntity.getKnockback (protected) works out for the attacker's weapon, with a player's sprint bonus (plan
	 * retest3 section 1.9: mobs get none). As in vanilla's Player.attack the sprint bonus needs the strong (charged) blow: a quick
	 * jab while sprinting gets none.
	 */
	private double knockbackLevel(final ServerLevel level, final DamageSource source, final LivingEntity attacker) {
		float base = (float) attacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
		return ProxyDamage.knockbackLevel(EnchantmentHelper.modifyKnockback(level, attacker.getWeaponItem(), this, source, base),
			attacker instanceof Player player && player.isSprinting() && ProxyDamage.charged(player));
	}
}
