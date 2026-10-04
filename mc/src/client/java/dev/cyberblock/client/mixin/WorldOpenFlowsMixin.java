package dev.cyberblock.client.mixin;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.AutoWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world with a datapack dimension type counts as "experimental", so opening it asks to make a backup first. For the
 * Night City world that is the point of the mod, and the question would block quick play: skip it, for the AutoWorld world and
 * for any other world whose overworld is the Night City type (a world copied from a dedicated server, the test harness's).
 */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
	@Shadow
	protected abstract void openWorldLoadBundledResourcePack(LevelStorageAccess worldAccess, WorldStem worldStem, PackRepository packRepository, Runnable onCancel);

	@Inject(method = "openWorldCheckWorldStemCompatibility", at = @At("HEAD"), cancellable = true)
	private void cyberblock$noExperimentalPrompt(final LevelStorageAccess worldAccess, final WorldStem worldStem, final PackRepository packRepository,
		final Runnable onCancel, final CallbackInfo ci) {
		if (AutoWorld.WORLD.equals(worldAccess.getLevelId()) || nightCity(worldStem)) {
			this.openWorldLoadBundledResourcePack(worldAccess, worldStem, packRepository, onCancel);
			ci.cancel();
		}
	}

	private static boolean nightCity(final WorldStem worldStem) {
		return worldStem.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).getOptional(LevelStem.OVERWORLD)
			.map(stem -> stem.type().is(Identifier.fromNamespaceAndPath(Cyberblock.ID, "nightcity"))).orElse(false);
	}
}
