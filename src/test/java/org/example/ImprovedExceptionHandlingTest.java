package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the improved exception handling in SingleFactoryCaller.
 */
@DisplayName("Improved Exception Handling Tests")
public class ImprovedExceptionHandlingTest {

    private SingleFactoryCaller<String> cache;

    @BeforeEach
    void setUp() {
        cache = new SingleFactoryCaller<>();
    }

    @Test
    @DisplayName("Should handle RuntimeException properly in CompletableFuture")
    void shouldHandleRuntimeExceptionProperlyInCompletableFuture() throws Exception {
        RuntimeException originalException = new IllegalStateException("Test runtime exception");
        
        // RuntimeException should be wrapped in a failed CompletableFuture
        CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
            throw originalException;
        });
        
        ExecutionException thrownException = assertThrows(
            ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS)
        );
        
        assertSame(originalException, thrownException.getCause());
        assertEquals(0, cache.getPendingTasksCount(), "Failed entry should be removed");
    }

    @Test
    @DisplayName("Should handle checked exceptions properly")
    void shouldHandleCheckedExceptionsAsFailedFuture() throws Exception {
        Exception checkedException = new Exception("Test checked exception");
        
        CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
            throw new RuntimeException(checkedException); // Simulate checked exception wrapped in runtime
        });
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS)
        );
        
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertEquals(0, cache.getPendingTasksCount(), "Failed entry should be removed");
    }

    @Test
    @DisplayName("Should allow immediate retry after RuntimeException")
    void shouldAllowImmediateRetryAfterRuntimeException() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        
        // First call throws RuntimeException
        CompletableFuture<String> firstAttempt = cache.getOrAddAsync("key", () -> {
            if (callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("First attempt fails");
            }
            return CompletableFuture.completedFuture("success");
        });
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> firstAttempt.get(1, TimeUnit.SECONDS)
        );
        assertTrue(exception.getCause() instanceof IllegalStateException);
        
        assertEquals(0, cache.getPendingTasksCount(), "Cache should be clean after exception");
        
        // Second call should succeed immediately
        CompletableFuture<String> secondAttempt = cache.getOrAddAsync("key", () -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture("success");
        });
        
        assertDoesNotThrow(() -> {
            String result = secondAttempt.get(1, TimeUnit.SECONDS);
            assertEquals("success", result);
        });
        
        assertEquals(2, callCount.get(), "Should have been called exactly twice");
    }

    @Test
    @DisplayName("Should handle concurrent access during exception scenarios")
    void shouldHandleConcurrentAccessDuringExceptionScenarios() throws Exception {
        AtomicInteger supplierCallCount = new AtomicInteger(0);
        RuntimeException testException = new RuntimeException("Concurrent test exception");
        
        // Launch multiple concurrent requests
        CompletableFuture<Void> thread1 = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<String> future = cache.getOrAddAsync("concurrent-key", () -> {
                    supplierCallCount.incrementAndGet();
                    // Add a small delay to increase chance of race conditions
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw testException;
                });
                future.get(1, TimeUnit.SECONDS);
                fail("Expected ExecutionException");
            } catch (ExecutionException e) {
                // Expected - verify it's our exception
                assertEquals(testException, e.getCause());
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            }
        });
        
        CompletableFuture<Void> thread2 = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<String> future = cache.getOrAddAsync("concurrent-key", () -> {
                    supplierCallCount.incrementAndGet();
                    throw testException;
                });
                future.get(1, TimeUnit.SECONDS);
                fail("Expected ExecutionException");
            } catch (ExecutionException e) {
                // Expected - verify it's our exception
                assertEquals(testException, e.getCause());
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            }
        });
        
        CompletableFuture<Void> thread3 = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<String> future = cache.getOrAddAsync("concurrent-key", () -> {
                    supplierCallCount.incrementAndGet();
                    throw testException;
                });
                future.get(1, TimeUnit.SECONDS);
                fail("Expected ExecutionException");
            } catch (ExecutionException e) {
                // Expected - verify it's our exception
                assertEquals(testException, e.getCause());
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            }
        });
        
        // Wait for all threads to complete
        CompletableFuture.allOf(thread1, thread2, thread3).get(5, TimeUnit.SECONDS);
        
        // The supplier should only be called once due to the lazy initialization
        assertEquals(1, supplierCallCount.get(), "Supplier should only be called once even with concurrent access");
        assertEquals(0, cache.getPendingTasksCount(), "Cache should be clean after all exceptions");
    }

    @Test
    @DisplayName("Should handle memory cleanup properly after exceptions")
    void shouldHandleMemoryCleanupProperlyAfterExceptions() throws Exception {
        // This test verifies that failed entries don't cause memory leaks
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            CompletableFuture<String> future = cache.getOrAddAsync("key-" + iteration, () -> {
                throw new RuntimeException("Exception " + iteration);
            });
            
            ExecutionException exception = assertThrows(ExecutionException.class, () -> {
                future.get(1, TimeUnit.SECONDS);
            });
            assertTrue(exception.getCause() instanceof RuntimeException);
        }
        
        // All failed entries should be cleaned up
        assertEquals(0, cache.getPendingTasksCount(), "All failed entries should be cleaned up");
        
        // Verify we can still use the cache normally
        CompletableFuture<String> future = cache.getOrAddAsync("success-key", 
            () -> CompletableFuture.completedFuture("success"));
        
        assertDoesNotThrow(() -> {
            String result = future.get(1, TimeUnit.SECONDS);
            assertEquals("success", result);
        });
    }
}
