package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ExternalEndpointRegistry;
import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.Transport;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Escolhe um coordenador de rendezvous entre conexoes Luffy que ja existem.
 * A classe nao abre conexoes, nao envia BEP 55 e nao transfere dados.
 */
public final class RendezvousCandidateSelector {
    private RendezvousCandidateSelector() { }

    public static Selection select(Request request, Collection<RendezvousCandidate> candidates) {
        return select(request, candidates, candidate -> candidate.controlConnectionActive());
    }

    /** A verificacao de vida e executada no instante da selecao, nao apenas na fotografia do candidato. */
    public static Selection select(Request request, Collection<RendezvousCandidate> candidates,
                                   CandidateLiveness liveness) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(liveness, "liveness");
        if (request.localTargetConnectionActive()) return Selection.direct(request.targetNodeId());

        List<RendezvousCandidate> eligible = candidates.stream()
                .filter(candidate -> candidate.targetNodeId().equals(request.targetNodeId()))
                .filter(candidate -> liveness.isStillActive(candidate))
                .filter(candidate -> isEligible(candidate, request.now()))
                .sorted(preference(request.now()))
                .toList();
        return eligible.isEmpty() ? Selection.unavailable() : Selection.via(eligible.getFirst());
    }

    private static boolean isEligible(RendezvousCandidate candidate, Instant now) {
        if (!candidate.controlConnectionActive() || !candidate.targetConnectionActive()) return false;
        if (candidate.inBackoff() || candidate.overloaded() || candidate.blocked()) return false;
        if (!candidate.capabilities().supportsDistributedRendezvous()) return false;
        return candidate.udpEndpoint().filter(endpoint -> usableUdpEndpoint(endpoint, now)).isPresent();
    }

    private static boolean usableUdpEndpoint(ObservedEndpoint endpoint, Instant now) {
        return endpoint.transport() == Transport.UTP
                && endpoint.confirmed()
                && !endpoint.isExpired(now)
                && ExternalEndpointRegistry.isPublicAddress(endpoint.address());
    }

    private static Comparator<RendezvousCandidate> preference(Instant now) {
        return Comparator.comparing(RendezvousCandidate::server) // servidores sao somente fallback
                .thenComparing(RendezvousCandidate::targetConnectionInContentSwarm, Comparator.reverseOrder())
                .thenComparingInt(RendezvousCandidate::routeDistance)
                .thenComparingInt(RendezvousCandidate::activeSessions)
                .thenComparing(candidate -> stability(candidate, now), Comparator.reverseOrder())
                .thenComparing(RendezvousCandidate::estimatedLatency)
                .thenComparing(candidate -> candidate.rendezvousNodeId().asText());
    }

    private static Duration stability(RendezvousCandidate candidate, Instant now) {
        return candidate.stableSince().isAfter(now) ? Duration.ZERO : Duration.between(candidate.stableSince(), now);
    }

    public record Request(LuffyNodeId targetNodeId, boolean localTargetConnectionActive, Instant now) {
        public Request {
            Objects.requireNonNull(targetNodeId, "targetNodeId");
            Objects.requireNonNull(now, "now");
        }
    }

    public record Selection(boolean directTargetConnection, Optional<RendezvousCandidate> candidate) {
        public Selection {
            candidate = candidate == null ? Optional.empty() : candidate;
            if (directTargetConnection && candidate.isPresent()) {
                throw new IllegalArgumentException("conexao direta nao utiliza candidato de rendezvous");
            }
        }

        public static Selection direct(LuffyNodeId targetNodeId) {
            Objects.requireNonNull(targetNodeId, "targetNodeId");
            return new Selection(true, Optional.empty());
        }

        public static Selection via(RendezvousCandidate candidate) {
            return new Selection(false, Optional.of(Objects.requireNonNull(candidate, "candidate")));
        }

        public static Selection unavailable() { return new Selection(false, Optional.empty()); }
    }

    @FunctionalInterface
    public interface CandidateLiveness {
        boolean isStillActive(RendezvousCandidate candidate);
    }
}
