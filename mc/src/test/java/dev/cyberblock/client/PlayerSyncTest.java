package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Plan retest3 M2 (F3): where Steve goes and where he looks, seated in a vehicle or not. */
class PlayerSyncTest {
	private static final double EYES = 1.62;

	private static HostState.Pose pose(final String cam) {
		HostState.update(JsonParser.parseString(cam).getAsJsonObject());
		return HostState.live();
	}

	@Test
	void seatedSteveSitsBelowVsHipsWhateverTheCamera() {
		for (String fp : new String[] {"true", "false"}) {
			HostState.Pose p = pose("{'p':[10,72,5],'r':[30,10,0],'fp':" + fp + ",'pl':[11,70.9,6],'h':80,'seat':true}");
			Vec3 at = PlayerSync.anchor(p, EYES);
			assertEquals(11.0, at.x, 1e-9, "fp " + fp);
			assertEquals(70.9 - PlayerSync.SEAT_DROP, at.y, 1e-9, "fp " + fp);
			assertEquals(6.0, at.z, 1e-9, "fp " + fp);
			assertEquals(80.0F, PlayerSync.bodyYaw(p), "the body faces the way V's seat faces");
		}

		assertEquals(0.70, PlayerSync.SEAT_DROP, 1e-12);
	}

	@Test
	void seatedHeadFollowsTheCameraInFirstPersonAndTheBodyInThirdPerson() {
		HostState.Pose first = pose("{'p':[10,72,5],'r':[30,10,0],'fp':true,'pl':[11,70.9,6],'h':80,'seat':true}");
		assertEquals(30.0F, PlayerSync.headYaw(first));
		assertEquals(10.0F, PlayerSync.pitch(first));
		HostState.Pose third = pose("{'p':[10,72,5],'r':[30,10,0],'fp':false,'pl':[11,70.9,6],'h':80,'seat':true}");
		assertEquals(80.0F, PlayerSync.headYaw(third));
		assertEquals(0.0F, PlayerSync.pitch(third), "an orbiting vehicle camera's pitch is not Steve's gaze");
	}

	@Test
	void onFootIsUnchanged() {
		HostState.Pose first = pose("{'p':[10,72,5],'r':[30,10,0],'fp':true,'pl':[11,70.4,6],'h':80}");
		Vec3 eyes = PlayerSync.anchor(first, EYES);
		assertEquals(10.0, eyes.x, 1e-9);
		assertEquals(72.0 - EYES, eyes.y, 1e-9);
		assertEquals(5.0, eyes.z, 1e-9);
		assertEquals(30.0F, PlayerSync.bodyYaw(first));
		assertEquals(30.0F, PlayerSync.headYaw(first));
		HostState.Pose third = pose("{'p':[10,72,5],'r':[30,10,0],'fp':false,'pl':[11,70.4,6],'h':80}");
		Vec3 feet = PlayerSync.anchor(third, EYES);
		assertEquals(new Vec3(11.0, 70.4, 6.0), feet);
		assertEquals(80.0F, PlayerSync.bodyYaw(third));
		assertEquals(10.0F, PlayerSync.pitch(third), "on foot Steve aims where the camera does");
	}
}
