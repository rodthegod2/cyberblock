package dev.cyberblock.mode.van;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.net.DevCommands;
import dev.cyberblock.net.HostSession;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only Van tools (with {@code -Dcyberblock.devtools=true}, ops only; the Van's multiplayer check and screenshots), acting as the
 * source player through the same server paths the client's Van screen takes:
 * <ul>
 * <li>{@code /cyberblock test van sit}: a Van parked where the player stands, and the player sits in it (as a right-click)</li>
 * <li>{@code ... van host <name>}: link the Van to that host; {@code ... van state}: the state as one line</li>
 * <li>{@code ... van hack <quickhack> [screen|drone]}: the chip on the nearest person the Van sees (from that place)</li>
 * <li>{@code ... van device <act> [screen|drone]} and {@code ... van lever [screen|drone]}: a device action, or the lever chip, on the nearest
 * device the Van sees</li>
 * <li>{@code ... van launch}, {@code ... van fly <dx> <dy> <dz>} (the drone moved to the Van plus that offset, held in its leash),
 * {@code ... van dock}</li>
 * </ul>
 */
final class VanCommands {
	private VanCommands() {
	}

	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!DevCommands.enabled()) {
			return;
		}

		var hack = Commands.argument("hack", StringArgumentType.word()).executes(c -> hack(c, null));
		var device = Commands.argument("act", StringArgumentType.word()).executes(c -> device(c, null, false));
		var lever = Commands.literal("lever").executes(c -> device(c, null, true));
		for (VanRules.Seen seen : VanRules.Seen.values()) {
			hack = hack.then(Commands.literal(seen.wire()).executes(c -> hack(c, seen)));
			device = device.then(Commands.literal(seen.wire()).executes(c -> device(c, seen, false)));
			lever = lever.then(Commands.literal(seen.wire()).executes(c -> device(c, seen, true)));
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("van")
				.then(Commands.literal("sit").executes(VanCommands::sit))
				.then(Commands.literal("host").then(Commands.argument("name", StringArgumentType.word()).executes(VanCommands::host)))
				.then(Commands.literal("state").executes(VanCommands::state))
				.then(Commands.literal("hack").then(hack))
				.then(Commands.literal("device").then(device))
				.then(lever)
				.then(Commands.literal("launch").executes(VanCommands::launch))
				.then(Commands.literal("fly").then(Commands.argument("dx", DoubleArgumentType.doubleArg(-128, 128))
					.then(Commands.argument("dy", DoubleArgumentType.doubleArg(-64, 64)).then(Commands.argument("dz", DoubleArgumentType.doubleArg(-128, 128))
						.executes(VanCommands::fly)))))
				.then(Commands.literal("dock").executes(c -> {
					VanEntity van = seated(c);
					VanModule.dock(van, "test");
					return say(c.getSource(), "test: van dock");
				})))));
	}

	private static final SimpleCommandExceptionType NOT_SEATED = new SimpleCommandExceptionType(Component.literal("test: not seated in a Van"));

	private static VanEntity seated(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		VanEntity van = VanModule.vanOf(c.getSource().getPlayerOrException());
		if (van == null) {
			throw NOT_SEATED.create();
		}

		return van;
	}

	private static int sit(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		VanEntity van = VanModule.vanOf(p);
		if (van == null) {
			van = VanModule.VAN.create(p.level(), EntitySpawnReason.COMMAND);
			if (van == null) {
				return 0;
			}

			van.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0.0F);
			p.level().addFreshEntity(van);
			VanModule.use(p, van);
		}

		return say(c.getSource(), "test: van sit " + p.getPlainTextName() + " -> " + (VanModule.vanOf(p) != null ? "seated" : "not seated"));
	}

	private static int host(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		VanEntity van = seated(c);
		String name = StringArgumentType.getString(c, "name");
		HostSession s = VanModule.hostsInReach(van).stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst().orElse(null);
		if (s != null) {
			van.setHost(s.uuid());
		}

		return say(c.getSource(), "test: van host " + name + " -> " + (s == null ? "not in reach" : "linked"));
	}

	private static int state(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		VanEntity van = seated(c);
		HostSession host = VanModule.linkedHost(van);
		List<VanWire.Target> targets = VanModule.targets(p, van, host);
		long screen = targets.stream().filter(t -> t.seen() == VanRules.Seen.SCREEN).count();
		VanDrone drone = VanModule.drone(van);
		return say(c.getSource(), String.format(Locale.ROOT, "test: van state %s: host %s, %d targets (%d screen, %d drone; %d people, %d cars, %d devices), drone %s, "
				+ "battery %d %%", p.getPlainTextName(), host == null ? "none" : host.name(), targets.size(), screen, targets.size() - screen,
			count(targets, VanRules.Kind.PERSON), count(targets, VanRules.Kind.CAR), count(targets, VanRules.Kind.DEVICE),
			drone == null ? "docked" : String.format(Locale.ROOT, "at %.1f m", drone.position().distanceTo(van.position())), Math.round(van.charge() * 100)));
	}

	private static long count(final List<VanWire.Target> targets, final VanRules.Kind kind) {
		return targets.stream().filter(t -> t.kind() == kind).count();
	}

	/** The nearest target of that kind the Van sees (from `seen`, or from anywhere when null), in the state's order. */
	private static VanWire.@Nullable Target nearest(final ServerPlayer p, final VanEntity van, final VanRules.Kind kind, final VanRules.@Nullable Seen seen) {
		return VanModule.targets(p, van, VanModule.linkedHost(van)).stream().filter(t -> t.kind() == kind && (seen == null || t.seen() == seen)).findFirst()
			.orElse(null);
	}

	private static int hack(final CommandContext<CommandSourceStack> c, final VanRules.@Nullable Seen seen) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		VanEntity van = seated(c);
		Hack hack = Hack.byWire(StringArgumentType.getString(c, "hack"));
		VanWire.Target t = nearest(p, van, VanRules.Kind.PERSON, seen);
		boolean sent = hack != null && !hack.needsDevice() && t != null && VanModule.figureHack(p, van, new VanWire.FigureHack(t.eid(), hack));
		return say(c.getSource(), "test: van hack " + StringArgumentType.getString(c, "hack") + " by " + p.getPlainTextName() + " -> "
			+ (t == null ? "no person seen" : (sent ? "sent to " : "refused for ") + t.name() + " (" + t.seen().wire() + ")"));
	}

	private static int device(final CommandContext<CommandSourceStack> c, final VanRules.@Nullable Seen seen, final boolean lever) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		VanEntity van = seated(c);
		String act = lever ? null : StringArgumentType.getString(c, "act");
		VanWire.Target t = nearest(p, van, VanRules.Kind.DEVICE, seen);
		UUID host = t == null ? null : t.host();
		boolean sent = host != null && VanModule.deviceRequest(p, van, new VanWire.DeviceRequest(host, t.dev(), act));
		return say(c.getSource(), "test: van " + (lever ? "lever" : "device " + act) + " by " + p.getPlainTextName() + " -> "
			+ (t == null ? "no device seen" : (sent ? "sent to " : "refused for ") + t.type() + " " + Long.toUnsignedString(t.dev()) + " (" + t.seen().wire() + ")"));
	}

	private static int launch(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		VanEntity van = seated(c);
		VanModule.Seat seat = VanModule.seat(p.getUUID());
		boolean ok = seat != null && VanModule.launch(p, van, seat);
		return say(c.getSource(), "test: van launch by " + p.getPlainTextName() + " -> " + (ok ? "flying" : "not launched"));
	}

	private static int fly(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		VanEntity van = seated(c);
		VanDrone drone = VanModule.drone(van);
		if (drone == null) {
			return say(c.getSource(), "test: van fly -> no drone");
		}

		Vec3 home = van.position().add(0.0, VanModule.LAUNCH_HEIGHT, 0.0);
		Vec3 to = VanRules.leash(home.add(DoubleArgumentType.getDouble(c, "dx"), DoubleArgumentType.getDouble(c, "dy"), DoubleArgumentType.getDouble(c, "dz")), home);
		drone.setPos(to);
		drone.setDeltaMovement(Vec3.ZERO);
		return say(c.getSource(), String.format(Locale.ROOT, "test: van fly -> drone at %.1f %.1f %.1f (%.1f m from the Van)", to.x, to.y, to.z, to.distanceTo(home)));
	}

	private static int say(final CommandSourceStack source, final String line) {
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

}
