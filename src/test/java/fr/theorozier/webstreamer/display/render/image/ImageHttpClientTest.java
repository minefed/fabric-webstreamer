package fr.theorozier.webstreamer.display.render.image;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ImageHttpClientTest {
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch headers = new CountDownLatch(1);
        final ImageHttpClient transport;

        Fixture(int idleMillis) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            timer.setRemoveOnCancelPolicy(true);
            transport = new ImageHttpClient(HttpClient.newHttpClient(), Duration.ofMillis(idleMillis), timer);
        }

        HttpRequest request(int headerMillis) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image"))
                    .timeout(Duration.ofMillis(headerMillis)).build();
        }

        void stalled(boolean sendHeaders) {
            server.createContext("/image", exchange -> {
                try {
                    if (sendHeaders) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                        headers.countDown();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        public void close() {
            release.countDown();
            server.stop(0);
            timer.shutdownNow();
        }
    }

    @Test
    public void progressAllowsTotalDurationLongerThanBothTimeouts() throws Exception {
        try (Fixture f = new Fixture(600)) {
            f.server.createContext("/image", exchange -> {
                try {
                    exchange.sendResponseHeaders(200, 12);
                    for (int i = 0; i < 12; i++) {
                        exchange.getResponseBody().write(i);
                        exchange.getResponseBody().flush();
                        Thread.sleep(100);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            f.server.start();
            long start = System.nanoTime();
            var response = f.transport.send(f.request(600)).get(5, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 600);
            assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, response.body());
            assertTrue("Completed bodies must release their timer", f.timer.getQueue().isEmpty());
        }
    }

    @Test
    public void idleBodyFailsAndRemovesItsTimer() throws Exception {
        try (Fixture f = new Fixture(300)) {
            f.stalled(true);
            var response = f.transport.send(f.request(1500));
            assertTrue(f.headers.await(2, TimeUnit.SECONDS));
            try {
                response.get(2, TimeUnit.SECONDS);
                fail("Expected idle body timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof HttpTimeoutException);
            }
            assertTrue(f.timer.getQueue().isEmpty());
        }
    }

    @Test
    public void cancelledBodyRemovesItsTimerImmediately() throws Exception {
        try (Fixture f = new Fixture(30_000)) {
            f.stalled(true);
            var response = f.transport.send(f.request(1500));
            assertTrue(f.headers.await(2, TimeUnit.SECONDS));
            response.cancel(true);
            assertTrue(response.isCancelled());
            assertTrue("Cancellation must not retain a 30-second timer", f.timer.getQueue().isEmpty());
        }
    }

    @Test
    public void headerTimeoutRemainsIndependentOfBodyIdleTimeout() throws Exception {
        try (Fixture f = new Fixture(30_000)) {
            f.stalled(false);
            try {
                f.transport.send(f.request(300)).get(2, TimeUnit.SECONDS);
                fail("Expected header timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof HttpTimeoutException);
            }
            assertTrue(f.timer.getQueue().isEmpty());
        }
    }
}
