package dev.cyberblock.client.mode.van;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.hack.actions.ActWire;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * The linked host's photo feed in the Van (megaplan 8-V1 with 6-D1): the newest 128 x 128 map colour frame per host, drawn through
 * one nearest-sampled texture, so it reads like the host's feed map item. Client thread.
 */
final class VanFeed {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "van/feed");
	private static final Map<UUID, byte[]> frames = new HashMap<>();
	private static final Map<UUID, Long> frameAt = new HashMap<>();
	private static @Nullable DynamicTexture texture;
	private static @Nullable UUID shown;
	private static long shownAt = -1L;

	private static final class FeedImage extends DynamicTexture {
		FeedImage() {
			super(() -> "Cyberblock Van photo feed", ActWire.FEED_SIDE, ActWire.FEED_SIDE, true);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}

	private VanFeed() {
	}

	static void put(final UUID host, final byte[] px) {
		if (px.length == ActWire.FEED_BYTES) {
			frames.put(host, px);
			frameAt.put(host, System.nanoTime());
		}
	}

	/** Seconds since this host's last frame (-1: none yet). */
	static long age(final UUID host) {
		Long at = frameAt.get(host);
		return at == null ? -1L : (System.nanoTime() - at) / 1_000_000_000L;
	}

	/** Draws the host's newest frame at (x, y), `size` GUI px square; false when there is none. */
	static boolean draw(final GuiGraphicsExtractor graphics, final UUID host, final int x, final int y, final int size) {
		byte[] px = frames.get(host);
		if (px == null) {
			return false;
		}

		if (texture == null) {
			texture = new FeedImage();
			Minecraft.getInstance().getTextureManager().register(ID, texture);
		}

		long at = frameAt.get(host);
		if (!host.equals(shown) || at != shownAt) {
			NativeImage image = texture.getPixels();
			if (image != null) {
				for (int i = 0; i < px.length; i++) {
					image.setPixel(i % ActWire.FEED_SIDE, i / ActWire.FEED_SIDE, MapColor.getColorFromPackedId(px[i] & 0xFF) | 0xFF000000);
				}

				texture.upload();
				shown = host;
				shownAt = at;
			}
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0.0F, 0.0F, size, size, ActWire.FEED_SIDE, ActWire.FEED_SIDE, ActWire.FEED_SIDE, ActWire.FEED_SIDE);
		return true;
	}

	static void clear() {
		frames.clear();
		frameAt.clear();
		shown = null;
		shownAt = -1L;
	}
}
