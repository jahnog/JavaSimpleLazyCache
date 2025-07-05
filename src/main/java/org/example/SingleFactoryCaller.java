package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A thread-safe cache that ensures only one factory call per key is executed concurrently.
 * This prevents duplicate expensive operations for the same key.
 * 
 * IMPROVED VERSION with proper exception handling.
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
     * @throws IllegalArgumentException if the key is null or empty, or if valueFactory is null
     */
    public CompletableFuture<T> getOrAddAsync(String key, Supplier<CompletableFuture<T>> valueFactory) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }
        
        if (valueFactory == null) {
            throw new IllegalArgumentException("ValueFactory cannot be null.");
        }

        Lazy<CompletableFuture<T>> lazyValue = pendingTasks.computeIfAbsent(key, k ->
            new Lazy<>(() -> {
                try {
                    CompletableFuture<T> future = valueFactory.get();
                    if (future == null) {
                        return CompletableFuture.failedFuture(
                            new IllegalStateException("ValueFactory returned null CompletableFuture")
                        );
                    }
                    return future;
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            })
        );

        CompletableFuture<T> future;
        try {
            future = lazyValue.getValue();
        } catch (Exception e) {
            // If lazy initialization fails, remove the failed entry and create a failed future
            pendingTasks.remove(key, lazyValue);
            return CompletableFuture.failedFuture(e);
        }
        
        // Remove the key from pending tasks when the future completes (either successfully or exceptionally)
        return future.whenComplete((result, throwable) -> {
            pendingTasks.remove(key, lazyValue);
        });
    }

    /**
     * Clears all pending tasks. Useful for testing or cleanup.
     */
    public void clear() {
        pendingTasks.clear();
    }

    /**
     * Returns the number of pending tasks.
     * @return the number of keys currently being processed
     */
    public int getPendingTasksCount() {
        return pendingTasks.size();
    }

    /**
     * A thread-safe lazy initialization wrapper with improved exception handling.
     * Ensures that the supplier is called only once, even in concurrent scenarios.
     * If the supplier throws an exception, subsequent calls will also throw the same exception.
     *
     * @param <T> The type of value to lazily initialize
     */
    private static class Lazy<T> {
        private volatile T value;
        private volatile Exception exception;
        private volatile boolean initialized = false;
        private final Object lock = new Object();
        private volatile Supplier<T> supplier; // Make volatile and allow nulling for GC

        public Lazy(Supplier<T> supplier) {
            if (supplier == null) {
                throw new IllegalArgumentException("Supplier cannot be null.");
            }
            this.supplier = supplier;
        }

        public T getValue() throws Exception {
            // First check - no synchronization needed for the happy path
            if (initialized) {
                if (exception != null) {
                    throw exception;
                }
                return value;
            }
            
            // Double-checked locking with proper exception handling
            synchronized (lock) {
                if (!initialized) {
                    try {
                        value = supplier.get();
                        // Clear supplier reference to help with GC
                        supplier = null;
                    } catch (Exception e) {
                        exception = e;
                        supplier = null; // Clear reference even on failure
                    } finally {
                        initialized = true;
                    }
                }
            }
            
            // Re-check after synchronization
            if (exception != null) {
                throw exception;
            }
            
            return value;
        }
    }
}
