package dev.lufi.application;

import java.util.Comparator;
import java.util.List;

/** Política de prioridade: janela de playback, depois menor latência e maior disponibilidade. */
public final class PieceScheduler {
    public record Peer(String id, double throughputBytesPerSecond, int latencyMs, int availablePieces) { }
    public List<Peer> rank(List<Peer> peers) {
        return peers.stream().sorted(Comparator.comparingDouble(Peer::throughputBytesPerSecond).reversed()
                .thenComparingInt(Peer::latencyMs).thenComparing(Comparator.comparingInt(Peer::availablePieces).reversed())).toList();
    }
}

