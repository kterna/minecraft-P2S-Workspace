package com.p2s.agent.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbortSignalTest {
    @Test
    void callbackRegisteredAfterAbortRunsImmediately() {
        AbortSignal signal = AbortSignal.create();
        assertTrue(signal.abort("stop"));
        assertFalse(signal.abort("again"));
        AtomicInteger runs = new AtomicInteger();
        signal.onAbort(runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals("stop", signal.reason());
    }

    @Test
    void closedRegistrationIsNotCalled() {
        AbortSignal signal = AbortSignal.create();
        AtomicInteger runs = new AtomicInteger();
        signal.onAbort(runs::incrementAndGet).close();
        signal.abort("stop");
        assertEquals(0, runs.get());
        assertEquals(0, signal.registeredCallbackCount());
    }

    /** Regression for the proposal's token: registration racing abort must never lose a callback. */
    @Test
    void everyCallbackRunsExactlyOnceUnderRacingRegistrationAndAbort() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 200; round++) {
                AbortSignal signal = AbortSignal.create();
                AtomicInteger runs = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(8);
                for (int i = 0; i < 7; i++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            signal.onAbort(runs::incrementAndGet);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                pool.execute(() -> {
                    try {
                        start.await();
                        signal.abort("race");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                start.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(7, runs.get(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failingCallbackDoesNotBlockOthers() {
        AbortSignal signal = AbortSignal.create();
        AtomicInteger runs = new AtomicInteger();
        signal.onAbort(() -> {
            throw new IllegalStateException("boom");
        });
        signal.onAbort(runs::incrementAndGet);
        signal.abort("stop");
        assertEquals(1, runs.get());
    }
}
