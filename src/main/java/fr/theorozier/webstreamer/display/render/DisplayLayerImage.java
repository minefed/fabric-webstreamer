package fr.theorozier.webstreamer.display.render;

import com.mojang.blaze3d.platform.TextureUtil;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.render.image.ImageLoadTask;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutionException;

@Environment(EnvType.CLIENT)
public class DisplayLayerImage extends DisplayLayerSimple {
	
	private long imageNextRequestTimestamp = 0;
	private boolean imageUploaded = false;
	private ImageLoadTask<STBLoadedImage> futureImage;
	private int consecutiveFailures;
	private boolean released;
	
	public DisplayLayerImage(URI uri, DisplayLayerResources res) {
		super(uri, res);
	}

	@Override
	public int cost() {
		return 1;  // Static images always have a cost of 1.
	}

	@Override
	public void tick() {
		if (this.released) {
			return;
		}
		
		long now = System.nanoTime();
		
		if (this.futureImage == null) {
			if (!this.imageUploaded && now >= this.imageNextRequestTimestamp) {
				this.futureImage = new ImageLoadTask<>(this.res.getImageSources(), this.uri,
						this.res.getImageDecoders(), bytes -> this.readImageBlocking(new ByteArrayInputStream(bytes)));
			}
		} else if (this.futureImage.isDone()) {
			
			STBLoadedImage img = null;
			
			try {
				
				img = this.futureImage.take();
				if (img == null) {
					return;
				}
				
				WebStreamerMod.LOGGER.info(makeLog("Uploading image... (channels: {}, pixel size: {})"), img.channels, img.buffer.remaining() / (img.width * img.height));
				
				this.tex.uploadRaw(img.buffer, GL11.GL_RGBA, img.width, img.height, img.width, GL11.GL_RGBA, 4);
				this.imageUploaded = true;
				this.consecutiveFailures = 0;
				
			} catch (ExecutionException e) {
				long delaySeconds = Math.min(30, 1L << Math.min(this.consecutiveFailures++, 5));
				WebStreamerMod.LOGGER.error(makeLog("Failed to request image, retrying in {} seconds."), delaySeconds, e.getCause());
				// A short initial retry avoids a fixed 30-second blank display; repeated failures still back off.
				this.imageNextRequestTimestamp = now + delaySeconds * 1_000_000_000L;
			} finally {
				this.futureImage.close();
				this.futureImage = null;
				if (img != null) {
					img.close();
				}
			}
			
		}
		
	}

	@Override
	public boolean cleanup(long now) {
		if (this.released || super.cleanup(now)) {
			this.released = true;
			if (this.futureImage != null) {
				this.futureImage.close();
				this.futureImage = null;
			}
			return true;
		}
		return false;
	}

	protected STBLoadedImage readImageBlocking(InputStream stream) throws IOException {
		ByteBuffer buf = null;
		try {
			buf = TextureUtil.readResource(stream);
			buf.rewind();
			try (MemoryStack memoryStack = MemoryStack.stackPush()){
				IntBuffer width = memoryStack.mallocInt(1);
				IntBuffer height = memoryStack.mallocInt(1);
				IntBuffer channels = memoryStack.mallocInt(1);
				ByteBuffer stbBuf = STBImage.stbi_load_from_memory(buf, width, height, channels, STBImage.STBI_rgb_alpha);
				if (stbBuf == null) {
					throw new IOException("Could not load image: " + STBImage.stbi_failure_reason());
				}
				return new STBLoadedImage(stbBuf, width.get(0), height.get(0), channels.get(0));
			}
		} finally {
			MemoryUtil.memFree(buf);
			IOUtils.closeQuietly(stream);
		}
	}

	protected record STBLoadedImage(ByteBuffer buffer, int width, int height, int channels) implements AutoCloseable {

		@Override
		public void close() {
			STBImage.stbi_image_free(this.buffer);
		}
		
	}
	
}
