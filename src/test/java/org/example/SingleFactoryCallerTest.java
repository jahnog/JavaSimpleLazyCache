package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleFactoryCallerTest {

    private static final int STAMPEDE = 32;
    private static final int LARGE_SET = 128;

    private SingleFactoryCaller<String> cache;

    @BeforeEach
    void setUp() {
        cache = new SingleFactoryCaller<>();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(1, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for latch", interrupted);
        }
    }

    @Nested
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    class RejectsInvalidArguments {

        @Test
        void rejectsNullFactory() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.getOrAddAsync("key", null));

            assertEquals("ValueFactory cannot be null.", exception.getMessage());
            assertEquals(0, cache.getPendingTasksCount());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        void rejectsBlankKey(String key) {
            AtomicInteger calls = new AtomicInteger();

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.getOrAddAsync(key, () -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture("value");
                    }));

            assertEquals("Key cannot be null or empty.", exception.getMessage());
            assertEquals(0, calls.get());
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void checksKeyBeforeFactory() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.getOrAddAsync(null, null));

            assertEquals("Key cannot be null or empty.", exception.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {" k ", "\u00A0"})
        void acceptsKeyWhoseTrimIsNotEmpty(String key) throws Exception {
            String value = await(cache.getOrAddAsync(key, () -> CompletableFuture.completedFuture("ok")));

            assertEquals("ok", value);
        }

        @Test
        void rejectedBlankKeyLeavesInFlightEntry() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> inFlight = cache.getOrAddAsync("key", () -> gate);

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.getOrAddAsync(" ", () -> CompletableFuture.completedFuture("other")));

            assertEquals("Key cannot be null or empty.", exception.getMessage());
            assertEquals(1, cache.getPendingTasksCount());
            assertFalse(inFlight.isDone());
            gate.complete("value");
            assertEquals("value", await(inFlight));
        }

        @Test
        void rejectedNullFactoryLeavesInFlightEntry() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> inFlight = cache.getOrAddAsync("key", () -> gate);

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.getOrAddAsync("key", null));

            assertEquals("ValueFactory cannot be null.", exception.getMessage());
            assertEquals(1, cache.getPendingTasksCount());
            assertFalse(inFlight.isDone());
            gate.complete("value");
            assertEquals("value", await(inFlight));
        }
    }

    @Nested
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    class ReturnsTheFactoryResult {

        @Test
        void returnsCompletedValue() throws Exception {
            String value = await(cache.getOrAddAsync("key", () -> CompletableFuture.completedFuture("value")));

            assertEquals("value", value);
        }

        @Test
        void returnsNullValue() throws Exception {
            String value = await(cache.getOrAddAsync("key", () -> CompletableFuture.completedFuture(null)));

            assertNull(value);
        }

        @Test
        void publishesThrownRuntimeException() throws Exception {
            IllegalStateException failure = new IllegalStateException("fail");

            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
                throw failure;
            });

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertSame(failure, thrown.getCause());
        }

        @Test
        void publishesNullFutureAsIllegalState() throws Exception {
            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> null);

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertEquals("ValueFactory returned null CompletableFuture", thrown.getCause().getMessage());
        }

        @Test
        void publishesFailedFutureCause() throws Exception {
            IllegalStateException failure = new IllegalStateException("failed");

            CompletableFuture<String> future = cache.getOrAddAsync(
                    "key",
                    () -> CompletableFuture.failedFuture(failure));

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertSame(failure, thrown.getCause());
        }

        @Test
        void publishesThrownError() throws Exception {
            Error failure = new Error("boom");

            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
                throw failure;
            });

            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertSame(failure, thrown.getCause());
            assertEquals(0, cache.getPendingTasksCount());
        }
    }

    @Nested
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    class SharesOneInFlightCall {

        @Test
        void reusesInFlightCallOnTheSameThread() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> gate = new CompletableFuture<>();

            CompletableFuture<String> first = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return gate;
            });
            CompletableFuture<String> second = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("other");
            });

            assertSame(first, second);
            assertEquals(1, calls.get());
            gate.complete("first");
            assertEquals("first", await(first));
            assertEquals("first", await(second));
        }

        @Test
        void sharesOneSuccessfulCallAcrossThreads() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> gate = new CompletableFuture<>();

            List<CompletableFuture<String>> futures = sharedInFlight("hot", calls, () -> gate);

            gate.complete("value");
            for (CompletableFuture<String> future : futures) {
                assertEquals("value", await(future));
            }
        }

        @Test
        void sharesOneThrownFailureAcrossThreads() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            RuntimeException failure = new RuntimeException("boom");

            List<CompletableFuture<String>> futures = sharedInFlight("hot", calls, () -> {
                throw failure;
            });

            for (CompletableFuture<String> future : futures) {
                ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
                assertSame(failure, thrown.getCause());
            }
        }

        @Test
        void reentrantCallSharesTheInFlightFuture() throws Exception {
            AtomicInteger nestedCalls = new AtomicInteger();
            AtomicReference<CompletableFuture<String>> nested = new AtomicReference<>();
            CompletableFuture<String> gate = new CompletableFuture<>();

            CompletableFuture<String> outer = cache.getOrAddAsync("key", () -> {
                nested.set(cache.getOrAddAsync("key", () -> {
                    nestedCalls.incrementAndGet();
                    return CompletableFuture.completedFuture("nested");
                }));
                return gate;
            });

            assertSame(outer, nested.get());
            assertEquals(0, nestedCalls.get());
            gate.complete("outer");
            assertEquals("outer", await(outer));
        }

        @Test
        void runsSeparateFactoriesForDifferentKeys() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> firstGate = new CompletableFuture<>();
            CompletableFuture<String> secondGate = new CompletableFuture<>();

            CompletableFuture<String> first = cache.getOrAddAsync("a", () -> {
                calls.incrementAndGet();
                return firstGate;
            });
            CompletableFuture<String> second = cache.getOrAddAsync("b", () -> {
                calls.incrementAndGet();
                return secondGate;
            });

            assertEquals(2, calls.get());
            firstGate.complete("A");
            secondGate.complete("B");
            assertEquals("A", await(first));
            assertEquals("B", await(second));
        }

        @Test
        void aBlockedKeyDoesNotStallAnotherKey() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                pool.submit(() -> cache.getOrAddAsync("hot", () -> {
                    entered.countDown();
                    waitForRelease(release);
                    return CompletableFuture.completedFuture("hot");
                }));
                await(entered);

                String other = await(cache.getOrAddAsync(
                        "other",
                        () -> CompletableFuture.completedFuture("other")));

                assertEquals("other", other);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        void surroundingSpaceDoesNotShareTheCall() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> raw = new CompletableFuture<>();
            CompletableFuture<String> padded = new CompletableFuture<>();

            CompletableFuture<String> first = cache.getOrAddAsync("k", () -> {
                calls.incrementAndGet();
                return raw;
            });
            CompletableFuture<String> second = cache.getOrAddAsync(" k ", () -> {
                calls.incrementAndGet();
                return padded;
            });

            assertNotSame(first, second);
            assertEquals(2, calls.get());
            assertEquals(2, cache.getPendingTasksCount());
            raw.complete("raw");
            padded.complete("padded");
            assertEquals("raw", await(first));
            assertEquals("padded", await(second));
        }

        @Test
        void sharesOneAsynchronousFailure() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> gate = new CompletableFuture<>();
            IllegalStateException failure = new IllegalStateException("later");

            CompletableFuture<String> first = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return gate;
            });
            CompletableFuture<String> second = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("other");
            });

            assertSame(first, second);
            assertEquals(1, calls.get());
            assertEquals(1, cache.getPendingTasksCount());
            gate.completeExceptionally(failure);
            assertEquals(0, cache.getPendingTasksCount());
            ExecutionException firstThrown = assertThrows(ExecutionException.class, () -> await(first));
            ExecutionException secondThrown = assertThrows(ExecutionException.class, () -> await(second));
            assertSame(failure, firstThrown.getCause());
            assertSame(failure, secondThrown.getCause());
        }

        /**
         * The winning factory blocks until {@code release}. The returned latch is
         * started at {@code STAMPEDE - 1} because the winner cannot count down
         * until that release. Waiting for all {@code STAMPEDE} workers first deadlocks.
         */
        private List<CompletableFuture<String>> sharedInFlight(
                String key,
                AtomicInteger calls,
                Supplier<CompletableFuture<String>> factory) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(STAMPEDE - 1);
            List<CompletableFuture<String>> futures = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(STAMPEDE);
            List<Future<?>> workers = new ArrayList<>();
            try {
                for (int i = 0; i < STAMPEDE; i++) {
                    workers.add(pool.submit(() -> {
                        CompletableFuture<String> future = cache.getOrAddAsync(key, () -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            waitForRelease(release);
                            return factory.get();
                        });
                        futures.add(future);
                        returned.countDown();
                        return null;
                    }));
                }
                await(entered);
                await(returned);
                assertOneSharedFuture(calls, futures, STAMPEDE - 1);
                release.countDown();
                for (Future<?> worker : workers) {
                    worker.get(1, TimeUnit.SECONDS);
                }
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
            assertOneSharedFuture(calls, futures, STAMPEDE);
            return new ArrayList<>(futures);
        }
    }

    @Nested
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    class TracksPendingCalls {

        @Test
        void countsOneCallUntilItCompletes() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();

            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> gate);

            assertEquals(1, cache.getPendingTasksCount());
            gate.complete("value");
            assertEquals("value", await(future));
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void countsEachInFlightKey() {
            CompletableFuture<String> first = new CompletableFuture<>();
            CompletableFuture<String> second = new CompletableFuture<>();

            cache.getOrAddAsync("a", () -> first);
            cache.getOrAddAsync("b", () -> second);

            assertEquals(2, cache.getPendingTasksCount());
            first.complete("A");
            assertEquals(1, cache.getPendingTasksCount());
            second.complete("B");
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void tracksALargeSetOfInFlightKeys() {
            List<CompletableFuture<String>> gates = new ArrayList<>();
            for (int i = 0; i < LARGE_SET; i++) {
                CompletableFuture<String> gate = new CompletableFuture<>();
                gates.add(gate);
                cache.getOrAddAsync("key-" + i, () -> gate);
            }

            assertEquals(LARGE_SET, cache.getPendingTasksCount());
            for (CompletableFuture<String> gate : gates) {
                gate.complete("done");
            }
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void removesKeyAfterThrownFailure() throws Exception {
            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> {
                throw new IllegalStateException("fail");
            });

            assertThrows(ExecutionException.class, () -> await(future));
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void keepsKeyUntilAsynchronousFailureCompletes() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();
            IllegalStateException failure = new IllegalStateException("later");

            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> gate);

            assertEquals(1, cache.getPendingTasksCount());
            gate.completeExceptionally(failure);
            assertEquals(0, cache.getPendingTasksCount());
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertSame(failure, thrown.getCause());
        }

        @Test
        void removesEachFailedKey() throws Exception {
            for (int i = 0; i < 3; i++) {
                int id = i;
                CompletableFuture<String> future = cache.getOrAddAsync("key-" + id, () -> {
                    throw new IllegalStateException("fail-" + id);
                });
                assertThrows(ExecutionException.class, () -> await(future));
            }

            assertEquals(0, cache.getPendingTasksCount());
            assertEquals("ok", await(cache.getOrAddAsync("later", () -> CompletableFuture.completedFuture("ok"))));
        }

        @Test
        void runsFactoryAgainAfterSuccess() throws Exception {
            AtomicInteger calls = new AtomicInteger();

            String first = await(cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("one");
            }));
            String second = await(cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("two");
            }));

            assertEquals("one", first);
            assertEquals("two", second);
            assertEquals(2, calls.get());
        }

        @Test
        void runsFactoryAgainFromACompletionCallback() {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<CompletableFuture<String>> second = new AtomicReference<>();
            CompletableFuture<String> gate = new CompletableFuture<>();

            CompletableFuture<String> first = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return gate;
            });
            first.thenRun(() -> second.set(cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("again");
            })));

            gate.complete("first");

            assertEquals(2, calls.get());
            assertNotSame(first, second.get());
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void runsFactoryAgainAfterThrownFailure() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            IllegalStateException failure = new IllegalStateException("fail");

            CompletableFuture<String> first = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                throw failure;
            });
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(first));
            assertSame(failure, thrown.getCause());

            String second = await(cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("ok");
            }));

            assertEquals("ok", second);
            assertEquals(2, calls.get());
        }

        @Test
        void clearRemovesPendingCalls() {
            cache.getOrAddAsync("key", CompletableFuture::new);

            assertEquals(1, cache.getPendingTasksCount());
            cache.clear();
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void clearDropsEveryInFlightEntry() {
            cache.getOrAddAsync("a", CompletableFuture::new);
            cache.getOrAddAsync("b", CompletableFuture::new);

            assertEquals(2, cache.getPendingTasksCount());
            cache.clear();
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void clearLeavesInFlightResultIntact() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> gate);

            cache.clear();
            gate.complete("value");

            assertEquals("value", await(future));
        }

        @Test
        void finishedClearedCallDoesNotRemoveReplacement() throws Exception {
            CompletableFuture<String> firstGate = new CompletableFuture<>();
            CompletableFuture<String> first = cache.getOrAddAsync("k", () -> firstGate);
            cache.clear();
            CompletableFuture<String> secondGate = new CompletableFuture<>();
            CompletableFuture<String> second = cache.getOrAddAsync("k", () -> secondGate);

            firstGate.complete("old");

            assertEquals(1, cache.getPendingTasksCount());
            assertEquals("old", await(first));
            secondGate.complete("new");
            assertEquals(0, cache.getPendingTasksCount());
            assertEquals("new", await(second));
        }

        @Test
        void completedFactoryLeavesNoPendingEntry() throws Exception {
            CompletableFuture<String> future = cache.getOrAddAsync(
                    "key", () -> CompletableFuture.completedFuture("value"));

            assertEquals(0, cache.getPendingTasksCount());
            assertEquals("value", await(future));
        }

        @Test
        void failedFutureLeavesNoPendingEntry() throws Exception {
            IllegalStateException failure = new IllegalStateException("failed");

            CompletableFuture<String> future = cache.getOrAddAsync(
                    "key", () -> CompletableFuture.failedFuture(failure));

            assertEquals(0, cache.getPendingTasksCount());
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertSame(failure, thrown.getCause());
        }

        @Test
        void nullFutureLeavesNoPendingEntry() throws Exception {
            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> null);

            assertEquals(0, cache.getPendingTasksCount());
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> await(future));
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertEquals("ValueFactory returned null CompletableFuture", thrown.getCause().getMessage());
        }

        @Test
        void runsFactoryAgainFromAFailureCallback() {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<CompletableFuture<String>> second = new AtomicReference<>();
            CompletableFuture<String> gate = new CompletableFuture<>();
            IllegalStateException failure = new IllegalStateException("fail");

            CompletableFuture<String> first = cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return gate;
            });
            first.whenComplete((value, error) -> second.set(cache.getOrAddAsync("key", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("again");
            })));

            gate.completeExceptionally(failure);

            assertEquals(2, calls.get());
            assertNotSame(first, second.get());
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void clearOnEmptyCallerLeavesCountAtZero() {
            assertEquals(0, cache.getPendingTasksCount());
            cache.clear();
            assertEquals(0, cache.getPendingTasksCount());
        }

        @Test
        void clearThenCompletionDoesNotRestoreTheKey() throws Exception {
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> future = cache.getOrAddAsync("key", () -> gate);

            cache.clear();
            gate.complete("value");

            assertEquals("value", await(future));
            assertEquals(0, cache.getPendingTasksCount());
        }
    }

    private static void waitForRelease(CountDownLatch release) {
        try {
            if (!release.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("factory wait timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("factory wait interrupted", interrupted);
        }
    }

    private static void assertOneSharedFuture(
            AtomicInteger calls,
            List<CompletableFuture<String>> futures,
            int expectedSize) {
        List<CompletableFuture<String>> snapshot;
        synchronized (futures) {
            snapshot = new ArrayList<>(futures);
        }
        assertEquals(1, calls.get());
        assertEquals(expectedSize, snapshot.size());
        CompletableFuture<String> shared = snapshot.get(0);
        for (CompletableFuture<String> future : snapshot) {
            assertSame(shared, future);
        }
    }
}
