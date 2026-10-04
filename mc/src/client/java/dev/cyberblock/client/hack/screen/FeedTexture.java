package dev.cyberblock.client.hack.screen;

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
 * The hosts' photo feeds on this client (megaplan tasks 6-I1 and 6-D1): the newest 128 x 128 map colour frame of each host the Net
 * Map (or the Van screen, 8-V1) watches, drawn through one nearest-sampled texture like a map item (the colours are vanilla's map
 * palette). PhotoFeed sends each frame, the DLL's picture or the replica fallback, gated per frame by the host's toggle and the join
 * lock. Client thread.
 */
public final class FeedTexture {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "hack/feed");
	private static final Map<UUID, byte[]> frames = new HashMap<>();
	private static final Map<UUID, Long> frameAt = new HashMap<>();
	private static @Nullable DynamicTexture texture;
	private static @Nullable UUID shown;
	private static long shownAt = -1L;

	/** A nearest-sampled, clamped 128 x 128 texture (as the Atlas's map textures): the pixels stay crisp squares. */
	private static final class FeedImage extends DynamicTexture {
		FeedImage() {
			super(() -> "Cyberblock photo feed", ActWire.FEED_SIDE, ActWire.FEED_SIDE, true);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}

	private FeedTexture() {
	}

	/** A frame for this host arrived (from the server, or the dev tour's test pattern). */
	static void put(final UUID host, final byte[] px) {
		if (px.length == ActWire.FEED_BYTES) {
			frames.put(host, px);
			frameAt.put(host, System.nanoTime());
		}
	}

	static boolean has(final UUID host) {
		return frames.containsKey(host);
	}

	/** Seconds since this host's last frame (-1: none yet). */
	public static long age(final UUID host) {
		Long at = frameAt.get(host);
		return at == null ? -1L : (System.nanoTime() - at) / 1_000_000_000L;
	}

	/** ARGB of one map colour byte (colour id times 4 plus brightness), as a map item shows it. Pure. */
	static int argb(final byte packed) {
		return MapColor.getColorFromPackedId(packed & 0xFF);
	}

	/** Draws the host's newest frame at (x, y), `size` GUI pixels square; false when there is none. */
	public static boolean draw(final GuiGraphicsExtractor graphics, final UUID host, final int x, final int y, final int size) {
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
					image.setPixel(i % ActWire.FEED_SIDE, i / ActWire.FEED_SIDE, argb(px[i]) | 0xFF000000);
				}

				texture.upload();
				shown = host;
				shownAt = at;
			}
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0.0F, 0.0F, size, size, ActWire.FEED_SIDE, ActWire.FEED_SIDE, ActWire.FEED_SIDE,
			ActWire.FEED_SIDE);
		return true;
	}

	/** Left the server. */
	static void clear() {
		frames.clear();
		frameAt.clear();
		shown = null;
		shownAt = -1L;
	}
}
