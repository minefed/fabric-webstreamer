package fr.theorozier.webstreamer.display.render.image;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Private, byte-budgeted HTTP cache for original image files, independent of GPU texture lifetime. */
public final class ImageSourceCache {
    private final long byteBudget;
    private final int concurrency;
    private final int queueLimit;
    private final Duration timeout;
    private final LongSupplier clock;
    private final Function<HttpRequest, CompletableFuture<HttpResponse<byte[]>>> transport;
    private final LinkedHashMap<URI, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<URI, Job> pending = new HashMap<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private long cachedBytes;
    private int active;

    public ImageSourceCache(HttpClient client, long byteBudget, int concurrency, int queueLimit, Duration timeout) {
        this(byteBudget, concurrency, queueLimit, timeout, System::currentTimeMillis,
                request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    ImageSourceCache(long byteBudget, int concurrency, int queueLimit, Duration timeout, LongSupplier clock,
                     Function<HttpRequest, CompletableFuture<HttpResponse<byte[]>>> transport) {
        if (byteBudget < 0 || concurrency < 1 || queueLimit < 1 || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid image cache limits");
        }
        this.byteBudget = byteBudget;
        this.concurrency = concurrency;
        this.queueLimit = queueLimit;
        this.timeout = timeout;
        this.clock = clock;
        this.transport = transport;
    }

    /** Closing a request releases only this consumer; another display may still need the same URL. */
    public synchronized Request request(URI uri) {
        Entry cached = entries.get(uri);
        if (cached != null && cached.freshUntil > clock.getAsLong()) {
            return new Request(null, CompletableFuture.completedFuture(cached.bytes));
        }
        Job job = pending.get(uri);
        if (job == null) {
            if (queue.size() >= queueLimit) {
                return new Request(null, CompletableFuture.failedFuture(new IOException("Image request queue is full")));
            }
            job = new Job(uri, cached);
            pending.put(uri, job);
            queue.addLast(job);
        }
        Request request = new Request(job, new CompletableFuture<>());
        job.requests.add(request);
        dispatch();
        return request;
    }

    /** A successful HTTP response can still be an invalid image. Do not retain that version after decode failure. */
    public synchronized void discard(URI uri, byte[] bytes) {
        Entry entry = entries.get(uri);
        if (entry != null && entry.bytes == bytes) {
            entries.remove(uri);
            cachedBytes -= entry.bytes.length;
        }
    }

    private void dispatch() {
        while (active < concurrency && !queue.isEmpty()) {
            Job job = queue.removeFirst();
            job.started = true;
            active++;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(job.uri).GET().timeout(timeout);
                if (job.previous != null) {
                    job.previous.headers.firstValue("ETag").ifPresent(value -> builder.header("If-None-Match", value));
                    job.previous.headers.firstValue("Last-Modified").ifPresent(value -> builder.header("If-Modified-Since", value));
                }
                job.network = transport.apply(builder.build());
                // The deadline covers the complete body, including a peer which sends headers then stalls.
                job.network.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .whenComplete((response, error) -> finish(job, response, error));
            } catch (RuntimeException exception) {
                finish(job, null, exception);
            }
        }
    }

    private synchronized void finish(Job job, HttpResponse<byte[]> response, Throwable error) {
        if (job.finished) {
            return;
        }
        job.finished = true;
        if (job.started) {
            active--;
        }
        pending.remove(job.uri, job);
        byte[] bytes = null;
        if (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof TimeoutException && job.network != null) {
            job.network.cancel(true);
        }
        if (!job.requests.isEmpty() && error == null) {
            try {
                HttpHeaders headers = response.headers();
                if (response.statusCode() == 200) {
                    bytes = response.body();
                } else if (response.statusCode() == 304 && job.previous != null) {
                    bytes = job.previous.bytes;
                    headers = mergeHeaders(job.previous.headers, headers);
                } else {
                    throw new IOException("Image HTTP request failed, status code: " + response.statusCode());
                }
                put(job.uri, bytes, headers);
            } catch (RuntimeException | IOException exception) {
                error = exception;
            }
        }
        for (Request request : job.requests) {
            if (error == null) {
                request.future.complete(bytes);
            } else {
                request.future.completeExceptionally(error);
            }
        }
        job.requests.clear();
        dispatch();
    }

    private void put(URI uri, byte[] bytes, HttpHeaders headers) {
        Entry old = entries.remove(uri);
        if (old != null) {
            cachedBytes -= old.bytes.length;
        }
        String control = String.join(",", headers.allValues("Cache-Control")).toLowerCase(Locale.ROOT);
        Map<String, String> directives = new HashMap<>();
        for (String part : control.split(",")) {
            String[] pair = part.trim().split("=", 2);
            directives.put(pair[0].trim(), pair.length == 1 ? "" : pair[1].trim().replace("\"", ""));
        }
        if (directives.containsKey("no-store") || headers.allValues("Vary").stream()
                .anyMatch(value -> List.of(value.split(",")).stream().anyMatch(part -> part.trim().equals("*")))
                || bytes.length > byteBudget || byteBudget == 0) {
            return;
        }
        long now = clock.getAsLong();
        long date = parseDate(headers.firstValue("Date").orElse(null), now);
        long lifetime = directives.containsKey("max-age") ? seconds(directives.get("max-age"))
                : Math.max(0, parseDate(headers.firstValue("Expires").orElse(null), date) - date);
        long age = Math.max(Math.max(0, now - date), seconds(headers.firstValue("Age").orElse("0")));
        long remaining = directives.containsKey("no-cache") ? 0 : Math.max(0, lifetime - age);
        entries.put(uri, new Entry(bytes, headers, now + Math.min(remaining, Long.MAX_VALUE - now)));
        cachedBytes += bytes.length;
        while (cachedBytes > byteBudget) {
            var oldest = entries.entrySet().iterator();
            Entry removed = oldest.next().getValue();
            oldest.remove();
            cachedBytes -= removed.bytes.length;
        }
    }

    private static long seconds(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? 0 : Math.min(seconds, Long.MAX_VALUE / 1000) * 1000;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static long parseDate(String value, long fallback) {
        try {
            return value == null ? fallback : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException exception) {
            return fallback;
        }
    }

    private static HttpHeaders mergeHeaders(HttpHeaders previous, HttpHeaders response) {
        Map<String, List<String>> merged = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(previous.map());
        // A new validation resets age; an omitted Date is treated as the receipt time, not the old response date.
        merged.remove("Date");
        merged.remove("Age");
        merged.putAll(response.map());
        return HttpHeaders.of(merged, (name, value) -> true);
    }

    public final class Request implements AutoCloseable {
        private final Job job;
        private final CompletableFuture<byte[]> future;
        private boolean closed;

        private Request(Job job, CompletableFuture<byte[]> future) {
            this.job = job;
            this.future = future;
        }

        /** Bytes are immutable to consumers. Decode without modifying the shared array. */
        public CompletableFuture<byte[]> future() {
            return future;
        }

        @Override
        public void close() {
            synchronized (ImageSourceCache.this) {
                if (closed) {
                    return;
                }
                closed = true;
                future.cancel(false);
                if (job != null && !job.finished) {
                    job.requests.remove(this);
                    if (job.requests.isEmpty()) {
                        queue.remove(job);
                        // Mark finished before cancellation can synchronously invoke the completion callback.
                        finish(job, null, new java.util.concurrent.CancellationException());
                        if (job.network != null) {
                            job.network.cancel(true);
                        }
                    }
                }
            }
        }
    }

    private record Entry(byte[] bytes, HttpHeaders headers, long freshUntil) { }

    private static final class Job {
        final URI uri;
        final Entry previous;
        final List<Request> requests = new ArrayList<>();
        CompletableFuture<HttpResponse<byte[]>> network;
        boolean started;
        boolean finished;

        Job(URI uri, Entry previous) {
            this.uri = uri;
            this.previous = previous;
        }
    }
}
