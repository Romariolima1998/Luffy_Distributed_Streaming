package dev.lufi.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serializa e espaça a manutenção DHT dos swarms Assist; consultas do usuário não passam por aqui. */
final class SwarmAssistDhtScheduler implements AutoCloseable {
    static final Duration LOOKUP_SPACING = Duration.ofSeconds(15);
    record ScheduledLookup(CompletableFuture<Integer> completion, Duration delay, boolean coalesced) { }

    private final Duration spacing;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private Instant nextSlot = Instant.EPOCH;

    SwarmAssistDhtScheduler() { this(LOOKUP_SPACING); }
    SwarmAssistDhtScheduler(Duration spacing) {
        this.spacing = spacing == null || spacing.isNegative() ? LOOKUP_SPACING : spacing;
    }

    synchronized ScheduledLookup schedule(String infoHash, Supplier<CompletableFuture<Integer>> lookup) {
        Pending existing = pending.get(infoHash);
        if (existing != null) return new ScheduledLookup(existing.completion(), remaining(existing.executeAt()), true);

        Instant now = Instant.now();
        Instant executeAt = nextSlot.isAfter(now) ? nextSlot : now;
        nextSlot = executeAt.plus(spacing);
        CompletableFuture<Integer> completion = new CompletableFuture<>();
        Pending created = new Pending(executeAt, completion);
        pending.put(infoHash, created);
        long delay = Math.max(0, Duration.between(now, executeAt).toMillis());
        executor.schedule(() -> execute(infoHash, lookup, completion), delay, TimeUnit.MILLISECONDS);
        return new ScheduledLookup(completion, Duration.ofMillis(delay), false);
    }

    private void execute(String infoHash, Supplier<CompletableFuture<Integer>> lookup, CompletableFuture<Integer> completion) {
        Pending current = pending.get(infoHash);
        if (current == null || current.completion() != completion) return;
        try {
            CompletableFuture<Integer> result = lookup.get();
            if (result == null) throw new IllegalStateException("consulta DHT não foi criada");
            result.whenComplete((count, error) -> {
                pending.remove(infoHash, current);
                if (error != null) completion.completeExceptionally(error);
                else completion.complete(count == null ? 0 : count);
            });
        } catch (Exception error) {
            pending.remove(infoHash, current);
            completion.completeExceptionally(error);
        }
    }

    private Duration remaining(Instant executeAt) {
        Duration value = Duration.between(Instant.now(), executeAt);
        return value.isNegative() ? Duration.ZERO : value;
    }

    void cancel(String infoHash) {
        Pending removed = pending.remove(infoHash);
        if (removed != null) removed.completion().completeExceptionally(new IllegalStateException("consulta DHT Assist cancelada"));
    }

    @Override public void close() {
        pending.values().forEach(pending -> {
            pending.completion().completeExceptionally(new IllegalStateException("scheduler DHT encerrado"));
        });
        pending.clear(); executor.shutdownNow();
    }

    private record Pending(Instant executeAt, CompletableFuture<Integer> completion) { }
}
