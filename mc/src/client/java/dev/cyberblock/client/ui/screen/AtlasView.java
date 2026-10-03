package dev.cyberblock.client.ui.screen;

/**
 * The Atlas's pan and zoom (pure, tested): Minecraft x to the right and z downward (north up, like a map item), {@code zoom}
 * GUI pixels per block around a centre point that is drawn at the view's centre.
 */
public final class AtlasView {
	public static final double MIN_ZOOM = 0.05;
	public static final double MAX_ZOOM = 8.0;
	/** Grid steps in blocks: the smallest that keeps lines at least MIN_GRID_PX apart is used. */
	private static final int[] GRID_STEPS = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};
	private static final double MIN_GRID_PX = 24.0;

	private double centreX, centreZ;
	private double zoom;

	public AtlasView(final double centreX, final double centreZ, final double zoom) {
		this.centreX = centreX;
		this.centreZ = centreZ;
		this.zoom = Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
	}

	public double zoom() {
		return this.zoom;
	}

	public double centreX() {
		return this.centreX;
	}

	public double centreZ() {
		return this.centreZ;
	}

	/** Screen x of world x, for a view whose centre is at screen x {@code viewX}. */
	public double screenX(final double worldX, final double viewX) {
		return viewX + (worldX - this.centreX) * this.zoom;
	}

	public double screenY(final double worldZ, final double viewY) {
		return viewY + (worldZ - this.centreZ) * this.zoom;
	}

	public double worldX(final double screenX, final double viewX) {
		return this.centreX + (screenX - viewX) / this.zoom;
	}

	public double worldZ(final double screenY, final double viewY) {
		return this.centreZ + (screenY - viewY) / this.zoom;
	}

	/** A mouse drag by (dx, dy) GUI pixels: the map follows the cursor. */
	public void pan(final double dx, final double dy) {
		this.centreX -= dx / this.zoom;
		this.centreZ -= dy / this.zoom;
	}

	/** Zoom by {@code factor} keeping the world point under (sx, sy) where it is (the scroll wheel zooms toward the cursor). */
	public void zoomAt(final double factor, final double sx, final double sy, final double viewX, final double viewY) {
		double wx = this.worldX(sx, viewX), wz = this.worldZ(sy, viewY);
		this.zoom = Math.clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
		this.centreX = wx - (sx - viewX) / this.zoom;
		this.centreZ = wz - (sy - viewY) / this.zoom;
	}

	public void centreOn(final double x, final double z) {
		this.centreX = x;
		this.centreZ = z;
	}

	/** The grid spacing in blocks for the current zoom. */
	public int gridStep() {
		for (int step : GRID_STEPS) {
			if (step * this.zoom >= MIN_GRID_PX) {
				return step;
			}
		}

		return GRID_STEPS[GRID_STEPS.length - 1];
	}
}
