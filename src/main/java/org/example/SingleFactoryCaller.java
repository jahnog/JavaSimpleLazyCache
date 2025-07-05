package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A thread-safe cache that ensures only one factory call per key is executed concurrently.
 * This prevents duplicate expensive operations for the same key.
 *
 * @param <T> The type of value to cache (must be a reference type)
 */
public class SingleFactoryCaller<T> {
    private final ConcurrentHashMap<String, Lazy<CompletableFuture<T>>> pendingTasks 
        = new ConcurrentHashMap<>();

    /**
     * Gets or adds a value asynchronously using the provided factory function.
     * If multiple threads request the same key simultaneously, only one factory call will be made.
     *
     * @param key The cache key
     * @param valueFactory The factory function to create the value if not present
     * @return A CompletableFuture containing the cached or newly created value
     * @throws IllegalArgumentException if the key is null or empty
     */
    public CompletableFuture<T> getOrAddAsync(String key, Supplier<CompletableFuture<T>> valueFactory) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }

        Lazy<CompletableFuture<T>> lazyValue = pendingTasks.computeIfAbsent(key, k ->
            new Lazy<>(() -> valueFactory.get())
        );

        CompletableFuture<T> future = lazyValue.getValue();
        
        // Remove the key from pending tasks when the future completes (either successfully or exceptionally)
        return future.whenComplete((result, throwable) -> {
            pendingTasks.remove(key, lazyValue);
        });
    }

    /**
     * A thread-safe lazy initialization wrapper.
     * Ensures that the supplier is called only once, even in concurrent scenarios.
     *
     * @param <T> The type of value to lazily initialize
     */
    private static class Lazy<T> {
        private volatile T value;
        private volatile boolean initialized = false;
        private final Object lock = new Object();
        private final Supplier<T> supplier;

        public Lazy(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        public T getValue() {
            if (!initialized) {
                synchronized (lock) {
                    if (!initialized) {
                        value = supplier.get();
                        initialized = true;
                    }
                }
            }
            return value;
        }
    }
}