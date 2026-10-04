package dev.cyberblock.client.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.cyberblock.Cyberblock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Atlas's map textures (render thread only): one 64 x 64 texture per tile (1 px = one 2-m cell) and, for far zoom, one per
 * region (8 x 8 tiles, 1 px = 16 m), each like a vanilla map texture (a DynamicTexture registered with the TextureManager, as
 * MapTextureManager does) but sampled NEAREST and clamped, so the cells stay crisp squares at any zoom. A texture is rebuilt
 * when its tiles (or their neighbours, which its ground search reaches) change, at most twice a second and a few per frame;
 * the least recently drawn ones are released past {@link #MAX_TEXTURES}.
 */
public final class AtlasTextures {
	private static final int MAX_TEXTURES = 384;
	private static final int BUILDS_PER_FRAME = 6;
	private static final long REBUILD_NANOS = 500_000_000L;
	/** Region textures: 8 x 8 cells per pixel, ground searched 6 px (96 m) around. */
	private static final int LOD = AtlasTiles.REGION;
	private static final int LOD_MARGIN = 6;

	/** A nearest-sampled, clamped DynamicTexture. */
	private static final class MapTexture extends DynamicTexture {
		MapTexture(final String label) {
			super(() -> label, AtlasTiles.TILE, AtlasTiles.TILE, true);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
	}

	private static final class Entry {
		final Identifier id;
		final MapTexture texture;
		long stamp = Long.MIN_VALUE;
		long builtAt;
		boolean empty = true;

		Entry(final Identifier id, final MapTexture texture) {
			this.id = id;
			this.texture = texture;
		}
	}

	/** Tiles and regions by key; access order, so the first entries are the least recently drawn. */
	private static final Map<Long, Entry> tiles = new LinkedHashMap<>(64, 0.75F, true);
	private static final Map<Long, Entry> regions = new LinkedHashMap<>(16, 0.75F, true);
	private static int generation = Integer.MIN_VALUE;
	/** The map being drawn this frame (AtlasStore.view(): a guest's shared map or this installation's own). */
	private static AtlasTiles viewed = AtlasStore.tiles;
	private static int builds;

	private AtlasTextures() {
	}

	/** Starts a frame: the build budget is renewed; another world's textures, or the other map's, are dropped. */
	public static void beginFrame() {
		builds = 0;
		AtlasTiles view = AtlasStore.view();
		int now = view.generation();
		if (view != viewed || now != generation) {
			viewed = view;
			generation = now;
			releaseAll(tiles);
			releaseAll(regions);
		}
	}

	/**
	 * Draws tile (tx, tz) at the current pose with its north-west corner at (x, y) and {@code cellPx} GUI pixels per cell (the
	 * pose may also rotate: the radar). Nothing when the tile has no data yet.
	 */
	public static void drawTile(final GuiGraphicsExtractor graphics, final int tx, final int tz, final float x, final float y, final float cellPx) {
		Identifier id = tile(tx, tz);
		if (id != null) {
			blit(graphics, id, x, y, cellPx);
		}
	}

	/** Draws region (rx, rz) with its north-west corner at (x, y) and {@code pixelPx} GUI pixels per 16-m pixel. */
	public static void drawRegion(final GuiGraphicsExtractor graphics, final int rx, final int rz, final float x, final float y, final float pixelPx) {
		Identifier id = region(rx, rz);
		if (id != null) {
			blit(graphics, id, x, y, pixelPx);
		}
	}

	private static void blit(final GuiGraphicsExtractor graphics, final Identifier id, final float x, final float y, final float scale) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0.0F, 0.0F, AtlasTiles.TILE, AtlasTiles.TILE, AtlasTiles.TILE, AtlasTiles.TILE);
		graphics.pose().popMatrix();
	}

	private static @Nullable Identifier tile(final int tx, final int tz) {
		AtlasTiles store = viewed;
		if (ServerMap.asking()) {
			ServerMap.want(tx, tz);
		}

		if (!store.hasTile(tx, tz)) {
			return null;
		}

		long key = AtlasTiles.key(tx, tz);
		long stamp = store.versionSum(tx - 1, tz - 1, tx + 1, tz + 1);
		Entry entry = entry(tiles, key, "tile_" + tx + "_" + tz);
		if (entry.stamp != stamp && canBuild(entry)) {
			int margin = AtlasColours.GROUND_RADIUS, full = AtlasTiles.TILE + 2 * margin;
			short[] cells = new short[full * full];
			store.copy(tx * AtlasTiles.TILE - margin, tz * AtlasTiles.TILE - margin, full, cells);
			upload(entry, AtlasColours.shade(cells, AtlasTiles.TILE, margin, margin), stamp);
		}

		return entry.empty ? null : entry.id;
	}

	private static @Nullable Identifier region(final int rx, final int rz) {
		AtlasTiles store = viewed;
		if (ServerMap.asking()) {
			ServerMap.wantRegion(rx, rz);
		}

		int t0x = rx * LOD, t0z = rz * LOD;
		long stamp = store.versionSum(t0x - 1, t0z - 1, t0x + LOD, t0z + LOD);
		if (stamp == 0L) {
			return null;
		}

		long key = AtlasTiles.key(rx, rz);
		Entry entry = entry(regions, key, "region_" + rx + "_" + rz);
		if (entry.stamp != stamp && canBuild(entry)) {
			// one representative cell per 8 x 8 block (its centre if known, else the first known one), then shaded as a tile
			int size = AtlasTiles.TILE, full = size + 2 * LOD_MARGIN, span = full * LOD;
			short[] cells = new short[span * span];
			int c0x = t0x * AtlasTiles.TILE - LOD_MARGIN * LOD, c0z = t0z * AtlasTiles.TILE - LOD_MARGIN * LOD;
			store.copy(c0x, c0z, span, cells);
			upload(entry, AtlasColours.shade(AtlasColours.downsample(cells, span, LOD), size, LOD_MARGIN, LOD_MARGIN), stamp);
		}

		return entry.empty ? null : entry.id;
	}

	private static boolean canBuild(final Entry entry) {
		long now = System.nanoTime();
		if (builds >= BUILDS_PER_FRAME || (!entry.empty && now - entry.builtAt < REBUILD_NANOS)) {
			return false;
		}

		builds++;
		entry.builtAt = now;
		return true;
	}

	private static void upload(final Entry entry, final int[] argb, final long stamp) {
		NativeImage image = entry.texture.getPixels();
		if (image == null) {
			return;
		}

		boolean any = false;
		for (int y = 0; y < AtlasTiles.TILE; y++) {
			for (int x = 0; x < AtlasTiles.TILE; x++) {
				int colour = argb[y * AtlasTiles.TILE + x];
				image.setPixel(x, y, colour);
				any |= colour != 0;
			}
		}

		entry.texture.upload();
		entry.stamp = stamp;
		entry.empty = !any;
	}

	private static Entry entry(final Map<Long, Entry> cache, final long key, final String name) {
		Entry entry = cache.get(key);
		if (entry != null) {
			return entry;
		}

		Identifier id = Identifier.fromNamespaceAndPath(Cyberblock.ID, "atlas/" + name.replace('-', 'm'));
		MapTexture texture = new MapTexture("Cyberblock atlas " + name);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		entry = new Entry(id, texture);
		cache.put(key, entry);
		if (cache.size() > MAX_TEXTURES) {
			Iterator<Map.Entry<Long, Entry>> oldest = cache.entrySet().iterator();
			Minecraft.getInstance().getTextureManager().release(oldest.next().getValue().id);
			oldest.remove();
		}

		return entry;
	}

	private static void releaseAll(final Map<Long, Entry> cache) {
		for (Entry entry : cache.values()) {
			Minecraft.getInstance().getTextureManager().release(entry.id);
		}

		cache.clear();
	}
}
