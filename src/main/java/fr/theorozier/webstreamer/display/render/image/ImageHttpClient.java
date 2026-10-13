package fr.theorozier.webstreamer.display.render.image;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Observes body progress without imposing a total download duration or changing the HTTP subscriber's event order. */
final class ImageHttpClient {
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private final HttpClient client;
    private final Duration idleTimeout;
    private final ScheduledThreadPoolExecutor scheduler;

    ImageHttpClient(HttpClient client, Duration idleTimeout) {
        this(client, idleTimeout, TIMER);
    }

    ImageHttpClient(HttpClient client, Duration idleTimeout, ScheduledThreadPoolExecutor scheduler) {
        if (idleTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid image body idle timeout");
        }
        this.client = client;
        this.idleTimeout = idleTimeout;
        this.scheduler = scheduler;
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "WebStreamer Image Timeout");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<HttpResponse<byte[]>>> exchange = new AtomicReference<>();
        Watchdog watchdog = new Watchdog(() -> result.completeExceptionally(
                new HttpTimeoutException("Image body made no progress for " + idleTimeout.toSeconds() + " seconds")));
        result.whenComplete((response, error) -> {
            watchdog.close();
            CompletableFuture<?> network = exchange.get();
            if (error != null && network != null) {
                network.cancel(true);
            }
        });
        try {
            CompletableFuture<HttpResponse<byte[]>> network = client.sendAsync(request, info -> {
                watchdog.progress();
                HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
                return new HttpResponse.BodySubscriber<byte[]>() {
                    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
                    public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
                    public void onNext(List<ByteBuffer> buffers) {
                        if (buffers.stream().anyMatch(ByteBuffer::hasRemaining)) {
                            watchdog.progress();
                        }
                        delegate.onNext(buffers);
                    }
                    public void onError(Throwable error) {
                        watchdog.close();
                        delegate.onError(error);
                    }
                    public void onComplete() {
                        watchdog.close();
                        delegate.onComplete();
                    }
                };
            });
            exchange.set(network);
            // Cancellation may race sendAsync returning or the body handler being installed.
            if (result.isDone()) {
                network.cancel(true);
            }
            network.whenComplete((response, error) -> {
                if (error == null) {
                    result.complete(response);
                } else {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private final class Watchdog implements AutoCloseable {
        private final Runnable expired;
        private ScheduledFuture<?> timer;
        private long generation;
        private boolean closed;

        private Watchdog(Runnable expired) { this.expired = expired; }

        synchronized void progress() {
            if (closed) {
                return;
            }
            if (timer != null) {
                timer.cancel(false);
            }
            long current = ++generation;
            timer = scheduler.schedule(() -> expire(current), idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void expire(long expected) {
            synchronized (this) {
                if (closed || generation != expected) {
                    return;
                }
                closed = true;
                timer = null;
            }
            // Completing the HTTP future can enter the cache. Never do so while holding the watchdog lock.
            expired.run();
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }
    }
}
