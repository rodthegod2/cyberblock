package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The quickhacks' Minecraft side (plan retest3 section 1.6), on the server thread. Each sent hack is a run: an upload beam in
 * the chip's colour from Steve's eyes to the target from the send until the `hackresult`, then for its `upload` seconds; then
 * the chip's own impact for its ticks (HackFxRules.impactTicks). Effects follow the live proxy of the target's owner (Bridge.proxyById,
 * megaplan task 1-C1: runs are keyed by the owner session and its request id, so two hosts' ids never mix) or, for a
 * device or a proxy that went away, the scan lock's point. A target still uploading refuses a new hack (busy). Spreads
 * (`hackspread`) play on their own targets; ones for a finished hack are taken for the last 32 ids. Everything is visual or
 * vanilla-harmless: the lightning is visual only, the clouds carry no effects and no fire block is placed, so nothing reaches
 * ExplosionEvents, ProxyDamage or MobWar; the cobwebs are real (HackWebs) and go after 6 s.
 */
public final class HackFx {
	/** Pre-result beam: drawn this long at most while Cyberpunk has not answered (the run stays busy until the answer or timeout). */
	private static final int WAIT_BEAM_TICKS = 600;
	private static final int BEAM_EVERY = 2;

	/** One sent hack. Server thread. */
	private static final class Run {
		final CyberRoute.Key key;
		final UUID player;
		final Hack hack;
		final long target;
		/** The scan lock's point (feet or device base) and the target's height there. */
		final Vec3 lockFeet;
		final double lockHeight;
		final String name;
		final long sentTick;
		final List<HackSpread> spreads = new ArrayList<>();
		String kind = "";
		/** Level game time the upload completes, Long.MAX_VALUE until the `hackresult`. */
		long uploadEnd = Long.MAX_VALUE;
		/** Level game time the impact started, -1 before. */
		long impactStart = -1L;

		Run(final CyberRoute.Key key, final UUID player, final Hack hack, final long target, final Vec3 lockFeet, final double lockHeight, final String name,
			final long sentTick) {
			this.key = key;
			this.player = player;
			this.hack = hack;
			this.target = target;
			this.lockFeet = lockFeet;
			this.lockHeight = lockHeight;
			this.name = name;
			this.sentTick = sentTick;
		}
	}

	/** Where an effect plays: the target's feet, its height, and the live proxy when there is one. */
	private record Spot(Vec3 feet, double height, @Nullable Entity entity) {
		Vec3 centre() {
			return this.feet.add(0.0, this.height / 2.0, 0.0);
		}

		Vec3 head() {
			return this.feet.add(0.0, this.height * 0.9, 0.0);
		}
	}

	private static final Map<CyberRoute.Key, Run> runs = new LinkedHashMap<>();
	/** The last MEMORY finished runs, for late spreads (ping, frenzy). */
	private static final LinkedHashMap<CyberRoute.Key, Run> finished = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<CyberRoute.Key, Run> eldest) {
			return this.size() > HackFxRules.MEMORY;
		}
	};

	private HackFx() {
	}

	/** The target of `owner`'s game is still uploading a hack (sent, impact not started): a new hack at it is refused. */
	public static boolean busy(final UUID owner, final long target) {
		for (Run run : runs.values()) {
			if (run.target == target && run.key.owner().equals(owner) && run.impactStart < 0L) {
				return true;
			}
		}

		return false;
	}

	/** A `hack` went out: the beam starts, with the chip's upload sound. */
	static void start(final CyberRoute.Key key, final ServerPlayer player, final Hack hack, final Hacking.Locked lock) {
		ServerLevel level = player.level();
		runs.put(key, new Run(key, player.getUUID(), hack, lock.target(), new Vec3(lock.x(), lock.y(), lock.z()), lock.height(), lock.name(),
			level.getGameTime()));
		sound(level, player.getEyePosition(), SoundEvents.BEACON_POWER_SELECT, 0.8F, HackFxRules.pitch(hack));
	}

	/** An ok `hackresult`: the beam runs `upload` seconds more, then the impact. */
	static void onResult(final MinecraftServer server, final CyberRoute.Key key, final HackResult result) {
		Run run = runs.get(key);
		if (run == null) {
			return;
		}

		ServerPlayer player = server.getPlayerList().getPlayer(run.player);
		long now = player == null ? 0L : player.level().getGameTime();
		run.kind = result.kind();
		run.uploadEnd = now + HackFxRules.uploadTicks(result.upload());
		Cyberblock.LOG.info("hackfx: {} on {} upload {} s", run.hack.wire(), Long.toUnsignedString(run.target), result.upload());
	}

	/** A failed or never answered hack: its beam stops, nothing lands. */
	static void cancel(final CyberRoute.Key key) {
		runs.remove(key);
	}

	/**
	 * A `hackspread` of the owner session's request `key`: kept for the impact while the hack uploads, played at once when the
	 * impact runs or ran (last 32 ids).
	 */
	public static void onSpread(final MinecraftServer server, final CyberRoute.Key key, final HackSpread spread) {
		Run run = runs.get(key);
		if (run == null) {
			run = finished.get(key);
		}

		if (run == null) {
			return;
		}

		run.spreads.add(spread);
		ServerPlayer player = server.getPlayerList().getPlayer(run.player);
		if (run.impactStart >= 0L && player != null) {
			spreadTo(player.level(), player, run, spread);
		}
	}

	/** Every server tick: beams, impacts, and the webs' expiry. */
	static void tick(final MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			HackWebs.tick(level);
		}

		for (Iterator<Run> it = runs.values().iterator(); it.hasNext(); ) {
			Run run = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(run.player);
			if (player == null) {
				it.remove();
				continue;
			}

			ServerLevel level = player.level();
			long now = level.getGameTime();
			Spot spot = spot(level, run);
			if (run.impactStart < 0L) {
				if (now >= run.uploadEnd) {
					run.impactStart = now;
				} else {
					if ((now - run.sentTick) % BEAM_EVERY == 0 && (run.uploadEnd != Long.MAX_VALUE || now - run.sentTick < WAIT_BEAM_TICKS)) {
						beam(level, player, run.hack, spot);
					}

					continue;
				}
			}

			long age = now - run.impactStart;
			if (age >= HackFxRules.impactTicks(run.hack)) {
				it.remove();
				finished.put(run.key, run);
				continue;
			}

			impact(level, player, run, spot, (int) age);
			if (age == 0L) {
				for (HackSpread spread : run.spreads) {
					spreadTo(level, player, run, spread);
				}
			}
		}
	}

	/** Server stopping or the player left: every run is forgotten. */
	static void clear() {
		runs.clear();
		finished.clear();
	}

	// ---- where -------------------------------------------------------------------------------------------------------

	private static @Nullable Entity proxy(final ServerLevel level, final UUID owner, final long target) {
		Entity e = Bridge.proxyById.at(owner, target);
		return e != null && !e.isRemoved() && e.level() == level ? e : null;
	}

	private static Spot spot(final ServerLevel level, final Run run) {
		Entity e = proxy(level, run.key.owner(), run.target);
		return e != null ? new Spot(e.position(), e.getBbHeight(), e) : new Spot(run.lockFeet, Math.max(0.5, run.lockHeight), null);
	}

	// ---- upload ------------------------------------------------------------------------------------------------------

	private static void beam(final ServerLevel level, final ServerPlayer player, final Hack hack, final Spot spot) {
		DustParticleOptions dust = new DustParticleOptions(HackFxRules.colour(hack), 0.7F);
		for (Vec3 p : HackFxRules.beamPoints(player.getEyePosition().add(0.0, -0.2, 0.0), spot.centre())) {
			level.sendParticles(dust, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ---- impacts -----------------------------------------------------------------------------------------------------

	/** One tick of the chip's impact (section 1.6); `age` 0 is the moment the upload completes. */
	private static void impact(final ServerLevel level, final ServerPlayer player, final Run run, final Spot spot, final int age) {
		Vec3 c = spot.centre();
		Vec3 head = spot.head();
		switch (run.hack) {
			case SHORT_CIRCUIT -> {
				if (age == 0) {
					LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
					if (bolt != null) {
						bolt.setVisualOnly(true);
						bolt.snapTo(spot.feet());
						level.addFreshEntity(bolt);
					}

					burst(level, ParticleTypes.ELECTRIC_SPARK, c, 30, 0.4, 0.3);
					burst(level, dust(run.hack, 1.2F), c, 20, 0.4, 0.0);
				} else if (age % 4 == 0) {
					burst(level, ParticleTypes.ELECTRIC_SPARK, c, 6, 0.3, 0.1);
					burst(level, dust(run.hack, 0.9F), c, 4, 0.3, 0.0);
				}
			}
			case OVERHEAT -> {
				if (age == 0) {
					sound(level, c, SoundEvents.BLAZE_SHOOT, 1.0F, 1.0F);
					burst(level, ParticleTypes.LAVA, c, 8, 0.3, 0.0);
				}

				if (age % 2 == 0) {
					double a = age * 0.6;
					double y = spot.height() * ((age % 20) / 20.0);
					for (int i = 0; i < 3; i++) {
						double b = a + i * Math.PI * 2.0 / 3.0;
						Vec3 p = spot.feet().add(Math.cos(b) * 0.5, y, Math.sin(b) * 0.5);
						level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.0, 0.02, 0.0, 0.0);
					}

					level.sendParticles(ParticleTypes.SMOKE, head.x, head.y, head.z, 1, 0.2, 0.1, 0.2, 0.01);
				}

				if (age % 10 == 5) {
					burst(level, ParticleTypes.LAVA, c, 2, 0.3, 0.0);
				}
			}
			case REBOOT_OPTICS -> {
				if (age == 0) {
					sound(level, head, SoundEvents.SQUID_SQUIRT, 1.0F, 1.0F);
					burst(level, ParticleTypes.SQUID_INK, head, 25, 0.25, 0.08);
				}

				if (age % 4 == 0) {
					ring(level, ParticleTypes.SQUID_INK, head.add(0.0, 0.15, 0.0), 0.45, 8, age * 0.2);
				}
			}
			case CRIPPLE_MOVEMENT -> {
				if (age == 0) {
					sound(level, c, SoundEvents.COBWEB_PLACE, 1.0F, 0.8F);
					sound(level, c, SoundEvents.SPIDER_AMBIENT, 1.0F, 1.0F);
					burst(level, ParticleTypes.ITEM_COBWEB, c, 20, 0.5, 0.05);
					HackWebs.place(level, BlockPos.containing(spot.feet().add(0.0, 0.1, 0.0)));
				} else if (age % 10 == 0) {
					burst(level, ParticleTypes.ITEM_COBWEB, c, 4, 0.4, 0.02);
				}
			}
			case CONTAGION -> {
				if (age == 0) {
					sound(level, c, SoundEvents.SPLASH_POTION_BREAK, 1.0F, 0.9F);
					cloud(level, player, spot.feet());
				} else if (age % 10 == 0) {
					burst(level, dust(run.hack, 1.0F), head.add(0.0, 0.3, 0.0), 3, 0.2, 0.0);
				}
			}
			case SYNAPSE_BURNOUT -> {
				Vec3 eye = player.getEyePosition();
				if (age == 0) {
					sound(level, eye, SoundEvents.WITHER_SHOOT, 0.7F, 1.2F);
				}

				if (age < 8) {
					Vec3 p = eye.lerp(c, (age + 1) / 8.0);
					level.sendParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
					level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
				} else if (age == 8) {
					sound(level, c, SoundEvents.WITHER_SKELETON_HURT, 1.0F, 0.8F);
					burst(level, ParticleTypes.SCULK_SOUL, c, 20, 0.4, 0.05);
					burst(level, ParticleTypes.LARGE_SMOKE, c, 15, 0.4, 0.03);
				} else if (age % 5 == 0) {
					burst(level, ParticleTypes.SOUL, head, 2, 0.2, 0.02);
				}
			}
			case PING -> {
				if (age == 0) {
					sound(level, c, SoundEvents.LODESTONE_COMPASS_LOCK, 1.0F, 1.0F);
					ping(level, spot);
				}

				if (age < 60 && age % 4 == 0) {
					for (double y = 0.0; y <= 6.0; y += 0.5) {
						level.sendParticles(ParticleTypes.END_ROD, spot.feet().x, spot.feet().y + y, spot.feet().z, 1, 0.0, 0.0, 0.0, 0.0);
					}
				}
			}
			case MEMORY_WIPE -> {
				if (age == 0) {
					sound(level, head, SoundEvents.BOTTLE_EMPTY, 1.0F, 1.0F);
					sound(level, head, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F, 1.0F);
				}

				if (age % 10 == 0 && spot.entity() != null) {
					forget(level, spot.entity());
				}

				if (age % 2 == 0) {
					double a = age * 0.5;
					for (int i = 0; i < 2; i++) {
						double b = a + i * Math.PI;
						Vec3 p = head.add(Math.cos(b) * 0.6, 0.2 + 0.1 * Math.sin(a), Math.sin(b) * 0.6);
						level.sendParticles(i == 0 ? ParticleTypes.WITCH : ParticleTypes.ENCHANT, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
					}
				}
			}
			case CYBERPSYCHOSIS -> {
				if (age == 0) {
					sound(level, c, SoundEvents.CREEPER_PRIMED, 1.0F, 0.8F);
				}

				if (age % 10 == 0) {
					mask(level, player, head.add(0.0, 0.7, 0.0), dust(run.hack, 0.9F));
				}
			}
			case DEVICE -> device(level, run.kind, c, age);
		}
	}

	/** Redstone Lever by `kind`: camera, traffic, distract, or any other device. */
	private static void device(final ServerLevel level, final String kind, final Vec3 c, final int age) {
		switch (kind) {
			case "camera" -> {
				if (age == 0) {
					sound(level, c, SoundEvents.LEVER_CLICK, 1.0F, 0.6F);
					burst(level, ParticleTypes.LARGE_SMOKE, c, 10, 0.2, 0.01);
					burst(level, DustParticleOptions.REDSTONE, c, 10, 0.2, 0.0);
				} else if (age % 8 == 0) {
					burst(level, ParticleTypes.SMOKE, c, 3, 0.15, 0.01);
				}
			}
			case "traffic" -> {
				if (age == 0) {
					sound(level, c, SoundEvents.BELL_BLOCK, 1.0F, 1.0F);
				}

				if (age % 5 == 0) {
					burst(level, new DustParticleOptions((age / 5) % 2 == 0 ? 0xFF2B2B : 0x3BFF4A, 1.2F), c, 6, 0.2, 0.0);
				}
			}
			case "distract" -> {
				if (age % 5 == 0) {
					float pitch = (float) Math.pow(2.0, (level.getRandom().nextInt(25) - 12) / 12.0);
					level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.0F, pitch);
					level.sendParticles(ParticleTypes.NOTE, c.x, c.y + 0.5, c.z, 0, level.getRandom().nextInt(25) / 24.0, 0.0, 0.0, 1.0);
				}
			}
			default -> {
				if (age == 0) {
					sound(level, c, SoundEvents.LEVER_CLICK, 1.0F, 0.8F);
					burst(level, DustParticleOptions.REDSTONE, c, 12, 0.3, 0.0);
				}
			}
		}
	}

	/** One spread target: chain plays the chip's opening at it, spread a cloud and a green arc, ping outlines it, frenzy angers. */
	private static void spreadTo(final ServerLevel level, final ServerPlayer player, final Run run, final HackSpread spread) {
		Entity e = proxy(level, run.key.owner(), spread.target());
		if (e == null) {
			return;
		}

		Spot to = new Spot(e.position(), e.getBbHeight(), e);
		switch (spread.why()) {
			case "chain" -> impact(level, player, run, to, 0);
			case "spread" -> {
				cloud(level, player, to.feet());
				arc(level, spot(level, run).centre(), to.centre(), dust(Hack.CONTAGION, 1.0F));
			}
			case "ping" -> pingOne(level, e);
			case "frenzy" -> burst(level, ParticleTypes.ANGRY_VILLAGER, to.head().add(0.0, 0.4, 0.0), 3, 0.3, 0.0);
			default -> {
			}
		}
	}

	/** Compass Ping: hostile proxies and vanilla Enemy mobs within 32 blocks of the target, pinged / glowing 200 ticks. */
	private static void ping(final ServerLevel level, final Spot spot) {
		AABB box = new AABB(spot.feet(), spot.feet()).inflate(HackFxRules.PING_RADIUS);
		double r2 = HackFxRules.PING_RADIUS * HackFxRules.PING_RADIUS;
		for (Entity e : level.getEntities((Entity) null, box, e -> !e.isRemoved() && e.distanceToSqr(spot.feet()) <= r2)) {
			if (Bridge.proxyIdOf.applyAsLong(e) != 0L && Bridge.hostileProxy.test(e)) {
				pingOne(level, e);
			} else if (e instanceof Mob mob && e instanceof Enemy) {
				mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, HackFxRules.PING_TICKS, 0, false, false));
			}
		}

		if (spot.entity() != null) {
			pingOne(level, spot.entity());
		}
	}

	private static void pingOne(final ServerLevel level, final Entity e) {
		Bridge.pingProxy.accept(e, HackFxRules.PING_TICKS);
		level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 3, 0.1, 0.2, 0.1, 0.0);
	}

	/** Bottle of Forgetting: mobs hunting this proxy lose it. */
	private static void forget(final ServerLevel level, final Entity target) {
		for (Mob mob : level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(32.0), m -> m.getTarget() == target)) {
			mob.setTarget(null);
		}
	}

	/** Spider Eye Virus's cloud: green, radius 2.5 shrinking over 6 s, owned by Steve, with no effects at all. */
	private static void cloud(final ServerLevel level, final ServerPlayer owner, final Vec3 feet) {
		AreaEffectCloud cloud = new AreaEffectCloud(level, feet.x, feet.y, feet.z);
		cloud.setOwner(owner);
		cloud.setRadius(2.5F);
		cloud.setDuration(120);
		cloud.setWaitTime(0);
		cloud.setRadiusPerTick(-2.0F / 120.0F);
		cloud.setCustomParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, ARGB.opaque(HackFxRules.colour(Hack.CONTAGION))));
		level.addFreshEntity(cloud);
	}

	/** The creeper mask's pixels on a plane above the head, turned to face Steve. */
	private static void mask(final ServerLevel level, final ServerPlayer player, final Vec3 centre, final ParticleOptions dust) {
		Vec3 toSteve = player.position().subtract(centre).multiply(1.0, 0.0, 1.0);
		Vec3 right = toSteve.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(-toSteve.z, 0.0, toSteve.x).normalize();
		for (double[] px : HackFxRules.maskPixels(0.1)) {
			Vec3 p = centre.add(right.scale(px[0])).add(0.0, px[1], 0.0);
			level.sendParticles(dust, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ---- helpers -----------------------------------------------------------------------------------------------------

	private static DustParticleOptions dust(final Hack hack, final float scale) {
		return new DustParticleOptions(HackFxRules.colour(hack), scale);
	}

	private static void burst(final ServerLevel level, final ParticleOptions particle, final Vec3 at, final int count, final double spread, final double speed) {
		level.sendParticles(particle, at.x, at.y, at.z, count, spread, spread, spread, speed);
	}

	private static void ring(final ServerLevel level, final ParticleOptions particle, final Vec3 centre, final double radius, final int points, final double phase) {
		for (int i = 0; i < points; i++) {
			double a = phase + Math.PI * 2.0 * i / points;
			level.sendParticles(particle, centre.x + Math.cos(a) * radius, centre.y, centre.z + Math.sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** A particle arc from one target to another, bowed up by a quarter of the distance. */
	private static void arc(final ServerLevel level, final Vec3 from, final Vec3 to, final ParticleOptions particle) {
		double bow = from.distanceTo(to) * 0.25;
		List<Vec3> points = HackFxRules.beamPoints(from, to);
		for (int i = 0; i < points.size(); i++) {
			double t = (i + 1.0) / points.size();
			Vec3 p = points.get(i).add(0.0, Math.sin(Math.PI * t) * bow, 0.0);
			level.sendParticles(particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static void sound(final ServerLevel level, final Vec3 at, final SoundEvent sound, final float volume, final float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}
}
