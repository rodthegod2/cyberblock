package dev.cyberblock.client.skin;

import dev.cyberblock.client.net.Roster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 5-K1: allies get an outline in their team colour, seen through walls (a per-tick gizmo drawn on top, the technique of
 * the Kiroshi and Compass Ping outlines). Allies are players on the same roster team; teams come from Phase 8 matches, so until
 * then every roster team is -1 and nobody is an ally (the hook is in place).
 */
public final class AllyOutlines {
	static final double RANGE = 96.0;
	/** Team colours (ARGB) by team number, repeating. */
	private static final int[] COLOURS = {0xE05AA9FF, 0xE0FF5A5A, 0xE05AFF7A, 0xE0FFD34A, 0xE0C77DFF, 0xE05AFFF0, 0xE0FF9F43, 0xE0FFFFFF};

	private AllyOutlines() {
	}

	/** Two different players on the same team (team -1 is none, so never an ally). */
	static boolean allied(final Roster.@Nullable Entry me, final Roster.@Nullable Entry other) {
		return me != null && other != null && !me.uuid().equals(other.uuid()) && me.team() >= 0 && me.team() == other.team() && !other.hidden();
	}

	static int colour(final int team) {
		return COLOURS[Math.floorMod(team, COLOURS.length)];
	}

	/** Every client tick (inside vanilla's per-tick gizmo collection). */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		Roster.Entry me = player == null ? null : Roster.of(player.getUUID());
		if (me == null || me.team() < 0 || level == null) {
			return;
		}

		for (AbstractClientPlayer other : level.players()) {
			Roster.Entry entry = Roster.of(other.getUUID());
			if (other != player && allied(me, entry) && other.distanceToSqr(player) <= RANGE * RANGE) {
				Gizmos.cuboid(other.getBoundingBox().inflate(0.05), GizmoStyle.stroke(colour(entry.team()), 2.0F)).setAlwaysOnTop();
			}
		}
	}
}
