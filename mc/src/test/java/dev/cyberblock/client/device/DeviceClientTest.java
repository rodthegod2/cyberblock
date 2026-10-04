package dev.cyberblock.client.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cyberblock.device.block.EyeCameraBlock;
import dev.cyberblock.device.block.ShopWire;
import dev.cyberblock.device.block.TurretBlock;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan 4-B1, client side: the camera cone's shape and colours, the turret tints, the vending screen's paging and Buy rule. */
class DeviceClientTest {
	@Test
	void everyConePointIsInsideTheCone() {
		Vec3 eye = new Vec3(10.5, 70.4, -3.5);
		Vec3 axis = Vec3.directionFromRotation(CameraCone.TILT, 30.0F).normalize();
		Vec3 u = axis.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 v = u.cross(axis).normalize();
		for (int i = 0; i <= 20; i++) {
			for (boolean edge : new boolean[] {true, false}) {
				double t = 0.1 + 0.9 * i / 20.0;
				Vec3 p = CameraCone.point(eye, axis, u, v, t, i * 0.7, edge);
				Vec3 d = p.subtract(eye);
				double angle = Math.acos(Math.min(1.0, d.normalize().dot(axis)));
				assertTrue(angle <= CameraCone.HALF_ANGLE + 1e-9, "angle " + angle);
				assertTrue(d.length() <= CameraCone.LENGTH / Math.cos(CameraCone.HALF_ANGLE) + 1e-9);
				if (edge) {
					assertEquals(CameraCone.HALF_ANGLE, angle, 1e-6);
				}
			}
		}
	}

	@Test
	void theConeIsRedOnAlarmGreenWhenFriendly() {
		assertEquals(CameraCone.RED, CameraCone.colour(EyeCameraBlock.Mode.ALARM));
		assertEquals(CameraCone.GREEN, CameraCone.colour(EyeCameraBlock.Mode.FRIENDLY));
		assertEquals(CameraCone.AMBER, CameraCone.colour(EyeCameraBlock.Mode.ON));
		assertEquals(TurretRenderer.ACTIVE, TurretRenderer.colour(TurretBlock.Mode.ACTIVE));
		assertEquals(TurretRenderer.OFF, TurretRenderer.colour(TurretBlock.Mode.OFF));
	}

	@Test
	void theVendingScreenPagesAndGreysBuy() {
		assertEquals(1, VendingScreen.pages(0));
		assertEquals(1, VendingScreen.pages(VendingScreen.PER_PAGE));
		assertEquals(2, VendingScreen.pages(VendingScreen.PER_PAGE + 1));
		assertEquals(5, VendingScreen.pages(ShopWire.MAX_LINES));
		ShopWire.Line line = new ShopWire.Line("a", "minecraft:apple", 1, 20, -1);
		assertTrue(VendingScreen.affordable(line, 20));
		assertFalse(VendingScreen.affordable(line, 19));
		assertFalse(VendingScreen.affordable(new ShopWire.Line("a", "minecraft:apple", 1, 0, 0), 100), "sold out");
	}
}
