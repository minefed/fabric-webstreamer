package fr.theorozier.webstreamer.display.render.image;

import org.junit.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ImageLoadTaskTest {
    private static final URI URI_A = URI.create("https://example.invalid/image.png");

    private static final class Image implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        public void close() { assertEquals("Decoded image must have exactly one owner", 1, closes.incrementAndGet()); }
    }

    private static final class Fixture {
        final CompletableFuture<HttpResponse<byte[]>> network = new CompletableFuture<>();
        final AtomicReference<HttpRequest> request = new AtomicReference<>();
        final ImageSourceCache cache = new ImageSourceCache(1024, 4, 8, Duration.ofSeconds(5), System::currentTimeMillis,
                req -> { request.set(req); return network; });

        void respond() {
            network.complete(new HttpResponse<>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return request.get(); }
                public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
                public byte[] body() { return new byte[]{1, 2, 3}; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return URI_A; }
                public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            });
        }
    }

    @Test
    public void takingImageTransfersOwnershipAndTaskCloseDoesNotFreeIt() throws Exception {
        Fixture f = new Fixture();
        Image image = new Image();
        var task = new ImageLoadTask<>(f.cache, URI_A, Runnable::run, bytes -> image);
        f.respond();
        assertTrue(task.isDone());
        assertSame(image, task.take());
        assertNull(task.take());
        task.close();
        assertEquals(0, image.closes.get());
        image.close();
    }

    @Test
    public void cleanupBeforeUploadFreesCompletedImageExactlyOnce() {
        Fixture f = new Fixture();
        Image image = new Image();
        var task = new ImageLoadTask<>(f.cache, URI_A, Runnable::run, bytes -> image);
        f.respond();
        task.close();
        task.close();
        assertEquals(1, image.closes.get());
    }

    @Test
    public void cleanupBeforeNetworkCompletionCancelsRequestAndNeverDecodes() {
        Fixture f = new Fixture();
        AtomicInteger decodes = new AtomicInteger();
        var task = new ImageLoadTask<>(f.cache, URI_A, Runnable::run, bytes -> { decodes.incrementAndGet(); return new Image(); });
        task.close();
        assertTrue(f.network.isCancelled());
        f.respond();
        assertEquals(0, decodes.get());
    }

    @Test
    public void cancelledQueuedDecodeDoesNotAllocateImage() {
        Fixture f = new Fixture();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger decodes = new AtomicInteger();
        var task = new ImageLoadTask<>(f.cache, URI_A, queued::set, bytes -> { decodes.incrementAndGet(); return new Image(); });
        f.respond();
        task.close();
        queued.get().run();
        assertEquals(0, decodes.get());
    }

    @Test
    public void cancellationDuringNativeDecodeClosesLateResult() throws Exception {
        Fixture f = new Fixture();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch freed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        var task = new ImageLoadTask<AutoCloseable>(f.cache, URI_A, worker, bytes -> {
            decoding.countDown();
            assertTrue(finish.await(3, TimeUnit.SECONDS));
            return () -> { closes.incrementAndGet(); freed.countDown(); };
        });
        try {
            f.respond();
            assertTrue(decoding.await(3, TimeUnit.SECONDS));
            task.close();
            finish.countDown();
            assertTrue(freed.await(3, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertNull(task.take());
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void networkCompletionAndCleanupCannotDeadlockOrLeak() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                Fixture f = new Fixture();
                Image image = new Image();
                AtomicInteger decodes = new AtomicInteger();
                var task = new ImageLoadTask<>(f.cache, URI_A, Runnable::run, bytes -> {
                    decodes.incrementAndGet(); return image;
                });
                var response = workers.submit(f::respond);
                var cleanup = workers.submit(task::close);
                response.get(3, TimeUnit.SECONDS);
                cleanup.get(3, TimeUnit.SECONDS);
                assertEquals(decodes.get(), image.closes.get());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void queueRejectionAndNativeErrorsReachCallerForRetry() throws Exception {
        Fixture rejected = new Fixture();
        Executor full = runnable -> { throw new RejectedExecutionException("full"); };
        var rejectedTask = new ImageLoadTask<>(rejected.cache, URI_A, full, bytes -> new Image());
        rejected.respond();
        assertTrue(rejectedTask.isDone());
        try {
            rejectedTask.take();
            fail("Expected rejection");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof RejectedExecutionException);
        }
        rejectedTask.close();

        Fixture broken = new Fixture();
        var brokenTask = new ImageLoadTask<Image>(broken.cache, URI_A, Runnable::run, bytes -> { throw new OutOfMemoryError("native fixture"); });
        broken.respond();
        assertTrue(brokenTask.isDone());
        try {
            brokenTask.take();
            fail("Expected decoder error");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof OutOfMemoryError);
        }
        brokenTask.close();
    }
}
