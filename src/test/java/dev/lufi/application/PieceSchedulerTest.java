package dev.lufi.application;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PieceSchedulerTest {
    @Test void prefersThroughputThenLatencyThenAvailability() {
        var ranked = new PieceScheduler().rank(List.of(new PieceScheduler.Peer("slow", 10, 1, 9), new PieceScheduler.Peer("fast", 20, 80, 1), new PieceScheduler.Peer("fast-low-latency", 20, 5, 1)));
        assertEquals("fast-low-latency", ranked.getFirst().id());
    }
}
