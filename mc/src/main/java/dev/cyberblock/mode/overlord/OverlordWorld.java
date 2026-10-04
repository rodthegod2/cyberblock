package dev.cyberblock.mode.overlord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import dev.cyberblock.mixin.MobAccessor;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the Overlord does in the world (megaplan 8-O1): the warning (sound, marker, action-bar line), Minecraft squads, Night City
 * spawns through {@code spawn} DOWN (with {@link NcSpawns}'s runtime check), the seven events and the upkeep of what it spawned.
 * Server thread.
 *
 * <p>Events in Minecraft: blackout = Darkness; EMP = Slowness and Mining Fatigue (with {@code cyberware_off} also Weakness: Minecraft
 * has no way to switch implants off for a while) plus {@code glitch} DOWN to a linked player's own game (at most 10 s); airstrike =
 * explosions that break no blocks; traffic jam = Slowness III; supply drop = items falling at the spot (its eddies only for the
 * match's opted-in players, capped); weather = the server's weather (Phase 3's time-and-weather sync from the primary host may change
 * it again); outbreak = squads of a palette entry. With {@code night_city}, a blackout and a traffic jam also reach the host games in
 * the radius as the real {@code blackout} and {@code gridlock} remote hacks, and an airstrike as real explosions. Only the match's
 * opted-in players get the effects, and a host's game gets nothing from the Overlord (spawn, hack, glitch or explosion) unless that
 * host is one of them and its {@code spawns} toggle is on ({@link HostRules}).
 */
final class OverlordWorld {
	/** A Night City spawn goes to an opted-in host within this many blocks of the spot (see {@link HostRules#spawnHost}). */
	static final double NC_RADIUS = 64.0;
	/** A Night City spawn counts toward the cap this long (the server cannot see those NPCs die). */
	static final long NC_LIVE_MS = 60_000L;
	static final int MARKER = 0xFF2040;
	private static final Set<String> warnedMobs = new HashSet<>();

	private record Strike(ServerLevel level, Vec3 at, float power, long tick, Blast blast) {
	}

	private static final List<Strike> strikes = new ArrayList<>();

	private OverlordWorld() {
	}

	/**
	 * The ground at (x, z): the top of the highest motion-blocking block, or null when the chunk is not loaded or the column has no block
	 * (on the void world: no replica under the spot).
	 */
	static @Nullable Vec3 ground(final ServerLevel level, final double x, final double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		if (!level.isLoaded(column)) {
			return null;
		}

		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
		return hasGround(top, level.getMinY()) ? new Vec3(x, top, z) : null;
	}

	/** A column's heightmap top is above the world's bottom only when the column holds a block. */
	static boolean hasGround(final int top, final int minY) {
		return top > minY;
	}

	/**
	 * The damage of the Overlord's blasts (airstrikes and its mobs' explosions, which break no blocks): only the match's opted-in players,
	 * the Overlord's own mobs and monsters are hurt or pushed; item frames, paintings, armour stands, dropped items, pets and every other
	 * player are left alone. {@code nightCity}: the blast also goes to the host games that consent ({@link HostRules#explosion}).
	 */
	static final class Blast extends ExplosionDamageCalculator {
		final String match;
		final @Nullable UUID by;
		final boolean nightCity;

		Blast(final String match, final @Nullable UUID by, final boolean nightCity) {
			this.match = match;
			this.by = by;
			this.nightCity = nightCity;
		}

		@Override
		public boolean shouldDamageEntity(final Explosion explosion, final Entity entity) {
			return Overlord.mayHit(entity, this.match);
		}

		@Override
		public float getKnockbackMultiplier(final Entity entity) {
			return Overlord.mayHit(entity, this.match) ? super.getKnockbackMultiplier(entity) : 0.0F;
		}

		/** Does this host's game get the blast as a real explosion? */
		boolean hears(final HostSession host) {
			ServerPlayer p = host.player();
			boolean own = host.uuid().equals(this.by);
			return this.nightCity && p != null && HostRules.explosion(Overlord.eligible(p, this.match), own, host.perms().has(Permissions.Toggle.SPAWNS),
				host.perms().has(Permissions.Toggle.EXPLOSIONS));
		}
	}

	/** The intensity's scale for an event that scales with it (counts, power and seconds). */
	static double scale(final OverlordContent.EventEntry e, final Intensity intensity) {
		if (!e.scales()) {
			return 1.0;
		}

		return switch (intensity) {
			case CHILL -> 0.75;
			case NORMAL -> 1.0;
			case BRUTAL -> 1.5;
		};
	}

	static int scaled(final int v, final double scale, final int min, final int max) {
		return Math.max(min, Math.min(max, (int) Math.round(v * scale)));
	}

	/** Mobs an event adds (an outbreak's squads), for the cap. */
	static int eventUnits(final OverlordContent.EventEntry e, final Intensity intensity) {
		if (!e.effect().type().equals("outbreak") || e.effect().spawn() == null) {
			return 0;
		}

		OverlordContent.SpawnEntry s = OverlordContent.spawns().get(e.effect().spawn());
		int per = s == null ? 0 : Math.max(s.count(), s.squadCount());
		return per * scaled(e.effect().count(), scale(e, intensity), 1, 4);
	}

	// ---- the warning ----

	/** At queueing: the sound at the spot and at each player it is aimed at, their action-bar line, and the first marker. */
	static void warn(final MinecraftServer s, final Overlord.MatchState m, final Overlord.Pending p) {
		ServerLevel level = p.level();
		level.playSound(null, p.at().x, p.at().y, p.at().z, SoundEvents.TRIAL_SPAWNER_DETECT_PLAYER, SoundSource.HOSTILE, 3.0F, 0.7F);
		int seconds = (int) Math.ceil((p.due() - s.getTickCount()) / 20.0);
		for (ServerPlayer player : level.players()) {
			double d = Math.hypot(player.getX() - p.at().x, player.getZ() - p.at().z);
			if (Overlord.eligible(player, m.id) && d <= Fairness.REACH + p.radius()) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_BELL, SoundSource.HOSTILE, 1.0F, 0.5F);
				player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.overlord.warning", "Overlord: %s in %s s, %s m %s", p.name(), seconds,
					(int) d, compass(p.at().x - player.getX(), p.at().z - player.getZ())));
			}
		}

		marker(p);
	}

	/** N, NE, E, ... for a direction in Minecraft's x/z (north is -z). */
	static String compass(final double dx, final double dz) {
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		double deg = Math.toDegrees(Math.atan2(-dx, dz));
		return names[Math.floorMod((int) Math.round(deg / 45.0), 8)];
	}

	/** The marker: a red column at the spot and, for an event, a ring at its radius (seen from afar). */
	static void marker(final Overlord.Pending p) {
		ServerLevel level = p.level();
		DustParticleOptions dust = new DustParticleOptions(MARKER, 2.0F);
		for (int y = 0; y < 8; y++) {
			level.sendParticles(dust, true, true, p.at().x, p.at().y + 0.3 + y * 0.75, p.at().z, 2, 0.1, 0.1, 0.1, 0.0);
		}

		int r = Math.max(2, Math.min(p.radius(), 32));
		int points = Math.min(48, 8 + r * 2);
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2.0 / points;
			level.sendParticles(dust, true, true, p.at().x + Math.cos(a) * r, p.at().y + 0.3, p.at().z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ---- landing ----

	/** The warning is over: the spawn or event happens, if it is still fair (else it is cancelled and its points come back). */
	static void land(final MinecraftServer s, final Overlord.MatchState m, final Overlord.Pending p) {
		Fairness.Refusal why = Fairness.check(new Fairness.Ask(p.at().x, p.at().z, p.units(), m.live(), m.intensity,
			Overlord.whoNear(p.level(), m.id, p.at().x, p.at().z), Overlord.area(s, m.id), p.radius(), !p.event()));
		if (why != null) {
			m.points.refund(p.cost(), m.intensity);
			m.line("Cancelled: " + p.name() + " (" + why.text() + ")");
			Cyberblock.LOG.info("overlord: {} {} cancelled at landing ({})", p.event() ? "event" : "spawn", p.id(), why.text());
			return;
		}

		if (p.event()) {
			OverlordContent.EventEntry e = OverlordContent.events().get(p.id());
			if (e == null) {
				m.points.refund(p.cost(), m.intensity);
				return;
			}

			event(s, m, p, e);
			return;
		}

		OverlordContent.SpawnEntry e = OverlordContent.spawns().get(p.id());
		if (e == null) {
			m.points.refund(p.cost(), m.intensity);
			return;
		}

		spawn(m, p.level(), p.at(), p.id(), e, p.by());
	}

	/**
	 * One palette entry at the spot: in the game of the opted-in host the spot is aimed at ({@link HostRules#spawnHost}) when its toggle
	 * allows it, else as its Minecraft squad.
	 */
	static void spawn(final Overlord.MatchState m, final ServerLevel level, final Vec3 at, final Identifier id, final OverlordContent.SpawnEntry e,
		final @Nullable UUID by) {
		HostSession host = e.nightCity() ? spawnHost(m, level, at) : null;
		String why;
		if (!e.nightCity()) {
			why = "Minecraft squad";
		} else if (host == null) {
			why = "Minecraft squad: the spot is not aimed at an opted-in Cyberpunk player of the match within " + (int) NC_RADIUS + " blocks";
		} else if (!HostRules.consents(true, host.uuid().equals(by), host.perms().has(Permissions.Toggle.SPAWNS))) {
			why = "Minecraft squad: " + host.name() + "'s spawns toggle is off";
		} else if (!Overlord.nightCity.usable(host.uuid())) {
			why = "Minecraft squad: Night City spawns fall back for " + host.name();
		} else {
			nightCitySpawn(m, level, at, id, e, host, by);
			return;
		}

		int n = squad(m, level, at, e.squadMob(), e.squadCount(), e.hostile());
		m.line(String.format(Locale.ROOT, "%s x%d landed (%s)", e.name(), n, why));
		Cyberblock.LOG.info("overlord: spawn {} landed ({}, {} mobs)", id, why, n);
	}

	/** The host a Night City spawn at the spot goes to: among the match's opted-in hosts in this level only. */
	private static @Nullable HostSession spawnHost(final Overlord.MatchState m, final ServerLevel level, final Vec3 at) {
		List<HostRules.Host> hosts = new ArrayList<>();
		for (HostSession h : HostSessions.active()) {
			ServerPlayer p = h.player();
			if (p != null && p.level() == level) {
				hosts.add(new HostRules.Host(h.uuid(), p.getX(), p.getZ(), Overlord.eligible(p, m.id)));
			}
		}

		HostRules.Host pick = HostRules.spawnHost(hosts, Overlord.whoNear(level, m.id, at.x, at.z), at.x, at.z, NC_RADIUS);
		return pick == null ? null : HostSessions.of(pick.id());
	}

	private static void nightCitySpawn(final Overlord.MatchState m, final ServerLevel level, final Vec3 at, final Identifier id,
		final OverlordContent.SpawnEntry e, final HostSession host, final @Nullable UUID by) {
		long now = System.currentTimeMillis();
		int request = host.newRequest(by != null ? by : host.uuid());
		String key = host.uuid() + "#" + request;
		m.nightCity.put(key, new long[] {now + NcSpawns.ANSWER_MS, e.count()});
		JsonObject msg = new JsonObject();
		msg.addProperty("t", "spawn");
		msg.addProperty("id", request);
		msg.addProperty("rec", NcSpawns.wireRec(e.rec(), e.heavy()));
		JsonArray p = new JsonArray();
		p.add(at.x);
		p.add(at.y);
		p.add(at.z);
		msg.add("p", p);
		msg.addProperty("n", e.count());
		msg.addProperty("hostile", e.hostile());
		msg.addProperty("faction", e.faction());

		host.send(msg.toString());
		String hostName = host.name();
		Overlord.nightCity.sent(host.uuid(), hostName, request, now, () -> {
			m.nightCity.remove(key);
			if (Overlord.active(m)) {
				int n = squad(m, level, at, e.squadMob(), e.squadCount(), e.hostile());
				m.line(String.format(Locale.ROOT, "%s x%d landed (Minecraft squad: %s's game did not spawn it)", e.name(), n, hostName));
				Cyberblock.LOG.info("overlord: spawn {} landed (Minecraft squad, fallback from {}, {} mobs)", id, hostName, n);
			}
		}, n -> {
			m.nightCity.put(key, new long[] {System.currentTimeMillis() + NC_LIVE_MS, n});
			m.line(String.format(Locale.ROOT, "%s x%d landed in %s's Night City", e.name(), n, hostName));
			Cyberblock.LOG.info("overlord: spawn {} landed (Night City, {}'s game, {})", id, hostName, n);
		});
	}

	/** The host's {@code spawnresult {id, ok, n, reason}} (UP, checked by the relay: the id is one this session issued). */
	static void onSpawnResult(final HostSession host, final JsonObject m) {
		JsonElement id = m.get("id"), ok = m.get("ok"), n = m.get("n"), reason = m.get("reason");
		if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber() || ok == null || !ok.isJsonPrimitive()
			|| !ok.getAsJsonPrimitive().isBoolean()) {
			return;
		}

		int count = n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber() ? Math.max(0, Math.min(8, n.getAsInt())) : 0;
		String why = reason != null && reason.isJsonPrimitive() && reason.getAsJsonPrimitive().isString() ? reason.getAsString() : "";
		if (why.length() > 128) {
			why = why.substring(0, 128);
		}

		if (!Overlord.nightCity.answered(host.uuid(), host.name(), id.getAsInt(), ok.getAsBoolean(), count, why) && !ok.getAsBoolean()) {
			Cyberblock.LOG.debug("overlord: spawnresult {} from {} matched no spawn", id, host.name());
		}
	}

	/** A Minecraft squad of {@code count} {@code mob}s around the spot; returns how many were placed. */
	static int squad(final Overlord.MatchState m, final ServerLevel level, final Vec3 at, final Identifier mob, final int count, final boolean hostile) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.containsKey(mob) ? BuiltInRegistries.ENTITY_TYPE.getValue(mob) : null;
		if (type == null) {
			once(mob + " is not an entity type");
			return 0;
		}

		int placed = 0;
		for (int i = 0; i < count; i++) {
			double a = i * Math.PI * 2.0 / count;
			Vec3 spot = count == 1 ? at : ground(level, at.x + Math.cos(a) * 1.5, at.z + Math.sin(a) * 1.5);
			Entity entity = type.create(level, EntitySpawnReason.EVENT);
			if (!(entity instanceof Mob mobEntity)) {
				if (entity != null) {
					entity.discard();
				}

				once(type.canSpawn(level) ? mob + " is not a mob" : mob + " cannot spawn here (Peaceful difficulty, or its feature is off)");
				return placed;
			}

			Vec3 p = spot == null ? at : spot;
			mobEntity.snapTo(p.x, p.y, p.z, (float) (Math.toDegrees(a) + 180.0), 0.0F);
			mobEntity.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(p)), EntitySpawnReason.EVENT, null);
			mobEntity.setPersistenceRequired();
			mobEntity.addTag(Overlord.TAG);
			aimAtMatch(mobEntity, m.id, hostile);
			if (hostile) {
				ServerPlayer target = nearestEligible(level, m.id, p, 48.0);
				if (target != null) {
					mobEntity.setTarget(target);
				}
			}

			// tracked before it is added: the entity-load listener removes tagged mobs that no match tracks
			m.mobs.add(mobEntity.getUUID());
			if (level.addFreshEntity(mobEntity)) {
				placed++;
			} else {
				m.mobs.remove(mobEntity.getUUID());
			}
		}

		return placed;
	}

	/**
	 * An Overlord mob looks for the match's opted-in players only (its vanilla "attack the nearest player, villager, golem" goals go; a
	 * hostile one gets one for opted-in players) and breaks no doors. Mobs that pick targets with a brain instead of goals keep theirs:
	 * {@link #keep} turns them back and {@link Overlord#allowDamage} keeps them from hurting anyone else.
	 */
	private static void aimAtMatch(final Mob mob, final String match, final boolean hostile) {
		GoalSelector targets = ((MobAccessor) mob).cyberblock$targetSelector();
		targets.removeAllGoals(g -> g instanceof NearestAttackableTargetGoal<?>);
		if (hostile) {
			targets.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, true, (target, level) -> target instanceof ServerPlayer p
				&& Overlord.eligible(p, match)));
		}

		if (mob instanceof Zombie zombie) {
			zombie.setCanBreakDoors(false);
		}
	}

	private static void once(final String why) {
		if (warnedMobs.add(why)) {
			Cyberblock.LOG.warn("overlord: {}: that spawn_palette entry places nothing", why);
		}
	}

	static @Nullable ServerPlayer nearestEligible(final ServerLevel level, final String match, final Vec3 at, final double radius) {
		ServerPlayer best = null;
		double bestD = radius;
		for (ServerPlayer p : level.players()) {
			double d = p.position().distanceTo(at);
			if (d <= bestD && Overlord.eligible(p, match) && p.isAlive()) {
				best = p;
				bestD = d;
			}
		}

		return best;
	}

	// ---- events ----

	private static void event(final MinecraftServer s, final Overlord.MatchState m, final Overlord.Pending p, final OverlordContent.EventEntry e) {
		ServerLevel level = p.level();
		OverlordContent.Effect fx = e.effect();
		double k = scale(e, m.intensity);
		List<ServerPlayer> hit = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (Overlord.eligible(player, m.id) && Math.hypot(player.getX() - p.at().x, player.getZ() - p.at().z) <= e.radius()) {
				hit.add(player);
			}
		}

		int ticks = scaled(fx.seconds(), k, 1, 600) * 20;
		String detail = switch (fx.type()) {
			case "blackout" -> {
				hit.forEach(player -> player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0)));
				yield hit.size() + " players in the dark" + nightCityHack(m, e, p, RemoteHack.BLACKOUT, ticks / 20);
			}
			case "emp" -> {
				int glitched = 0;
				for (ServerPlayer player : hit) {
					player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 0));
					player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, ticks, 0));
					if (fx.cyberwareOff()) {
						player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0));
					}

					// the glitch reaches a linked player's own game only with that host's spawns toggle (contract 1.6)
					HostSession session = HostSessions.of(player);
					if (session != null && session.active() && HostRules.consents(true, session.uuid().equals(p.by()),
						session.perms().has(Permissions.Toggle.SPAWNS))) {
						session.send("{\"t\":\"glitch\",\"seconds\":" + Math.min(10, ticks / 20) + "}");
						glitched++;
					}
				}

				yield hit.size() + " players hit" + (glitched == 0 ? "" : ", glitch in " + glitched + " host game(s)");
			}
			case "traffic_jam" -> {
				hit.forEach(player -> player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 2)));
				yield hit.size() + " players stuck" + nightCityHack(m, e, p, RemoteHack.GRIDLOCK, ticks / 20);
			}
			case "airstrike" -> {
				int n = scaled(fx.strikes(), k, 1, 20);
				float power = (float) Math.min(4.0, fx.power() * k);
				long tick = s.getTickCount();
				Blast blast = new Blast(m.id, p.by(), e.nightCity());
				for (int i = 0; i < n; i++) {
					double a = Math.random() * Math.PI * 2.0, r = Math.random() * fx.spread();
					Vec3 at = ground(level, p.at().x + Math.cos(a) * r, p.at().z + Math.sin(a) * r);
					strikes.add(new Strike(level, at == null ? p.at() : at, power, tick + (long) i * fx.intervalTicks(), blast));
				}

				yield n + " strikes";
			}
			case "supply_drop" -> {
				List<ServerPlayer> near = new ArrayList<>();
				for (ServerPlayer player : level.players()) {
					if (Overlord.eligible(player, m.id) && Math.hypot(player.getX() - p.at().x, player.getZ() - p.at().z) <= Fairness.REACH + e.radius()) {
						near.add(player);
					}
				}

				yield drop(m, level, p.at(), fx, near);
			}
			case "weather" -> {
				boolean rain = !fx.weather().equals("clear"), thunder = fx.weather().equals("thunder");
				s.setWeatherParameters(rain ? 0 : ticks, rain ? ticks : 0, rain, thunder);
				yield fx.weather() + " for " + ticks / 20 + " s";
			}
			default -> outbreak(m, level, p, e, k);
		};
		m.line(String.format(Locale.ROOT, "%s: %s", e.name(), detail));
		Cyberblock.LOG.info("overlord: event {} landed ({})", p.id(), detail);
	}

	/**
	 * A night_city blackout or traffic jam: the real remote hack in the game of each host in the radius that is an opted-in player of
	 * the match and whose spawns and hack toggles allow it ({@link HostRules#rhack}).
	 */
	private static String nightCityHack(final Overlord.MatchState m, final OverlordContent.EventEntry e, final Overlord.Pending p, final RemoteHack hack,
		final int seconds) {
		if (!e.nightCity()) {
			return "";
		}

		int sent = 0;
		for (HostSession host : HostSessions.active()) {
			ServerPlayer player = host.player();
			boolean own = host.uuid().equals(p.by());
			if (player == null || player.level() != p.level() || Math.hypot(player.getX() - p.at().x, player.getZ() - p.at().z) > e.radius()
				|| !HostRules.rhack(Overlord.eligible(player, m.id), own, host.perms().has(Permissions.Toggle.SPAWNS), hack.allowed(host.perms(), own))) {
				continue;
			}

			int id = host.newRequest(p.by() != null ? p.by() : host.uuid());
			// the merged rhack shape (protocol.md "Area hacks"): a district blackout is centred on V, a gridlock on the event's spot
			int secs = hack.downSeconds(Math.max(RemoteHack.MIN_BLACKOUT, Math.min(RemoteHack.MAX_BLACKOUT, seconds)));
			host.send(ActWire.rhack(id, 0L, hack, hack == RemoteHack.BLACKOUT ? player.position() : p.at(), secs));
			sent++;
		}

		return sent == 0 ? "" : ", " + hack.wire() + " in " + sent + " host game(s)";
	}

	/**
	 * A supply drop: its items fall at the spot for anyone; its eddies are shared among the match's opted-in players near it
	 * ({@link Fairness#eddieShares}: capped per drop, per match and per player), each share a stack only that player can pick up, so a
	 * drop never pays the Overlord or a bystander and never becomes an eddie farm (section 1.16).
	 */
	private static String drop(final Overlord.MatchState m, final ServerLevel level, final Vec3 at, final OverlordContent.Effect fx,
		final List<ServerPlayer> near) {
		int stacks = 0;
		for (OverlordContent.Stack st : fx.items()) {
			if (BuiltInRegistries.ITEM.containsKey(st.item())) {
				Item item = BuiltInRegistries.ITEM.getValue(st.item());
				fall(level, at, new ItemStack(item, Math.min(st.count(), item.getDefaultMaxStackSize())), null);
				stacks++;
			} else {
				once(st.item() + " is not an item");
			}
		}

		Identifier eddies = Identifier.fromNamespaceAndPath(Cyberblock.ID, "eddies");
		int paid = 0;
		if (fx.eddies() > 0 && !near.isEmpty() && BuiltInRegistries.ITEM.containsKey(eddies)) {
			int[] before = near.stream().mapToInt(p -> m.eddiesTo.getOrDefault(p.getUUID(), 0)).toArray();
			int[] shares = Fairness.eddieShares(fx.eddies(), m.eddiesPaid, before);
			for (int i = 0; i < shares.length; i++) {
				if (shares[i] > 0) {
					fall(level, at, new ItemStack(BuiltInRegistries.ITEM.getValue(eddies), shares[i]), near.get(i).getUUID());
					m.eddiesTo.merge(near.get(i).getUUID(), shares[i], Integer::sum);
					paid += shares[i];
				}
			}

			m.eddiesPaid += paid;
		}

		level.sendParticles(new DustParticleOptions(0x40FF80, 2.0F), true, true, at.x, at.y + 1.0, at.z, 30, 0.6, 2.0, 0.6, 0.0);
		return stacks + " stacks dropped" + (paid == 0 ? "" : ", " + paid + " eddies for " + near.size() + " opted-in player(s)");
	}

	/** A glowing stack falling onto the spot; with {@code owner} only that player can pick it up. */
	private static void fall(final ServerLevel level, final Vec3 at, final ItemStack stack, final @Nullable UUID owner) {
		ItemEntity drop = new ItemEntity(level, at.x, at.y + 12.0, at.z, stack, 0.0, -0.2, 0.0);
		drop.setGlowingTag(true);
		if (owner != null) {
			drop.setTarget(owner);
		}

		level.addFreshEntity(drop);
	}

	private static String outbreak(final Overlord.MatchState m, final ServerLevel level, final Overlord.Pending p, final OverlordContent.EventEntry e,
		final double k) {
		Identifier id = e.effect().spawn();
		OverlordContent.SpawnEntry entry = id == null ? null : OverlordContent.spawns().get(id);
		if (entry == null) {
			return "no spawn_palette entry " + id;
		}

		int squads = scaled(e.effect().count(), k, 1, 4), placed = 0;
		double r = Math.max(m.intensity.minDistance() + 2.0, 6.0);
		for (int i = 0; i < squads; i++) {
			double a = i * Math.PI * 2.0 / squads;
			Vec3 at = ground(level, p.at().x + Math.cos(a) * r, p.at().z + Math.sin(a) * r);
			if (at != null && Overlord.whoNear(level, m.id, at.x, at.z).stream()
				.noneMatch(w -> Math.hypot(w.x() - at.x, w.z() - at.z) < m.intensity.minDistance())) {
				spawn(m, level, at, id, entry, p.by());
				placed++;
			}
		}

		return placed + " of " + squads + " squads of " + entry.name();
	}

	/**
	 * Airstrike explosions whose time has come: they break no blocks, hurt only what {@link Blast} allows and reach only the host games
	 * that consent (ExplosionEvents.owner hands them to {@link Blast#hears}, not the generic relay).
	 */
	static void tickStrikes(final long tick) {
		for (Iterator<Strike> it = strikes.iterator(); it.hasNext();) {
			Strike st = it.next();
			if (tick >= st.tick()) {
				it.remove();
				st.level().explode(null, null, st.blast(), st.at().x, st.at().y, st.at().z, st.power(), false, Level.ExplosionInteraction.NONE);
			}
		}
	}

	// ---- upkeep ----

	/**
	 * Once a second: forget mobs that died or unloaded, keep the living on the match's opted-in players only (a mob that targets anyone
	 * else drops that target and takes the nearest opted-in player instead), and let Night City spawns age out.
	 */
	static void keep(final MinecraftServer s, final Overlord.MatchState m, final long now) {
		for (Iterator<UUID> it = m.mobs.iterator(); it.hasNext();) {
			Entity e = find(s, it.next());
			if (e == null || !e.isAlive()) {
				it.remove();
				continue;
			}

			if (e instanceof Mob mob && mob.getTarget() instanceof ServerPlayer target && !Overlord.eligible(target, m.id)) {
				mob.setTarget(e.level() instanceof ServerLevel level ? nearestEligible(level, m.id, e.position(), 32.0) : null);
			}
		}

		m.nightCity.values().removeIf(v -> v[0] <= now);
	}

	/** Removes every mob the match spawned; returns how many. */
	static int removeMobs(final MinecraftServer s, final Overlord.MatchState m) {
		int n = 0;
		for (UUID id : m.mobs) {
			Entity e = find(s, id);
			if (e != null) {
				e.discard();
				n++;
			}
		}

		m.mobs.clear();
		m.pending.clear();
		return n;
	}

	private static @Nullable Entity find(final MinecraftServer s, final UUID id) {
		for (ServerLevel level : s.getAllLevels()) {
			Entity e = level.getEntity(id);
			if (e != null) {
				return e;
			}
		}

		return null;
	}
}
