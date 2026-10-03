package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Vec3i;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.jspecify.annotations.Nullable;

/**
 * The tracked objective on vanilla's Locator Bar (section 1.11): one client-side waypoint, set with
 * {@code ClientWaypointManager.trackWaypoint(TrackedWaypoint.setPosition(UUID, icon, Vec3i))} (26.3 client API) in gold, and taken
 * out when no waypoint is tracked. The bar shows whenever the client has a waypoint (Hud.nextContextualInfoState) unless the
 * experience bar takes priority; QuestHud draws its own arrow then.
 */
final class Waypoints {
	/** Ours alone: the Locator Bar skips only the camera entity's own UUID. */
	private static final UUID ID = UUID.nameUUIDFromBytes("cyberblock:quest_waypoint".getBytes(java.nio.charset.StandardCharsets.UTF_8));
	private static @Nullable Vec3i shown;
	private static @Nullable ClientPacketListener shownOn;

	private Waypoints() {
	}

	/** Every client tick: follows UiModel.quest's waypoint. */
	static void tick(final Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		UiModel.Quest quest = UiModel.quest;
		Vec3i want = quest == null || quest.wp() == null || !Cyberblock.active ? null
			: new Vec3i((int) Math.floor(quest.wp().x()), (int) Math.floor(quest.wp().y()), (int) Math.floor(quest.wp().z()));
		if (connection != shownOn) {
			// a new world: the old manager went with the old connection
			shown = null;
			shownOn = connection;
		}

		if (connection == null || java.util.Objects.equals(want, shown)) {
			return;
		}

		if (want == null) {
			connection.getWaypointManager().untrackWaypoint(TrackedWaypoint.empty(ID));
		} else {
			Waypoint.Icon icon = new Waypoint.Icon();
			icon.color = Optional.of(McTheme.GOLD); // opaque ARGB, as ByteBufCodecs.RGB_COLOR decodes it (alpha 0 draws nothing)
			connection.getWaypointManager().trackWaypoint(TrackedWaypoint.setPosition(ID, icon, want));
		}

		shown = want;
	}

	/** The UI data was dropped: the waypoint goes at the next tick (UiModel.quest is null by now). */
	static void clear(final Minecraft minecraft) {
		tick(minecraft);
	}
}
