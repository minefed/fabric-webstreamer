package fr.theorozier.webstreamer.display.render.image;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

/** Transfers one decoded native image to the render thread, or closes it even when cancellation races decoding. */
public final class ImageLoadTask<T extends AutoCloseable> implements AutoCloseable {
    @FunctionalInterface
    public interface Decoder<T> {
        T decode(byte[] source) throws Exception;
    }

    private final ImageSourceCache cache;
    private final URI uri;
    private final Executor executor;
    private final Decoder<T> decoder;
    private final ImageSourceCache.Request request;
    private FutureTask<Void> decodeWork;
    private T result;
    private Throwable failure;
    private boolean done;
    private boolean closed;

    public ImageLoadTask(ImageSourceCache cache, URI uri, Executor executor, Decoder<T> decoder) {
        this.cache = cache;
        this.uri = uri;
        this.executor = executor;
        this.decoder = decoder;
        request = cache.request(uri);
        request.future().whenComplete((bytes, error) -> {
            if (error == null) {
                scheduleDecode(bytes);
            } else {
                fail(error);
            }
        });
    }

    private void scheduleDecode(byte[] bytes) {
        FutureTask<Void> work;
        synchronized (this) {
            if (closed) {
                return;
            }
            work = new FutureTask<>(() -> {
                T image = null;
                try {
                    synchronized (this) {
                        if (closed) {
                            return null;
                        }
                    }
                    image = Objects.requireNonNull(decoder.decode(bytes));
                    synchronized (this) {
                        if (!closed) {
                            result = image;
                            image = null; // Ownership moves to take(), or close() if the renderer goes away.
                            done = true;
                        }
                    }
                } catch (Throwable exception) {
                    cache.discard(uri, bytes);
                    fail(exception);
                } finally {
                    closeResult(image);
                    request.close();
                }
                return null;
            });
            decodeWork = work;
        }
        try {
            executor.execute(work);
        } catch (RuntimeException exception) {
            fail(exception);
            request.close();
        }
    }

    private synchronized void fail(Throwable exception) {
        if (!closed) {
            failure = exception instanceof CompletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            done = true;
        }
    }

    public synchronized boolean isDone() {
        return done;
    }

    /** Nonblocking, one-time ownership transfer. The caller must close the returned image after uploading it. */
    public synchronized T take() throws ExecutionException {
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        T image = result;
        result = null;
        return image;
    }

    @Override
    public void close() {
        T image;
        FutureTask<Void> work;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            done = true;
            image = result;
            result = null;
            work = decodeWork;
        }
        // Never acquire the HTTP cache lock while holding this object's lock: completion callbacks run in reverse order.
        request.close();
        if (work != null) {
            // Native decoders need not support interruption. A running decoder closes its late result above.
            work.cancel(false);
            if (executor instanceof ThreadPoolExecutor pool) {
                pool.remove(work);
            }
        }
        closeResult(image);
    }

    private static void closeResult(AutoCloseable image) {
        if (image != null) {
            try {
                image.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not release decoded image", exception);
            }
        }
    }
}
