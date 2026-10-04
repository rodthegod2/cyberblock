package dev.cyberblock.net;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Dev-only test commands (megaplan 1-N4), registered only with {@code -Dcyberblock.devtools=true} (never on by default) and only for
 * ops: tools/mpcheck.py drives them over RCON, with {@code execute as <guest> run ...} to act as that guest through the same code
 * paths a real hit or explosion takes (the player is the damage or explosion source, so the guest toggles apply).
 * <ul>
 * <li>{@code /cyberblock test hit <host> <id> <dmg>}: damages the proxy of that host's Night City id</li>
 * <li>{@code /cyberblock test explode <x> <y> <z> <power>}: a TNT-style explosion in the source's level</li>
 * <li>{@code /cyberblock test session <host>}: dumps the host's session</li>
 * </ul>
 */
public final class DevCommands {
	private DevCommands() {
	}

	public static boolean enabled() {
		return Boolean.getBoolean("cyberblock.devtools");
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("hit").then(Commands.argument("host", StringArgumentType.word())
				.then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("dmg", FloatArgumentType.floatArg(0.0F, 1000.0F))
					.executes(c -> hit(c.getSource(), StringArgumentType.getString(c, "host"), StringArgumentType.getString(c, "id"),
						FloatArgumentType.getFloat(c, "dmg")))))))
			.then(Commands.literal("explode").then(Commands.argument("x", DoubleArgumentType.doubleArg())
				.then(Commands.argument("y", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
					.then(Commands.argument("power", FloatArgumentType.floatArg(0.0F, 16.0F))
						.executes(c -> explode(c.getSource(), DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"),
							DoubleArgumentType.getDouble(c, "z"), FloatArgumentType.getFloat(c, "power"))))))))
			.then(Commands.literal("session").then(Commands.argument("host", StringArgumentType.word())
				.executes(c -> session(c.getSource(), StringArgumentType.getString(c, "host")))))));
	}

	private static HostSession sessionOf(final CommandSourceStack source, final String host) {
		ServerPlayer p = source.getServer().getPlayerList().getPlayerByName(host);
		return p == null ? null : HostSessions.of(p);
	}

	private static int hit(final CommandSourceStack source, final String host, final String id, final float dmg) {
		HostSession s = sessionOf(source, host);
		long cpId;
		try {
			cpId = Long.parseUnsignedLong(id);
		} catch (NumberFormatException e) {
			source.sendFailure(Component.literal("test: id must be a Night City id (unsigned 64-bit decimal)"));
			return 0;
		}

		Entity proxy = s == null ? null : Bridge.proxyById.at(s.uuid(), cpId);
		if (proxy == null || !(proxy.level() instanceof ServerLevel level)) {
			source.sendFailure(Component.literal("test: no proxy " + id + " of host " + host));
			return 0;
		}

		ServerPlayer actor = source.getPlayer();
		DamageSource damage = actor != null ? level.damageSources().playerAttack(actor) : level.damageSources().generic();
		boolean hurt = proxy.hurtServer(level, damage, dmg);
		String line = String.format(Locale.ROOT, "test: hit %s of %s for %.2f by %s -> %s", id, host, dmg, actor == null ? "the server" : actor.getPlainTextName(),
			hurt ? "hurt" : "no effect");
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return hurt ? 1 : 0;
	}

	private static int explode(final CommandSourceStack source, final double x, final double y, final double z, final float power) {
		ServerLevel level = source.getLevel();
		level.explode(source.getEntity(), x, y, z, power, Level.ExplosionInteraction.TNT);
		String line = String.format(Locale.ROOT, "test: explode %.1f %.1f %.1f power %.1f by %s", x, y, z, power,
			source.getEntity() == null ? "the server" : source.getEntity().getName().getString());
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static int session(final CommandSourceStack source, final String host) {
		HostSession s = sessionOf(source, host);
		if (s == null) {
			source.sendFailure(Component.literal("test: no session for " + host));
			return 0;
		}

		String line = String.format(Locale.ROOT,
			"test: session %s state=%s mod=%s dll=%s vehicle=%b air=%b cr=%b held=%b fly=%b launch=%b seat=%b stands=%b lv=%d feetCpZ=%s aim=%.3f "
				+ "money=%d wanted=%d district=%s time=%s perms=%d queued=%d up=%d down=%d dropped=%d",
			s.name(), s.state().name().toLowerCase(Locale.ROOT), s.mod(), s.dll(), s.vehicle(), s.airborne(), s.crouching(), s.held(), s.flying(), s.launching(),
			s.seated(), s.stands(), s.levelK(), s.feetCpZ(), s.aim(), s.money(), s.wanted(), s.district(), Arrays.toString(s.time()), s.perms().bits(),
			s.queued(), s.upCount(), s.downCount(), s.droppedDown());
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}
}
