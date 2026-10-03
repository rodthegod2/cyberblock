package dev.cyberblock.client.mixin;

import dev.cyberblock.client.AutoWorld;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world with a datapack dimension type counts as "experimental", so opening it asks to make a backup first. For the
 * Night City world that is the point of the mod, and the question would block quick play: skip it.
 */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
	@Shadow
	protected abstract void openWorldLoadBundledResourcePack(LevelStorageAccess worldAccess, WorldStem worldStem, PackRepository packRepository, Runnable onCancel);

	@Inject(method = "openWorldCheckWorldStemCompatibility", at = @At("HEAD"), cancellable = true)
	private void cyberblock$noExperimentalPrompt(final LevelStorageAccess worldAccess, final WorldStem worldStem, final PackRepository packRepository,
		final Runnable onCancel, final CallbackInfo ci) {
		if (AutoWorld.WORLD.equals(worldAccess.getLevelId())) {
			this.openWorldLoadBundledResourcePack(worldAccess, worldStem, packRepository, onCancel);
			ci.cancel();
		}
	}
}
