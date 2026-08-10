package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.runtime.BtRuntime;
import bt.runtime.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DhtLookupRuntimeLifecycleTest {
    @Test void readinessBarrierCompletesOnlyAfterTheDhtStartupFinishes() throws Exception {
        CountDownLatch enteredInitialization = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                DhtLookupRuntimeLifecycleTest::runtime,
                ignored -> {
                    enteredInitialization.countDown();
                    await(releaseInitialization);
                });
        try {
            CompletableFuture<Void> dhtReady = lifecycle.dhtReady();
            assertTrue(enteredInitialization.await(2, TimeUnit.SECONDS));
            assertEquals(DhtLookupState.STARTING, lifecycle.state());
            assertFalse(dhtReady.isDone(), "a barreira nao pode liberar getPeers durante startup");

            releaseInitialization.countDown();
            dhtReady.get(2, TimeUnit.SECONDS);
            assertEquals(DhtLookupState.READY, lifecycle.state());
            assertTrue(lifecycle.isReady());
        } finally {
            releaseInitialization.countDown();
            lifecycle.stop();
        }
    }

    @Test void magnetBootstrapAnnounceAndSwarmAssistShareOneReadyIpv4Runtime() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger initialized = new AtomicInteger();
        CountDownLatch enteredInitialization = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                () -> { created.incrementAndGet(); return runtime(); },
                ignored -> {
                    initialized.incrementAndGet();
                    enteredInitialization.countDown();
                    await(releaseInitialization);
                });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            Map<String, Future<BtRuntime>> lookups = new LinkedHashMap<>();
            lookups.put("magnet", executor.submit(lifecycle::getOrStart));
            assertTrue(enteredInitialization.await(2, TimeUnit.SECONDS));
            for (String purpose : List.of("ola-luffy", "verificacao-announce", "swarm-assist",
                    "magnet-adicional", "ola-luffy-renovacao", "swarm-assist-renovacao", "outra-busca-dht")) {
                lookups.put(purpose, executor.submit(lifecycle::getOrStart));
            }

            assertEquals(DhtLookupState.STARTING, lifecycle.state());
            assertEquals(1, created.get());
            assertEquals(1, initialized.get());

            releaseInitialization.countDown();
            BtRuntime ready = lookups.get("magnet").get(2, TimeUnit.SECONDS);
            for (Map.Entry<String, Future<BtRuntime>> lookup : lookups.entrySet()) {
                assertSame(ready, lookup.getValue().get(2, TimeUnit.SECONDS),
                        "a finalidade " + lookup.getKey() + " deve receber a runtime IPv4 ja pronta");
            }
            assertEquals(DhtLookupState.READY, lifecycle.state());
            assertEquals(1, created.get());
            assertEquals(1, initialized.get());
        } finally {
            releaseInitialization.countDown();
            executor.shutdownNow();
            lifecycle.stop();
        }
    }

    @Test void tenConcurrentLookupRequestsUseOneStartupAndDoNotRunLookupWorkBeforeReady() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger initialized = new AtomicInteger();
        AtomicInteger lookupWork = new AtomicInteger();
        CountDownLatch enteredInitialization = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        CountDownLatch requestsStarted = new CountDownLatch(10);
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                () -> { created.incrementAndGet(); return runtime(); },
                ignored -> {
                    initialized.incrementAndGet();
                    enteredInitialization.countDown();
                    await(releaseInitialization);
                });
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<BtRuntime>> lookups = IntStream.range(0, 10)
                    .mapToObj(ignored -> executor.submit(() -> {
                        requestsStarted.countDown();
                        BtRuntime active = lifecycle.getOrStart();
                        lookupWork.incrementAndGet(); // representa a chamada getPeers() do gateway
                        return active;
                    }))
                    .toList();

            assertTrue(requestsStarted.await(2, TimeUnit.SECONDS));
            assertTrue(enteredInitialization.await(2, TimeUnit.SECONDS));
            assertEquals(DhtLookupState.STARTING, lifecycle.state());
            assertEquals(1, created.get(), "dez consultas nao podem criar dez runtimes");
            assertEquals(1, initialized.get(), "dez consultas nao podem executar dez startups");
            assertEquals(0, lookupWork.get(), "getPeers nao pode iniciar antes de READY");

            releaseInitialization.countDown();
            BtRuntime ready = lookups.getFirst().get(2, TimeUnit.SECONDS);
            for (Future<BtRuntime> lookup : lookups) assertSame(ready, lookup.get(2, TimeUnit.SECONDS));
            assertEquals(10, lookupWork.get(), "as dez consultas devem prosseguir depois de READY");
            assertEquals(1, created.get());
            assertEquals(1, initialized.get());
            assertEquals(DhtLookupState.READY, lifecycle.state());
        } finally {
            releaseInitialization.countDown();
            executor.shutdownNow();
            lifecycle.stop();
        }
    }

    @Test void failedStartupIsVisibleAndTheNextLookupCanRetry() {
        AtomicInteger attempts = new AtomicInteger();
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                DhtLookupRuntimeLifecycleTest::runtime,
                ignored -> {
                    if (attempts.getAndIncrement() == 0) throw new IllegalStateException("startup failure");
                }, () -> Duration.ZERO);
        try {
            assertThrows(IllegalStateException.class, lifecycle::getOrStart);
            assertEquals(DhtLookupState.FAILED, lifecycle.state());

            lifecycle.getOrStart();
            assertEquals(DhtLookupState.READY, lifecycle.state());
            assertEquals(2, attempts.get());
        } finally {
            lifecycle.stop();
        }
    }

    @Test void stoppingPreventsAnyFurtherStartup() {
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                DhtLookupRuntimeLifecycleTest::runtime, ignored -> { });
        lifecycle.getOrStart();
        lifecycle.stop();

        assertEquals(DhtLookupState.STOPPED, lifecycle.state());
        assertThrows(IllegalStateException.class, lifecycle::getOrStart);
    }

    @Test void stoppingCancelsTheReadinessBarrierAndWaitsForRuntimeShutdown() throws Exception {
        CountDownLatch enteredInitialization = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                DhtLookupRuntimeLifecycleTest::runtime,
                ignored -> {
                    enteredInitialization.countDown();
                    await(releaseInitialization);
                }, ignored -> closed.incrementAndGet());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> dhtReady = lifecycle.dhtReady();
            assertTrue(enteredInitialization.await(2, TimeUnit.SECONDS));
            Future<?> stopping = executor.submit(lifecycle::stop);

            assertThrows(ExecutionException.class, () -> dhtReady.get(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, lifecycle::getOrStart,
                    "consulta recebida durante STOPPING deve ser rejeitada antes de chegar ao lookup");
            assertFalse(stopping.isDone(), "stop precisa aguardar o shutdown da runtime em STARTING");

            releaseInitialization.countDown();
            stopping.get(2, TimeUnit.SECONDS);
            assertEquals(DhtLookupState.STOPPED, lifecycle.state());
            assertEquals(1, closed.get());
            assertThrows(IllegalStateException.class, lifecycle::getOrStart);
        } finally {
            releaseInitialization.countDown();
            executor.shutdownNow();
            lifecycle.stop();
        }
    }

    @Test void failedStartupNeverRunsLookupWorkAndRecoversAfterBackoff() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger lookupWork = new AtomicInteger();
        Instant initial = Instant.parse("2026-08-10T12:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(initial);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        DhtLookupRuntimeLifecycle lifecycle = new DhtLookupRuntimeLifecycle(
                () -> { created.incrementAndGet(); return runtime(); },
                ignored -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("RPC server did not become ready");
                    }
                },
                ignored -> closed.incrementAndGet(), () -> Duration.ofSeconds(5), clock);
        try {
            assertThrows(IllegalStateException.class, () -> {
                lifecycle.getOrStart();
                lookupWork.incrementAndGet();
            });
            assertEquals(DhtLookupState.FAILED, lifecycle.state());
            assertEquals(initial.plusSeconds(5), lifecycle.nextRetryAt());
            assertEquals(1, created.get());
            assertEquals(1, closed.get());
            assertEquals(0, lookupWork.get(), "getPeers nao pode ser chamado depois de startup falho");

            assertThrows(IllegalStateException.class, lifecycle::getOrStart);
            assertEquals(1, created.get(), "a falha nao pode recriar runtimes durante backoff");

            now.set(initial.plusSeconds(5));
            lifecycle.getOrStart();
            lookupWork.incrementAndGet();
            assertEquals(DhtLookupState.READY, lifecycle.state());
            assertEquals(2, created.get());
            assertEquals(1, closed.get(), "a runtime recuperada deve permanecer disponivel para lookup");
            assertEquals(1, lookupWork.get(), "o lookup deve voltar a funcionar apos a recuperacao");
        } finally {
            lifecycle.stop();
        }
    }

    private static BtRuntime runtime() {
        return BtRuntime.builder(new Config()).disableAutomaticShutdown().build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test startup");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
