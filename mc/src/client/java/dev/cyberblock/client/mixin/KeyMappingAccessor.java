// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int cyberblock$getClickCount();

	@Accessor("clickCount")
	void cyberblock$setClickCount(int clickCount);

	/** The key this mapping is bound to now (the player may have rebound it; getDefaultKey is the factory one). */
	@Accessor("key")
	InputConstants.Key cyberblock$getKey();
}
