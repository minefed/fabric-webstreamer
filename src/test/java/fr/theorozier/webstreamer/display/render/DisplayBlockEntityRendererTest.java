package fr.theorozier.webstreamer.display.render;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DisplayBlockEntityRendererTest {

    private static final Vec3d CAMERA = new Vec3d(0.5, 64, 0.5);

    @Test
    public void rendersBeyondTheDefault64BlockLimit() {
        assertTrue(inRange(128, 64, 0, CAMERA, 16));
    }

    @Test
    public void includesTheWholeBoundaryChunkAt16And32Chunks() {
        for (int distance : new int[]{16, 32}) {
            assertTrue(inRange(distance * 16 + 15, 64, 0, CAMERA, distance));
            assertTrue(inRange(-distance * 16, 64, 0, CAMERA, distance));
            assertFalse(inRange((distance + 1) * 16, 64, 0, CAMERA, distance));
            assertFalse(inRange(-distance * 16 - 1, 64, 0, CAMERA, distance));
            assertTrue(inRange(0, 64, distance * 16 + 15, CAMERA, distance));
            assertFalse(inRange(0, 64, (distance + 1) * 16, CAMERA, distance));
        }
    }

    @Test
    public void followsTheRoundedTerrainBoundaryDiagonally() {
        assertTrue(inRange(12 * 16, 64, 12 * 16, CAMERA, 16));
        assertFalse(inRange(13 * 16, 64, 13 * 16, CAMERA, 16));
        assertTrue(inRange(23 * 16, 64, -23 * 16, CAMERA, 32));
        assertFalse(inRange(24 * 16, 64, -24 * 16, CAMERA, 32));
    }

    @Test
    public void heightDoesNotReduceTheChunkRange() {
        assertTrue(inRange(256, -64, 0, new Vec3d(0.5, 319, 0.5), 16));
        assertTrue(inRange(512, 319, 0, new Vec3d(0.5, -64, 0.5), 32));
    }

    @Test
    public void floorsNegativeCameraCoordinatesToTheCorrectChunk() {
        Vec3d negativeCamera = new Vec3d(-0.01, 64, -0.01);
        assertTrue(inRange(15 * 16 + 15, 64, -1, negativeCamera, 16));
        assertFalse(inRange(16 * 16, 64, -1, negativeCamera, 16));
        assertTrue(inRange(-17 * 16, 64, -1, negativeCamera, 16));
        assertFalse(inRange(-17 * 16 - 1, 64, -1, negativeCamera, 16));
        assertFalse(inRange(-1, 64, 16 * 16, negativeCamera, 16));
    }

    @Test
    public void respondsToDistanceAndCameraChanges() {
        assertFalse(inRange(400, 64, 0, CAMERA, 16));
        assertTrue(inRange(400, 64, 0, CAMERA, 32));
        assertFalse(inRange(400, 64, 0, CAMERA, 16));
        assertTrue(inRange(400, 64, 0, new Vec3d(144, 64, 0), 16));
        assertFalse(inRange(400, 64, 0, new Vec3d(143.99, 64, 0), 16));
        assertTrue(inRange(128, 64, 0, CAMERA, 8));
        assertFalse(inRange(144, 64, 0, CAMERA, 8));
    }

    private static boolean inRange(int x, int y, int z, Vec3d camera, int distance) {
        return DisplayBlockEntityRenderer.isInRenderDistance(new BlockPos(x, y, z), camera, distance);
    }
}
