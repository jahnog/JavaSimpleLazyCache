package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the improved version with proper exception handling.
 */
@DisplayName("Improved SingleFactoryCaller Exception Handling Tests")
public class SingleFactoryCallerTest {

    private SingleFactoryCaller<String> cache;

    @BeforeEach
    void setUp() {
        cache = new SingleFactoryCaller<>();
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when valueFactory is null")
    void shouldThrowExceptionWhenValueFactoryIsNull() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> cache.getOrAddAsync("key", null)
        );
        
        assertEquals("ValueFactory cannot be null.", exception.getMessage());
    }

    @Test
    @DisplayName("Should handle factory exception properly wrapped in CompletableFuture")
    void shouldHandleFactoryExceptionProperlyWrapped() throws Exception {
        RuntimeException factoryException = new RuntimeException("Factory error");
        
        // Exception should be wrapped in CompletableFuture, not thrown synchronously
        CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
            throw factoryException;
        });
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS)
        );
        
        assertSame(factoryException, exception.getCause());
    }

    @Test
    @DisplayName("Should handle null CompletableFuture from factory")
    void shouldHandleNullCompletableFutureFromFactory() throws Exception {
        CompletableFuture<String> future = cache.getOrAddAsync("key", () -> null);
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS)
        );
        
        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertEquals("ValueFactory returned null CompletableFuture", exception.getCause().getMessage());
    }

    @Test
    @DisplayName("Should allow retry after factory exception")
    void shouldAllowRetryAfterFactoryException() throws Exception {
        RuntimeException factoryException = new RuntimeException("Factory error");
        
        // First attempt - should fail
        CompletableFuture<String> firstAttempt = cache.getOrAddAsync("key", () -> {
            throw factoryException;
        });
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> firstAttempt.get(1, TimeUnit.SECONDS)
        );
        assertSame(factoryException, exception.getCause());
        
        // Verify the failed entry was removed
        assertEquals(0, cache.getPendingTasksCount());

        // Second attempt - should succeed
        CompletableFuture<String> secondAttempt = cache.getOrAddAsync("key", 
            () -> CompletableFuture.completedFuture("success"));
        
        String result = secondAttempt.get(1, TimeUnit.SECONDS);
        assertEquals("success", result);
    }

    @Test
    @DisplayName("Should maintain consistent state after exception")
    void shouldMaintainConsistentStateAfterException() throws Exception {
        RuntimeException factoryException = new RuntimeException("Factory error");
        
        // First call throws exception
        CompletableFuture<String> firstFuture = cache.getOrAddAsync("key", () -> {
            throw factoryException;
        });
        
        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> firstFuture.get(1, TimeUnit.SECONDS)
        );
        assertSame(factoryException, exception.getCause());

        // Cache should be clean after exception
        assertEquals(0, cache.getPendingTasksCount());

        // Second call with same key should work independently
        CompletableFuture<String> secondFuture = cache.getOrAddAsync("key", 
            () -> CompletableFuture.completedFuture("success"));
        
        String result = secondFuture.get(1, TimeUnit.SECONDS);
        assertEquals("success", result);
    }

    @Test
    @DisplayName("Should handle concurrent requests with factory exception")
    void shouldHandleConcurrentRequestsWithFactoryException() throws InterruptedException {
        RuntimeException factoryException = new RuntimeException("Concurrent factory error");
        
        // Start multiple threads with the same key
        CompletableFuture<String> future1 = cache.getOrAddAsync("concurrent-key", () -> {
            throw factoryException;
        });
        CompletableFuture<String> future2 = cache.getOrAddAsync("concurrent-key", () -> {
            throw factoryException;
        });
        CompletableFuture<String> future3 = cache.getOrAddAsync("concurrent-key", () -> {
            throw factoryException;
        });

        // All futures should complete with the same exception
        assertAll(
            () -> {
                ExecutionException ex = assertThrows(ExecutionException.class, 
                    () -> future1.get(1, TimeUnit.SECONDS));
                assertSame(factoryException, ex.getCause());
            },
            () -> {
                ExecutionException ex = assertThrows(ExecutionException.class, 
                    () -> future2.get(1, TimeUnit.SECONDS));
                assertSame(factoryException, ex.getCause());
            },
            () -> {
                ExecutionException ex = assertThrows(ExecutionException.class, 
                    () -> future3.get(1, TimeUnit.SECONDS));
                assertSame(factoryException, ex.getCause());
            }
        );
        
        // Cache should be clean after all exceptions
        assertEquals(0, cache.getPendingTasksCount());
    }
}
