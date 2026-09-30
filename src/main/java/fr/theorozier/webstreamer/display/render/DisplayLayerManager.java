package fr.theorozier.webstreamer.display.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * This class is responsible for caching and keeping the number of layer to the minimum.
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerManager extends DisplayLayerMap<URI> {

    /** Max cost for concurrent layers. */
    private static final int MAX_LAYERS_COST = 20 * 30;  // Approx 20 HLS layers.

    /** Interval of cleanups for unused display layers. */
    private static final long CLEANUP_INTERVAL = 5L * 1000000000L;

    /** Common pools for shared and reusable heavy buffers. */
    private final DisplayLayerResources res = new DisplayLayerResources();

    /** Time in nanoseconds (monotonic) of the last cleanup for unused layers. */
    private long lastCleanup = 0;

    public DisplayLayerResources getResources() {
        return this.res;
    }

    @Override
    public void tick() {

        RenderSystem.assertOnRenderThread();
        super.tick();

        long now = System.nanoTime();
        if (now - this.lastCleanup >= CLEANUP_INTERVAL) {
            super.cleanup(now); // Super to avoid redundant render thread check.
            // Keep two idle 8 MiB buffers (current and next segment of one stream)
            this.res.trimRawFileBuffers(2);
            this.lastCleanup = now;
        }

    }

    @Override
    public boolean cleanup(long now) {
        RenderSystem.assertOnRenderThread();
        final boolean result = super.cleanup(now);
        if (now == 0) {
            // Leaving the world: no layer remains to reuse the buffers
            this.res.trimRawFileBuffers(0);
        }
        return result;
    }

    @Override
    @NotNull
    protected URI getLayerKey(Key key) {
        return key.uri();
    }

    @Override
    @NotNull
    protected DisplayLayerNode getNewLayer(Key key) throws OutOfLayerException, UnknownFormatException {

        if (this.cost() >= MAX_LAYERS_COST) {
            throw new OutOfLayerException();
        }

        String path = key.uri().getPath();
        if (path != null) {
            if (path.endsWith(".m3u8")) {
                return new DisplayLayerHls(key.uri(), this.res);
            } else if (path.endsWith(".jpeg") || path.endsWith(".jpg") || path.endsWith(".bmp") || path.endsWith(".png")) {
                return new DisplayLayerImage(key.uri(), this.res);
            } else if (path.endsWith(".svg")) {
                return new DisplayLayerSVGMap(this.res);
            }
        }

        throw new UnknownFormatException();

    }

}
