package dev.lufi.infrastructure;

import bt.runtime.BtRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns exactly one lookup runtime for one IP family. Concurrent callers share
 * the same startup operation and receive a runtime only after it is ready.
 */
final class DhtLookupRuntimeLifecycle {
    @FunctionalInterface
    interface RuntimeInitializer {
        void initialize(BtRuntime runtime);
    }

    private final Object monitor = new Object();
    private final Supplier<BtRuntime> runtimeFactory;
    private final RuntimeInitializer initializer;
    private final Consumer<BtRuntime> shutdown;
    private final Supplier<Duration> retryBackoff;
    private final Clock clock;
    private DhtLookupState state = DhtLookupState.NEW;
    private BtRuntime runtime;
    private RuntimeException lastFailure;
    private CompletableFuture<Void> dhtReady = new CompletableFuture<>();
    private Instant nextRetryAt = Instant.MIN;

    DhtLookupRuntimeLifecycle(Supplier<BtRuntime> runtimeFactory, RuntimeInitializer initializer) {
        this(runtimeFactory, initializer, BtRuntime::shutdown,
                () -> DhtLookupRuntimeSettings.DEFAULT_DHT_RETRY_BACKOFF, Clock.systemUTC());
    }

    DhtLookupRuntimeLifecycle(Supplier<BtRuntime> runtimeFactory, RuntimeInitializer initializer, Consumer<BtRuntime> shutdown) {
        this(runtimeFactory, initializer, shutdown,
                () -> DhtLookupRuntimeSettings.DEFAULT_DHT_RETRY_BACKOFF, Clock.systemUTC());
    }

    DhtLookupRuntimeLifecycle(Supplier<BtRuntime> runtimeFactory, RuntimeInitializer initializer,
                              Supplier<Duration> retryBackoff) {
        this(runtimeFactory, initializer, BtRuntime::shutdown, retryBackoff, Clock.systemUTC());
    }

    DhtLookupRuntimeLifecycle(Supplier<BtRuntime> runtimeFactory, RuntimeInitializer initializer, Consumer<BtRuntime> shutdown,
                              Supplier<Duration> retryBackoff, Clock clock) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.initializer = Objects.requireNonNull(initializer, "initializer");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    BtRuntime getOrStart() {
        return awaitDhtReady();
    }

    /** Starts the lifecycle when needed and returns the shared readiness barrier. */
    CompletableFuture<Void> dhtReady() {
        BtRuntime created;
        CompletableFuture<Void> readiness;
        synchronized (monitor) {
            if (state == DhtLookupState.READY || state == DhtLookupState.STARTING) return dhtReady;
            if (state == DhtLookupState.STOPPING) return CompletableFuture.failedFuture(
                    new IllegalStateException("DHT lookup runtime is stopping"));
            if (state == DhtLookupState.STOPPED) {
                return CompletableFuture.failedFuture(new IllegalStateException("DHT lookup runtime is stopped"));
            }
            if (state == DhtLookupState.FAILED && clock.instant().isBefore(nextRetryAt)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "DHT lookup runtime retry is in backoff until " + nextRetryAt));
            }

            state = DhtLookupState.STARTING;
            lastFailure = null;
            dhtReady = new CompletableFuture<>();
            readiness = dhtReady;
            try {
                created = Objects.requireNonNull(runtimeFactory.get(), "runtimeFactory returned null");
                runtime = created;
            } catch (RuntimeException error) {
                transitionToFailed(error, readiness);
                return readiness;
            }
        }
        Thread.startVirtualThread(() -> initialize(created, readiness));
        return readiness;
    }

    /** All callers must cross dhtReady before they can receive the runtime. */
    BtRuntime awaitDhtReady() {
        CompletableFuture<Void> readiness = dhtReady();
        try {
            readiness.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) throw runtimeError;
            throw new IllegalStateException("DHT lookup runtime failed to start", cause);
        }
        synchronized (monitor) {
            if (state == DhtLookupState.READY && runtime != null) return runtime;
            if (state == DhtLookupState.FAILED) throw startupFailure();
            throw new IllegalStateException("DHT lookup runtime is not ready: " + state);
        }
    }

    DhtLookupState state() {
        synchronized (monitor) {
            return state;
        }
    }

    boolean isReady() {
        synchronized (monitor) {
            return state == DhtLookupState.READY;
        }
    }

    void stop() {
        BtRuntime current;
        synchronized (monitor) {
            if (state == DhtLookupState.STOPPED) return;
            if (state == DhtLookupState.STARTING) {
                state = DhtLookupState.STOPPING;
                dhtReady.completeExceptionally(new IllegalStateException("DHT lookup runtime is stopping"));
                while (state == DhtLookupState.STOPPING) awaitTransition();
                return;
            }
            state = DhtLookupState.STOPPING;
            current = runtime;
            runtime = null;
        }
        try {
            if (current != null) shutdown.accept(current);
        } finally {
            synchronized (monitor) {
                state = DhtLookupState.STOPPED;
                nextRetryAt = Instant.MAX;
                monitor.notifyAll();
            }
        }
    }

    private void initialize(BtRuntime created, CompletableFuture<Void> readiness) {
        try {
            initializer.initialize(created);
        } catch (RuntimeException error) {
            failStartup(created, readiness, error);
            return;
        }

        synchronized (monitor) {
            if (state != DhtLookupState.STOPPING) {
                state = DhtLookupState.READY;
                readiness.complete(null);
                monitor.notifyAll();
                return;
            }
        }
        try {
            shutdown.accept(created);
        } finally {
            synchronized (monitor) {
                runtime = null;
                state = DhtLookupState.STOPPED;
                nextRetryAt = Instant.MAX;
                readiness.completeExceptionally(new IllegalStateException("DHT lookup runtime was stopped while it was starting"));
                monitor.notifyAll();
            }
        }
    }

    private void failStartup(BtRuntime created, CompletableFuture<Void> readiness, RuntimeException error) {
        try {
            shutdown.accept(created);
        } finally {
            synchronized (monitor) {
                runtime = null;
                if (state == DhtLookupState.STOPPING) {
                    state = DhtLookupState.STOPPED;
                    nextRetryAt = Instant.MAX;
                    readiness.completeExceptionally(new IllegalStateException("DHT lookup runtime was stopped while it was starting", error));
                } else transitionToFailed(error, readiness);
                monitor.notifyAll();
            }
        }
    }

    private void transitionToFailed(RuntimeException error, CompletableFuture<Void> readiness) {
        state = DhtLookupState.FAILED;
        lastFailure = error;
        nextRetryAt = clock.instant().plus(validRetryBackoff());
        readiness.completeExceptionally(error);
        monitor.notifyAll();
    }

    private RuntimeException startupFailure() {
        return new IllegalStateException("DHT lookup runtime failed to start", lastFailure);
    }

    Instant nextRetryAt() {
        synchronized (monitor) {
            return nextRetryAt;
        }
    }

    private Duration validRetryBackoff() {
        Duration value = retryBackoff.get();
        if (value == null || value.isNegative()) {
            throw new IllegalStateException("DHT lookup retry backoff is invalid");
        }
        return value;
    }

    private void awaitTransition() {
        try {
            monitor.wait();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the DHT lookup runtime", error);
        }
    }
}
