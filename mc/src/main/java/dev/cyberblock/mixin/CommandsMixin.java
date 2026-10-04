package dev.cyberblock.mixin;

import com.mojang.brigadier.ParseResults;
import dev.cyberblock.net.JoinLock;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player locked by the join password may run /login and nothing else (no /msg, /me or /teammsg around the chat block):
 * Commands.performCommand is where every command of a player runs, signed or not (javap of the 26.3 jar). The command text is
 * neither echoed nor logged.
 */
@Mixin(Commands.class)
abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void cyberblock$lockedPlayerRunsOnlyLogin(final ParseResults<CommandSourceStack> parse, final String command, final CallbackInfo ci) {
		ServerPlayer player = parse.getContext().getSource().getPlayer();
		if (player != null && JoinLock.locked(player) && !JoinLock.isLoginCommand(command)) {
			player.sendSystemMessage(Component.translatableWithFallback("cyberblock.login.reminder", "Log in first: type /login <password>"));
			ci.cancel();
		}
	}
}
