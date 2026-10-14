package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs at most one factory per key while that call is in flight.
 *
 * <p>Callers that ask for the same key before the call finishes share one
 * future. The key is removed when that future completes, successfully or
 * not, so the next call runs the factory again. Results are not retained.
 *
 * <p>The factory should return a future promptly and do slow work inside it.
 * A throw, including an {@link Error}, or a null return becomes a failed
 * future. The method itself still returns that future.
 *
 * @param <T> the value produced by the factory
 */
public final class SingleFactoryCaller<T> {
    private final ConcurrentHashMap<String, CompletableFuture<T>> pendingTasks = new ConcurrentHashMap<>();

    /**
     * Returns the in-flight future for {@code key}, starting {@code valueFactory}
     * when no call for that key is already running.
     *
     * @param key          cache key; null, empty, and whitespace-only keys are rejected
     * @param valueFactory produces the future for a new call; must not be null
     * @return the shared future for this in-flight call
     * @throws IllegalArgumentException if {@code key} is null or blank, or if {@code valueFactory} is null
     */
    public CompletableFuture<T> getOrAddAsync(String key, Supplier<CompletableFuture<T>> valueFactory) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }
        if (valueFactory == null) {
            throw new IllegalArgumentException("ValueFactory cannot be null.");
        }

        CompletableFuture<T> created = new CompletableFuture<>();
        CompletableFuture<T> existing = pendingTasks.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<T> produced = invoke(valueFactory);
        produced.whenComplete((value, error) -> {
            // Drop the key before completing. complete() runs dependents inline,
            // and those dependents must not observe the finished call.
            pendingTasks.remove(key, created);
            if (error == null) {
                created.complete(value);
            } else {
                created.completeExceptionally(error);
            }
        });
        return created;
    }

    /**
     * Drops every in-flight entry. Futures already handed to callers still complete.
     */
    public void clear() {
        pendingTasks.clear();
    }

    /**
     * Returns the number of keys with a call still in flight.
     *
     * @return the number of keys currently being processed
     */
    public int getPendingTasksCount() {
        return pendingTasks.size();
    }

    private static <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> valueFactory) {
        try {
            CompletableFuture<T> produced = valueFactory.get();
            if (produced == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("ValueFactory returned null CompletableFuture"));
            }
            return produced;
        } catch (Throwable thrown) {
            return CompletableFuture.failedFuture(thrown);
        }
    }
}
