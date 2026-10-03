package dev.cyberblock.client.ui;

import dev.cyberblock.ProxyEntities;
import dev.cyberblock.client.HostState;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * The radar (section 1.11): top-left, 80x80, in the style of a held map. The parchment is vanilla's map background; on it the
 * client level's blocks within 24 blocks shaded like a map item, Cyberpunk's NPCs (red hostile, white neutral, grey squares for
 * vehicles), Minecraft's own mobs, the minimap pins with vanilla map-decoration sprites, Steve's arrow in the middle and an
 * arrow on the edge toward the tracked objective. It turns with the host camera, so straight ahead is up.
 */
final class RadarHud {
	static final int SIZE = 80;
	static final int MARGIN = 4;
	/** The parchment's border at this size: content is clipped inside it. */
	private static final int BORDER = 5;
	private static final int RANGE = 24;
	private static final int HALF = SIZE / 2 - BORDER;
	private static final double SCALE = (double) HALF / RANGE;
	private static final int GRID = RANGE * 2 + 1;
	/** Terrain rescanned every this many ticks, or when Steve moved to another block column. */
	private static final int RESCAN_TICKS = 10;
	private static final int NPC_HOSTILE = McTheme.RED;
	private static final int NPC_NEUTRAL = McTheme.WHITE;
	private static final int VEHICLE = 0xFF8B8B8B;
	private static final int MOB_HOSTILE = 0xFFAA0000;
	private static final int MOB_PASSIVE = McTheme.YELLOW;
	private static final Identifier PLAYER = Identifier.withDefaultNamespace("player");
	private static final Identifier OBJECTIVE = Identifier.withDefaultNamespace("target_point");

	/** ARGB per grid cell (0 = nothing to draw), x-major from the north-west corner; rebuilt on the client thread. */
	private static final int[] terrain = new int[GRID * GRID];
	private static int scannedX = Integer.MIN_VALUE, scannedY, scannedZ;
	private static long scannedTick = Long.MIN_VALUE;

	private RadarHud() {
	}

	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Player player = UiHud.player();
		if (player == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		HostState.Pose pose = HostState.frame();
		float yaw = pose != null ? pose.yaw() : player.getYRot();
		double px = player.getX(), pz = player.getZ();
		int x0 = MARGIN, y0 = MARGIN;
		float cx = x0 + SIZE / 2.0F, cy = y0 + SIZE / 2.0F;
		graphics.blit(RenderPipelines.GUI_TEXTURED, McTheme.MAP_BACKGROUND, x0, y0, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
		graphics.enableScissor(x0 + BORDER, y0 + BORDER, x0 + SIZE - BORDER, y0 + SIZE - BORDER);
		scan(minecraft.level, player);
		drawTerrain(graphics, cx, cy, yaw, px - scannedX - 1.0, pz - scannedZ - 1.0);

		for (UiModel.Npc npc : UiModel.npcs) {
			RadarProjection.Point p = RadarProjection.project(npc.p().x() - px, npc.p().z() - pz, yaw, SCALE, HALF);
			if (p.inside()) {
				int x = Math.round(cx + p.x()), y = Math.round(cy + p.y());
				if (npc.vehicle()) {
					graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xFF000000);
					graphics.fill(x - 1, y - 1, x + 1, y + 1, VEHICLE);
				} else {
					dot(graphics, x, y, npc.hostile() ? NPC_HOSTILE : NPC_NEUTRAL);
				}
			}
		}

		if (minecraft.level != null) {
			List<Mob> mobs = minecraft.level.getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(RANGE));
			for (Mob mob : mobs) {
				if (mob.getType() == ProxyEntities.HUMAN || mob.getType() == ProxyEntities.VEHICLE) {
					continue;
				}

				RadarProjection.Point p = RadarProjection.project(mob.getX() - px, mob.getZ() - pz, yaw, SCALE, HALF);
				if (p.inside()) {
					dot(graphics, Math.round(cx + p.x()), Math.round(cy + p.y()), mob instanceof Enemy ? MOB_HOSTILE : MOB_PASSIVE);
				}
			}
		}

		for (UiModel.Pin pin : UiModel.pins.list()) {
			RadarProjection.Point p = RadarProjection.project(pin.p().x() - px, pin.p().z() - pz, yaw, SCALE, HALF);
			if (p.inside()) {
				decoration(graphics, pinSprite(pin.kind()), cx + p.x(), cy + p.y(), 0.0F, pin.tracked() ? McTheme.YELLOW : 0xFFFFFFFF);
			}
		}

		UiModel.Quest quest = UiModel.quest;
		if (quest != null && quest.wp() != null) {
			RadarProjection.Point p = RadarProjection.project(quest.wp().x() - px, quest.wp().z() - pz, yaw, SCALE, HALF);
			if (p.inside()) {
				decoration(graphics, OBJECTIVE, cx + p.x(), cy + p.y(), 0.0F, McTheme.GOLD);
			} else {
				// the objective edge arrow: on the rim, pointing out toward it
				RadarProjection.Point edge = RadarProjection.clampToEdge(p, HALF - 3);
				decoration(graphics, PLAYER, cx + edge.x(), cy + edge.y(), (float) Math.atan2(p.x(), -p.y()), McTheme.GOLD);
			}
		}

