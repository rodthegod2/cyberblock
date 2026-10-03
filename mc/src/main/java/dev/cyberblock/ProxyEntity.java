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
 * which applies it to the real NPC. Damage with no player or mob behind it (lava, suffocation, falling) is ignored.
 */
public final class ProxyEntity extends LivingEntity {
	/** Vanilla's damage cooldown: for this long only a hit stronger than the last one counts. */
	private static final int COOLDOWN_TICKS = 10;
	/** How long flint and steel or a fire charge set an NPC alight. */
	private static final float ITEM_IGNITE_SECONDS = 8.0F;
	/** Cyberpunk's NPC burns for the reported time: the same ignition is not reported again until it has run out. */
	private int igniteCooldown;
	private int damageCooldown;
	private float lastDamage;
	private long proxyId;

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
	}

	/**
	 * Every vanilla way to set an entity on fire (lava, a fire block, a flaming arrow, fire aspect, the items below) ends here
	 * with positive ticks. The proxy never burns in Minecraft: the ignition goes to the host, which burns the real NPC.
	 */
	@Override
	public void setRemainingFireTicks(final int remainingTicks) {
		if (remainingTicks <= 0 || !(this.level() instanceof ServerLevel)) {
			super.setRemainingFireTicks(remainingTicks);
		} else if (this.igniteCooldown <= 0) {
			ProxyManager.ignite(this, remainingTicks / 20.0);
			this.igniteCooldown = remainingTicks;
		}
	}

	/** Flint and steel and fire charges only light blocks in vanilla; aimed at an NPC they light the NPC. */
	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		ItemStack held = player.getItemInHand(hand);
		boolean flint = held.is(Items.FLINT_AND_STEEL);
		if (!flint && !held.is(Items.FIRE_CHARGE)) {
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
		// layer 3: a mob's own blow or projectile names the mob, so its puppet in Night City is the attacker
		int mob = attacker instanceof Mob && (kind.equals("mob") || kind.equals("arrow") || kind.equals("trident")) ? attacker.getId() : -1;
		ProxyManager.hit(this, counted, kind, this.impulse(level, source, kind, medium, attacker, direct, counted), mob);
		return true;
	}

	private Vec3 impulse(final ServerLevel level, final DamageSource source, final String kind, final ProxyDamage.Medium medium,
		final @Nullable Entity attacker, final @Nullable Entity direct, final float counted) {
		if (kind.equals("fire")) {
			return Vec3.ZERO;
		}

		if (kind.equals("explosion")) {
			Vec3 centre = source.getSourcePosition();
			return centre == null ? Vec3.ZERO : ProxyDamage.blast(this.position().add(0.0, this.getBbHeight() / 2.0, 0.0).subtract(centre), counted);
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
		return ProxyDamage.melee(along.x, along.z, attacker instanceof LivingEntity living ? this.knockbackLevel(level, source, living) : 0.0);
	}

	/** What vanilla's LivingEntity.getKnockback (protected) works out for the attacker's weapon, plus the sprint bonus. */
	private float knockbackLevel(final ServerLevel level, final DamageSource source, final LivingEntity attacker) {
		float base = (float) attacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
		return EnchantmentHelper.modifyKnockback(level, attacker.getWeaponItem(), this, source, base) / 2.0F + (attacker.isSprinting() ? 0.5F : 0.0F);
	}
}
