package dev.cyberblock;

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
 * (layer 5) is reported as {"t":"lead"} and the host drags the NPC; a fishing rod reeling it in is {"t":"pull"}.
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
	/** Hostile to V as far as the host knows (its latest list): the mobs hunt these first. */
	private boolean hostile;

	public ProxyEntity(final EntityType<? extends ProxyEntity> type, final Level level) {
		super(type, level);
		this.setInvisible(true);
		this.setNoGravity(true);
	}

	/** The Cyberpunk entity id hash this proxy stands for. */
	void setProxyId(final long id) {
		this.proxyId = id;
	}

	long proxyId() {
		return this.proxyId;
	}

	void setHostile(final boolean hostile) {
		this.hostile = hostile;
	}

	boolean isHostile() {
		return this.hostile;
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

		if (this.level() instanceof ServerLevel) {
			this.reportLeash();
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
		} else if (ProxyDamage.igniteDue(this.igniteCooldown, this.igniteReported, remainingTicks)) {
			ProxyManager.ignite(this, remainingTicks / 20.0);
			this.igniteCooldown = remainingTicks;
			this.igniteReported = remainingTicks;
		}
	}

	/** Flint and steel and fire charges only light blocks in vanilla; aimed at an NPC they light the NPC. */
	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		ItemStack held = player.getItemInHand(hand);
		boolean flint = held.is(Items.FLINT_AND_STEEL);
		// a vehicle does not burn: the item stays unused (and is not worn or consumed)
		if (this.getType() == ProxyEntities.VEHICLE || !flint && !held.is(Items.FIRE_CHARGE)) {
			return super.interact(player, hand, location);
		}

		if (this.level() instanceof ServerLevel level) {
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
		ProxyDamage.Actor actor = attacker instanceof Player ? ProxyDamage.Actor.PLAYER : attacker instanceof Mob ? ProxyDamage.Actor.MOB : ProxyDamage.Actor.OTHER;
		Entity direct = source.getDirectEntity();
		ProxyDamage.Medium medium = direct instanceof ThrownTrident ? ProxyDamage.Medium.TRIDENT
			: direct instanceof AbstractArrow ? ProxyDamage.Medium.ARROW
			: direct instanceof Projectile ? ProxyDamage.Medium.PROJECTILE : ProxyDamage.Medium.BODY;
		// no kind for an explosion (TNT, creeper, firework...): the host's real Cyberpunk explosion is what hurts the NPC, and this one is consumed
		String kind = ProxyDamage.kind(source.is(DamageTypeTags.IS_EXPLOSION), source.is(DamageTypeTags.IS_FIRE), medium, actor);
		if (kind == null || !(damage > 0.0F) || !Float.isFinite(damage)) {
			return false;
		}

		float counted = ProxyDamage.afterCooldown(damage, this.lastDamage, this.damageCooldown > 0);
		if (!(counted > 0.0F)) {
			return false;
		}

		this.lastDamage = damage;
		this.damageCooldown = COOLDOWN_TICKS;
		// the headhunter perk: Steve's arrow in a pedestrian's head (its top 0.35 m) counts double
		if (medium == ProxyDamage.Medium.ARROW && actor == ProxyDamage.Actor.PLAYER && direct != null && this.getType() == ProxyEntities.HUMAN
			&& ProxyDamage.headshot(direct.getY(), this.getY(), this.getBbHeight()) && perk("headhunter")) {
			counted *= ProxyDamage.HEADSHOT;
		}

		// layer 3: whatever a mob deals (blow, arrow, trident, a ghast's fireball) names the mob, so its puppet in Night City is the attacker
		int mob = attacker instanceof Mob ? attacker.getId() : -1;
		ProxyManager.hit(this, counted, kind, this.impulse(level, source, kind, medium, attacker, direct), mob);
		return true;
	}

	private Vec3 impulse(final ServerLevel level, final DamageSource source, final String kind, final ProxyDamage.Medium medium,
		final @Nullable Entity attacker, final @Nullable Entity direct) {
		if (kind.equals("fire")) {
			return Vec3.ZERO;
		}

		if (medium != ProxyDamage.Medium.BODY) {
			return direct == null ? Vec3.ZERO : ProxyDamage.projectile(direct.getDeltaMovement());
		}

		if (attacker == null) {
			return Vec3.ZERO;
		}

		// vanilla pushes along the attacker's yaw; looking straight up or down there is no direction, then it is away from the attacker
		Vec3 look = attacker.getLookAngle();
		Vec3 along = Math.hypot(look.x, look.z) > 1.0E-3 ? look : this.position().subtract(attacker.position());
		Vec3 melee = ProxyDamage.melee(along.x, along.z, attacker instanceof LivingEntity living ? this.knockbackLevel(level, source, living) : 0.0);
		return attacker instanceof Player && perk("gorilla_arms") ? ProxyDamage.gorillaArms(melee, this.getType() == ProxyEntities.VEHICLE) : melee;
	}

	/** Server thread: Steve owns this perk (plan section 1.5; the cyber track keeps the ranks). */
	private static boolean perk(final String id) {
		return Bridge.perkRank.applyAsInt(id) > 0;
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
		if (!Cyberblock.active) {
			return;
		}

		String id = Long.toUnsignedString(this.proxyId);
		Cyberblock.events.accept(holder == null ? Wire.leadRelease(id)
			: Wire.lead(id, holder instanceof Player ? null : new double[] {holder.getX(), holder.getY(), holder.getZ()},
				Tethers.leadLength(this.getType() == ProxyEntities.VEHICLE)));
	}

	/** Server thread, as ProxyManager removes the proxy: a leash the host was told of is reported released. */
	void reportRemoved() {
		Entity holder = this.reportedHolder;
		this.reportedHolder = null;
		if (holder != null && Cyberblock.active) {
			Cyberblock.events.accept(Wire.leadRelease(Long.toUnsignedString(this.proxyId)));
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
	public boolean reelIn(final @Nullable Entity owner) {
		if (owner == null || !Cyberblock.active || ProxyManager.idOf(this) == 0L) {
			return false;
		}

		Vec3 eyes = owner.getEyePosition();
		Cyberblock.events.accept(Wire.pull(Long.toUnsignedString(this.proxyId), eyes.x, eyes.y, eyes.z,
			Tethers.pullStrength(this.getType() == ProxyEntities.VEHICLE)));
		return true;
	}

	/** What vanilla's LivingEntity.getKnockback (protected) works out for the attacker's weapon, plus the sprint bonus. */
	private float knockbackLevel(final ServerLevel level, final DamageSource source, final LivingEntity attacker) {
		float base = (float) attacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
		return EnchantmentHelper.modifyKnockback(level, attacker.getWeaponItem(), this, source, base) / 2.0F + (attacker.isSprinting() ? 0.5F : 0.0F);
	}
}
