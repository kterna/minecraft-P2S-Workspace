package com.p2s.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative cancellation shared by the loop, the transport and tools (pi: AbortSignal).
 * Registration and abort are mutually atomic: a callback is either recorded before abort
 * runs the callbacks, or it runs immediately on the registering thread. Never both, never
 * neither.
 */
public final class AbortSignal {
    private static final AbortSignal NONE = new AbortSignal(true);

    private final List<Runnable> callbacks = new ArrayList<>();
    private final boolean inert;
    private boolean aborted;
    private String reason = "";

    private AbortSignal(boolean inert) {
        this.inert = inert;
    }

    public static AbortSignal create() {
        return new AbortSignal(false);
    }

    /** Shared signal that is never aborted (abort() on it has no effect). */
    public static AbortSignal none() {
        return NONE;
    }

    public synchronized boolean isAborted() {
        return aborted;
    }

    public synchronized String reason() {
        return reason;
    }

    /** Returns true only for the call that performed the transition. */
    public boolean abort(String why) {
        if (inert) {
            return false;
        }
        List<Runnable> toRun;
        synchronized (this) {
            if (aborted) {
                return false;
            }
            aborted = true;
            reason = why == null ? "" : why;
            toRun = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        for (Runnable callback : toRun) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // One failing listener must not prevent the others from cancelling.
            }
        }
        return true;
    }

    /** Runs the callback on abort; unregister it when the guarded operation completes. */
    public Registration onAbort(Runnable callback) {
        if (callback == null || inert) {
            return () -> { };
        }
        synchronized (this) {
            if (!aborted) {
                callbacks.add(callback);
                return () -> {
                    synchronized (AbortSignal.this) {
                        callbacks.remove(callback);
                    }
                };
            }
        }
        callback.run();
        return () -> { };
    }

    synchronized int registeredCallbackCount() {
        return callbacks.size();
    }

    public void throwIfAborted() throws AbortedException {
        if (isAborted()) {
            throw new AbortedException(reason());
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public static final class AbortedException extends Exception {
        public AbortedException(String reason) {
            super(reason == null || reason.isBlank() ? "Operation aborted" : reason);
        }
    }
}