		// Steve: the map's player arrow, always pointing up (the radar turns instead)
		decoration(graphics, PLAYER, cx, cy, 0.0F, 0xFFFFFFFF);
		graphics.disableScissor();
	}

	/** A 2x2 dot with a dark rim, so white dots show on the pale parchment. */
	private static void dot(final GuiGraphicsExtractor graphics, final int x, final int y, final int colour) {
		graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xC0000000);
		graphics.fill(x - 1, y - 1, x + 1, y + 1, colour);
	}

	/** A vanilla map decoration (map_decorations atlas, 8x8) centred on (x, y), turned by {@code radians}. */
	private static void decoration(final GuiGraphicsExtractor graphics, final Identifier sprite, final float x, final float y, final float radians, final int colour) {
		TextureAtlasSprite atlasSprite = graphics.getSprite(new SpriteId(Sheets.MAP_DECORATIONS_SHEET, sprite));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().rotate(radians);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, atlasSprite, -4, -4, 8, 8, colour);
		graphics.pose().popMatrix();
	}

	/** Section 1.1 pin kinds to vanilla map-decoration sprites (ids in the map_decorations atlas of the 26.3 client jar). */
	static Identifier pinSprite(final String kind) {
		return Identifier.withDefaultNamespace(switch (kind) {
			case "quest" -> "target_x";
			case "vendor" -> "desert_village";
			case "fasttravel" -> "blue_marker";
			case "gig" -> "yellow_banner";
			case "police" -> "blue_banner";
			case "ripperdoc" -> "red_banner";
			case "apartment" -> "plains_village";
			case "car" -> "frame";
			default -> "white_banner";
		});
	}

	/**
	 * The cells around (sx, sz), drawn in world axes (north up) around the centre and turned as a whole by the pose; (fx, fz) is
	 * Steve's offset inside his block, so the grid slides smoothly.
	 */
	private static void drawTerrain(final GuiGraphicsExtractor graphics, final float cx, final float cy, final float yaw, final double fx, final double fz) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().rotate(RadarProjection.gridRotation(yaw));
		graphics.pose().scale((float) SCALE, (float) SCALE);
		graphics.pose().translate((float) fx, (float) fz);
		for (int i = 0; i < GRID; i++) {
			for (int k = 0; k < GRID; k++) {
				int colour = terrain[i * GRID + k];
				if (colour != 0) {
					// local = -(offset): world +x is drawn to the left, +z up, then the pose turns it (RadarProjection.gridRotation)
					int lx = RANGE - i, lz = RANGE - k;
					graphics.fill(lx, lz, lx + 1, lz + 1, colour);
				}
			}
		}

		graphics.pose().popMatrix();
	}

	/**
	 * Map-item shading of the client level around Steve: per column the first block with a map colour from 8 above to 16 below his
	 * feet (Night City's invisible barriers have none), brighter when higher than its northern neighbour, darker when lower.
	 */
	private static void scan(final ClientLevel level, final Player player) {
		if (level == null) {
			return;
		}

		BlockPos feet = player.blockPosition();
		long tick = level.getGameTime();
		if (feet.getX() == scannedX && feet.getY() == scannedY && feet.getZ() == scannedZ && tick - scannedTick < RESCAN_TICKS && tick >= scannedTick) {
			return;
		}

		scannedX = feet.getX();
		scannedY = feet.getY();
		scannedZ = feet.getZ();
		scannedTick = tick;
		int[] heights = new int[GRID * GRID];
		MapColor[] colours = new MapColor[GRID * GRID];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < GRID; i++) {
			for (int k = 0; k < GRID; k++) {
				int x = scannedX + i - RANGE, z = scannedZ + k - RANGE;
				colours[i * GRID + k] = MapColor.NONE;
				for (int y = scannedY + 8; y >= scannedY - 16; y--) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					MapColor colour = state.isAir() ? MapColor.NONE : state.getMapColor(level, pos);
					if (colour != MapColor.NONE) {
						colours[i * GRID + k] = colour;
						heights[i * GRID + k] = y;
						break;
					}
				}
			}
		}

		for (int i = 0; i < GRID; i++) {
			for (int k = 0; k < GRID; k++) {
				MapColor colour = colours[i * GRID + k];
				if (colour == MapColor.NONE) {
					terrain[i * GRID + k] = 0;
					continue;
				}

				int north = k > 0 && colours[i * GRID + k - 1] != MapColor.NONE ? heights[i * GRID + k - 1] : heights[i * GRID + k];
				int diff = Mth.sign(heights[i * GRID + k] - north);
				MapColor.Brightness brightness = diff > 0 ? MapColor.Brightness.HIGH : diff < 0 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				terrain[i * GRID + k] = colour.calculateARGBColor(brightness);
			}
		}
	}
}
