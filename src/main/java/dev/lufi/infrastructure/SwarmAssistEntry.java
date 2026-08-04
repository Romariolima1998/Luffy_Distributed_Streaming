package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.time.Instant;
import java.util.Objects;

/**
 * Fotografia de uma vaga da Swarm Assist List. O conteudo nao esta implicito
 * nesta entrada: ela representa apenas uma participacao de controle.
 */
public record SwarmAssistEntry(
        String infoHash,
        MagnetLink magnet,
        SwarmAssistStats stats,
        Instant joinedAt,
        Instant lastUserInteraction,
        Instant lastPeerSeen,
        Instant lastUsefulRendezvous,
        int holePunchRequestsRelayed,
        int successfulHolePunches,
        Instant lastSuccessfulAssist) {

    public SwarmAssistEntry {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("infoHash invalido");
        magnet = Objects.requireNonNull(magnet, "magnet");
        if (!magnet.infoHash().equalsIgnoreCase(infoHash)) throw new IllegalArgumentException("magnet e infoHash divergentes");
        stats = stats == null ? unknownStats(infoHash) : stats;
        joinedAt = joinedAt == null ? Instant.EPOCH : joinedAt;
        holePunchRequestsRelayed = Math.max(0, holePunchRequestsRelayed);
        successfulHolePunches = Math.max(0, successfulHolePunches);
    }

    public static SwarmAssistEntry from(SwarmMembershipRepository.Membership membership, SwarmAssistStats currentStats) {
        Objects.requireNonNull(membership, "membership");
        return new SwarmAssistEntry(membership.infoHash(), MagnetLink.parse(membership.magnet()), currentStats,
                membership.joinedAt(), membership.lastUserInteraction(), membership.lastPeerSeen(),
                membership.lastUsefulRendezvous(), membership.holePunchRequestsRelayed(),
                membership.successfulHolePunches(), membership.lastSuccessfulAssist());
    }

    public Instant lastRelevantActivity() {
        return java.util.stream.Stream.of(joinedAt, lastUserInteraction, lastPeerSeen, lastUsefulRendezvous, lastSuccessfulAssist)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static SwarmAssistStats unknownStats(String infoHash) {
        return new SwarmAssistStats(infoHash, 0, 0, 0, 0, Instant.EPOCH);
    }
}
