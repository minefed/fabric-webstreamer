package fr.theorozier.webstreamer.display.render;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that draining a segment with {@code grabSamples()} yields the same audio as
 * the previous {@code grab()} loop in {@link FrameGrabber#grabRemaining}.
 */
public class FrameGrabberDrainTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_RATE = 25;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int VIDEO_FRAMES = 60;

    private static byte[] segment;

    @BeforeClass
    public static void encodeSegment() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(out, WIDTH, HEIGHT, CHANNELS);
        recorder.setFormat("mpegts");
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG2VIDEO);
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP2);
        recorder.setFrameRate(FRAME_RATE);
        recorder.setGopSize(12);
        // B-frames make the video decoder hold frames back, like typical HLS streams.
        recorder.setMaxBFrames(2);
        recorder.setSampleRate(SAMPLE_RATE);
        recorder.setAudioBitrate(128000);
        recorder.start();

        int samplesPerFrame = SAMPLE_RATE / FRAME_RATE;
        long sampleIndex = 0;
        for (int i = 0; i < VIDEO_FRAMES; i++) {
            Frame image = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
            ByteBuffer pixels = (ByteBuffer) image.image[0];
            for (int p = 0; p < pixels.capacity(); p++) {
                pixels.put(p, (byte) (p * 7 + i * 13));
            }
            recorder.record(image);

            ShortBuffer samples = ShortBuffer.allocate(samplesPerFrame * CHANNELS);
            for (int s = 0; s < samplesPerFrame; s++, sampleIndex++) {
                short value = (short) (Math.sin(sampleIndex * 2 * Math.PI * 440 / SAMPLE_RATE) * 8000);
                samples.put(value).put((short) -value);
            }
            samples.flip();
            recorder.recordSamples(SAMPLE_RATE, CHANNELS, samples);
        }
        recorder.stop();
        recorder.release();
        segment = out.toByteArray();
    }

    @Test
    public void grabSamplesDrainsTheSameAudioAsGrab() throws Exception {
        boolean anyAudio = false;
        for (int consumedImages = 0; consumedImages <= VIDEO_FRAMES; consumedImages += 7) {
            List<String> expected = drain(consumedImages, false);
            List<String> actual = drain(consumedImages, true);
            assertEquals("after " + consumedImages + " images", expected, actual);
            anyAudio |= !expected.isEmpty();
        }
        assertTrue("the segment must contain audio to drain", anyAudio);
    }

    /** Grab like start()/grabAt() up to an image, then drain like grabRemaining(). */
    private static List<String> drain(int consumedImages, boolean samplesOnly) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(segment));
        grabber.startUnsafe();
        try {
            int images = 0;
            Frame frame;
            while (images < consumedImages && (frame = grabber.grab()) != null) {
                if (frame.image != null) {
                    images++;
                }
            }

            List<String> audio = new ArrayList<>();
            while ((frame = samplesOnly ? grabber.grabSamples() : grabber.grab()) != null) {
                if (frame.samples != null) {
                    ShortBuffer data = ((ShortBuffer) frame.samples[0]).duplicate();
                    short[] copy = new short[data.remaining()];
                    data.get(copy);
                    audio.add(frame.timestamp + "/" + frame.sampleRate + "/" + frame.audioChannels
                            + "/" + Arrays.toString(copy));
                }
            }
            return audio;
        } finally {
            grabber.releaseUnsafe();
        }
    }
}
