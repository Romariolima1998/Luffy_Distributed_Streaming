package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Seleção pura do peer C: só peers já conectados ao mesmo swarm podem fazer rendezvous. */
final class RendezvousPeerSelector {
    private RendezvousPeerSelector() { }

    static Optional<Candidate> select(String infoHash, InetAddress targetAddress, int targetPort,
                                      Collection<Candidate> candidates) {
        return selectAll(infoHash, targetAddress, targetPort, candidates).stream().findFirst();
    }

    /** Mantém a ordem determinística e permite ao agente tentar outro C após NOT_CONNECTED. */
    static java.util.List<Candidate> selectAll(String infoHash, InetAddress targetAddress, int targetPort,
                                               Collection<Candidate> candidates) {
        if (infoHash == null || targetAddress == null || candidates == null) return java.util.List.of();
        return candidates.stream()
                .filter(Candidate::supportsBep55)
                .filter(candidate -> candidate.infoHash().equalsIgnoreCase(infoHash))
                .filter(candidate -> !candidate.matchesTarget(targetAddress, targetPort))
                .sorted(Comparator.comparing(Candidate::connectionId)).toList();
    }

    record Candidate(String connectionId, String infoHash, InetAddress address, int declaredTcpPort,
                     int observedTcpPort, boolean supportsBep55) {
        Candidate {
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(infoHash, "infoHash");
            Objects.requireNonNull(address, "address");
            if (declaredTcpPort < 1 || declaredTcpPort > 65_535) throw new IllegalArgumentException("declaredTcpPort");
            if (observedTcpPort < 0 || observedTcpPort > 65_535) throw new IllegalArgumentException("observedTcpPort");
        }

        boolean matchesTarget(InetAddress targetAddress, int targetPort) {
            return address.equals(targetAddress)
                    && (targetPort == declaredTcpPort || observedTcpPort > 0 && targetPort == observedTcpPort);
        }
    }
}
