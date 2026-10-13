package fr.theorozier.webstreamer.display.render.image;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ImageSourceCacheTest {
    private static final URI A = URI.create("https://example.invalid/a.png");
    private static final URI B = URI.create("https://example.invalid/b.png");
    private static final URI C = URI.create("https://example.invalid/c.png");

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        final List<HttpRequest> requests = new ArrayList<>();
        final List<CompletableFuture<HttpResponse<byte[]>>> responses = new ArrayList<>();
        final ImageSourceCache cache;

        Fixture(long budget, int concurrency, int queue, long timeoutMillis) {
            cache = new ImageSourceCache(budget, concurrency, queue, Duration.ofMillis(timeoutMillis), now::get,
                    request -> {
                        requests.add(request);
                        CompletableFuture<HttpResponse<byte[]>> response = new CompletableFuture<>();
                        responses.add(response);
                        return response;
                    });
        }

        Fixture() { this(1024, 4, 8, 5000); }

        void answer(int index, int status, String body, Map<String, List<String>> headers) {
            responses.get(index).complete(response(requests.get(index), status, body, headers));
        }
    }

    @Test
    public void freshBytesSurviveClosingGpuConsumers() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        f.answer(0, 200, "original pixels", Map.of("Cache-Control", List.of("public, max-age=60")));
        byte[] original = first.future().get();
        first.close();
        f.now.addAndGet(20_000);
        try (var again = f.cache.request(A)) {
            assertSame(original, again.future().get());
            assertEquals(1, f.requests.size());
        }
    }

    @Test
    public void staleEtagRevalidatesAnd304ExtendsFreshness() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        f.answer(0, 200, "v1", Map.of("ETag", List.of("\"v1\""), "Cache-Control", List.of("max-age=1")));
        byte[] original = first.future().get();
        first.close();
        f.now.addAndGet(2000);
        var second = f.cache.request(A);
        assertEquals("\"v1\"", f.requests.get(1).headers().firstValue("If-None-Match").orElseThrow());
        f.answer(1, 304, "", Map.of("Cache-Control", List.of("max-age=60")));
        assertSame(original, second.future().get());
        second.close();
        f.now.addAndGet(30_000);
        assertSame(original, f.cache.request(A).future().get());
        assertEquals(2, f.requests.size());
    }

    @Test
    public void changed200ReplacesBytesAndNoCacheAlwaysRevalidates() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        String modified = "Tue, 14 Nov 2023 20:00:00 GMT";
        f.answer(0, 200, "old", Map.of("Last-Modified", List.of(modified), "Cache-Control", List.of("no-cache, max-age=600")));
        assertEquals("old", new String(first.future().get(), StandardCharsets.UTF_8));
        first.close();
        var second = f.cache.request(A);
        assertEquals(modified, f.requests.get(1).headers().firstValue("If-Modified-Since").orElseThrow());
        f.answer(1, 200, "new", Map.of("Cache-Control", List.of("max-age=600")));
        assertEquals("new", new String(second.future().get(), StandardCharsets.UTF_8));
        assertSame(second.future().get(), f.cache.request(A).future().get());
    }

    @Test
    public void noStoreAndVaryStarDoNotRetainOriginalOrValidators() throws Exception {
        for (Map<String, List<String>> headers : List.of(
                Map.of("Cache-Control", List.of("no-store, max-age=600"), "ETag", List.of("\"private\"")),
                Map.of("Cache-Control", List.of("max-age=600"), "Vary", List.of("Accept, *")))) {
            Fixture f = new Fixture();
            var first = f.cache.request(A);
            f.answer(0, 200, "private", headers);
            first.future().get();
            first.close();
            var second = f.cache.request(A);
            assertEquals(2, f.requests.size());
            assertTrue(f.requests.get(1).headers().firstValue("If-None-Match").isEmpty());
            second.close();
        }
    }

    @Test
    public void absentFreshnessRequiresValidationAndAgeCountsAgainstMaxAge() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        f.answer(0, 200, "old", Map.of("ETag", List.of("\"old\"")));
        first.future().get();
        first.close();
        var second = f.cache.request(A);
        assertEquals(2, f.requests.size());
        f.answer(1, 304, "", Map.of("Cache-Control", List.of("max-age=60"), "Age", List.of("59")));
        second.future().get();
        second.close();
        f.now.addAndGet(2000);
        f.cache.request(A).close();
        assertEquals(3, f.requests.size());
    }

    @Test
    public void expiresAndDateSetFreshness() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        f.answer(0, 200, "v1", Map.of("Date", List.of("Tue, 14 Nov 2023 22:13:20 GMT"),
                "Expires", List.of("Tue, 14 Nov 2023 22:14:20 GMT")));
        first.future().get();
        f.now.addAndGet(59_000);
        assertTrue(f.cache.request(A).future().isDone());
        f.now.addAndGet(2000);
        assertFalse(f.cache.request(A).future().isDone());
        assertEquals(2, f.requests.size());
    }

    @Test
    public void byteBudgetEvictsLeastRecentlyUsedOriginal() throws Exception {
        Fixture f = new Fixture(8, 4, 8, 5000);
        var a = f.cache.request(A);
        f.answer(0, 200, "aaaa", Map.of("Cache-Control", List.of("max-age=600")));
        a.future().get();
        var b = f.cache.request(B);
        f.answer(1, 200, "bbbb", Map.of("Cache-Control", List.of("max-age=600")));
        b.future().get();
        f.cache.request(A).close();
        var c = f.cache.request(C);
        f.answer(2, 200, "cccc", Map.of("Cache-Control", List.of("max-age=600")));
        c.future().get();
        assertTrue(f.cache.request(A).future().isDone());
        assertFalse(f.cache.request(B).future().isDone());
        assertEquals(4, f.requests.size());
    }

    @Test
    public void oversizedImageStillLoadsWithoutCachingOrResampling() throws Exception {
        Fixture f = new Fixture(2, 4, 8, 5000);
        var a = f.cache.request(A);
        f.answer(0, 200, "large original", Map.of("Cache-Control", List.of("max-age=600")));
        assertEquals("large original", new String(a.future().get(), StandardCharsets.UTF_8));
        assertFalse(f.cache.request(A).future().isDone());
    }

    @Test
    public void duplicateRequestSharesNetworkAndOneCancellationDoesNotCancelOtherDisplay() throws Exception {
        Fixture f = new Fixture();
        var first = f.cache.request(A);
        var second = f.cache.request(A);
        assertEquals(1, f.requests.size());
        first.close();
        assertFalse(f.responses.get(0).isCancelled());
        f.answer(0, 200, "same", Map.of());
        assertEquals("same", new String(second.future().get(), StandardCharsets.UTF_8));
        assertTrue(first.future().isCancelled());
    }

    @Test
    public void lastConsumerCancellationReleasesSlotAndCancelsQueuedWork() {
        Fixture f = new Fixture(1024, 1, 2, 5000);
        var a = f.cache.request(A);
        var b = f.cache.request(B);
        var c = f.cache.request(C);
        b.close();
        a.close();
        assertTrue(f.responses.get(0).isCancelled());
        assertEquals(2, f.requests.size());
        assertEquals(C, f.requests.get(1).uri());
        c.close();
        assertTrue(f.responses.get(1).isCancelled());
        f.cache.request(A).close();
        assertEquals(3, f.requests.size());
    }

    @Test
    public void queueIsBoundedAndRejectsWithoutBlockingCaller() throws Exception {
        Fixture f = new Fixture(1024, 1, 1, 5000);
        var a = f.cache.request(A);
        var b = f.cache.request(B);
        var rejected = f.cache.request(C);
        assertTrue(rejected.future().isCompletedExceptionally());
        a.close();
        f.answer(1, 200, "b", Map.of());
        assertEquals("b", new String(b.future().get(), StandardCharsets.UTF_8));
    }

    @Test
    public void networkTimeoutReleasesSlotAndNextAttemptCanRecover() throws Exception {
        Fixture f = new Fixture(1024, 1, 2, 40);
        var a = f.cache.request(A);
        assertEquals(Duration.ofMillis(40), f.requests.get(0).timeout().orElseThrow());
        f.responses.get(0).completeExceptionally(new java.net.http.HttpTimeoutException("fixture"));
        try {
            a.future().get(2, TimeUnit.SECONDS);
            fail("Expected timeout");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof java.net.http.HttpTimeoutException);
        }
        var retry = f.cache.request(A);
        f.answer(1, 200, "recovered", Map.of());
        assertEquals("recovered", new String(retry.future().get(), StandardCharsets.UTF_8));
    }

    @Test
    public void httpErrorsDoNotBecomeImagesAndDecodeFailureDiscardsOnlyItsOwnVersion() throws Exception {
        Fixture f = new Fixture();
        var failed = f.cache.request(A);
        f.answer(0, 503, "bad", Map.of("Cache-Control", List.of("max-age=600")));
        assertTrue(failed.future().isCompletedExceptionally());
        var next = f.cache.request(A);
        f.answer(1, 200, "invalid png", Map.of("Cache-Control", List.of("max-age=600")));
        byte[] invalid = next.future().get();
        f.cache.discard(A, invalid);
        var valid = f.cache.request(A);
        f.answer(2, 200, "valid png", Map.of("Cache-Control", List.of("max-age=600")));
        valid.future().get();
        f.cache.discard(A, invalid);
        assertSame(valid.future().get(), f.cache.request(A).future().get());
        assertEquals(3, f.requests.size());
    }

    @Test
    public void actualHttpClientGetsFreshHitAndConditional304WithoutBodyDownload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger bodies = new AtomicInteger();
        server.createContext("/image.png", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"image-v1\"");
            exchange.getResponseHeaders().add("Cache-Control", "max-age=0");
            if ("\"image-v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.getResponseHeaders().set("Cache-Control", "max-age=600");
                exchange.sendResponseHeaders(304, -1);
            } else {
                bodies.incrementAndGet();
                byte[] pixels = {0, 1, 2, 3};
                exchange.sendResponseHeaders(200, pixels.length);
                exchange.getResponseBody().write(pixels);
            }
            exchange.close();
        });
        server.start();
        try {
            ImageSourceCache cache = new ImageSourceCache(HttpClient.newHttpClient(), 1024, 2, 8, Duration.ofSeconds(3));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image.png");
            byte[] original;
            try (var first = cache.request(uri)) { original = first.future().get(5, TimeUnit.SECONDS); }
            try (var second = cache.request(uri)) { assertSame(original, second.future().get(5, TimeUnit.SECONDS)); }
            try (var third = cache.request(uri)) { assertSame(original, third.future().get(5, TimeUnit.SECONDS)); }
            assertEquals(2, requests.get());
            assertEquals(1, bodies.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void actualHttpClientTimesOutBodyWithoutProgressAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stalled.png", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                headersSent.countDown();
                release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ImageSourceCache cache = new ImageSourceCache(HttpClient.newHttpClient(), 1024, 1, 8,
                    Duration.ofSeconds(1), Duration.ofMillis(300));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled.png");
            try (var request = cache.request(uri)) {
                assertTrue(headersSent.await(2, TimeUnit.SECONDS));
                try {
                    request.future().get(2, TimeUnit.SECONDS);
                    fail("Body stalled after headers must time out");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof java.util.concurrent.TimeoutException
                            || expected.getCause() instanceof java.net.http.HttpTimeoutException);
                }
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static HttpResponse<byte[]> response(HttpRequest request, int status, String body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
            public byte[] body() { return body.getBytes(StandardCharsets.UTF_8); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
