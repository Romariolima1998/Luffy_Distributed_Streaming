package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmAssistDhtSchedulerTest {
    @Test void spacesDifferentAssistLookupsAndCoalescesTheSameSwarm() throws Exception {
        try (SwarmAssistDhtScheduler scheduler = new SwarmAssistDhtScheduler(Duration.ofMillis(80))) {
            var firstCompletion = new CompletableFuture<Integer>();
            var first = scheduler.schedule("a", () -> firstCompletion);
            var repeated = scheduler.schedule("a", () -> CompletableFuture.completedFuture(99));

            assertTrue(repeated.coalesced());
            assertSame(first.completion(), repeated.completion());
            firstCompletion.complete(1);
            assertEquals(1, first.completion().get(1, TimeUnit.SECONDS));

            var startedAt = new CopyOnWriteArrayList<Long>();
            var next = scheduler.schedule("b", () -> {
                startedAt.add(System.nanoTime());
                return CompletableFuture.completedFuture(2);
            });
            var last = scheduler.schedule("c", () -> {
                startedAt.add(System.nanoTime());
                return CompletableFuture.completedFuture(3);
            });
            next.completion().get(1, TimeUnit.SECONDS);
            last.completion().get(1, TimeUnit.SECONDS);

            assertEquals(2, startedAt.size());
            long gapMillis = TimeUnit.NANOSECONDS.toMillis(startedAt.get(1) - startedAt.get(0));
            assertTrue(gapMillis >= 60, "as consultas Assist devem ser espaçadas, não disparadas em rajada");
        }
    }
}
