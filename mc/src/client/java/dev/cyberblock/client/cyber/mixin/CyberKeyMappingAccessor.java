package dev.cyberblock.client.cyber.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Presses that the host's key messages count, as vanilla's keyboard handler does (ClientInput does the same for vanilla keys). */
@Mixin(KeyMapping.class)
public interface CyberKeyMappingAccessor {
	@Accessor("clickCount")
	int cyberblock$cyberClickCount();

	@Accessor("clickCount")
	void cyberblock$setCyberClickCount(int clickCount);
}
