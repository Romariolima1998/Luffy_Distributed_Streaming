package dev.lufi.infrastructure.security;

import dev.lufi.infrastructure.overlay.LuffyRouteMessage;

/** Limites locais da camada de controle. Eles nunca alteram o protocolo BitTorrent. */
public record AbuseProtectionConfig(
        int maxFindNodeRequestsPerMinute,
        int maxForwardedRequestsPerMinute,
        int maxConcurrentRouteSearches,
        int maxConcurrentRendezvousSessions,
        int maxRendezvousRequestsPerPeer,
        int maxPayloadBytes,
        int maxTtl,
        int maxPendingUtpSessions,
        int maxPendingUtpSessionsPerAddress
) {
    public static final int DEFAULT_MAX_FIND_NODE_REQUESTS_PER_MINUTE = 120;
    public static final int DEFAULT_MAX_FORWARDED_REQUESTS_PER_MINUTE = 240;
    public static final int DEFAULT_MAX_CONCURRENT_ROUTE_SEARCHES = 8;
    public static final int DEFAULT_MAX_CONCURRENT_RENDEZVOUS_SESSIONS = 4;
    public static final int DEFAULT_MAX_RENDEZVOUS_REQUESTS_PER_PEER = 12;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 512;
    public static final int DEFAULT_MAX_PENDING_UTP_SESSIONS = 128;
    public static final int DEFAULT_MAX_PENDING_UTP_SESSIONS_PER_ADDRESS = 8;

    public AbuseProtectionConfig {
        if (maxFindNodeRequestsPerMinute < 1 || maxForwardedRequestsPerMinute < 1
                || maxConcurrentRouteSearches < 1 || maxConcurrentRendezvousSessions < 1
                || maxRendezvousRequestsPerPeer < 1 || maxPayloadBytes < 1
                || maxPendingUtpSessions < 1 || maxPendingUtpSessionsPerAddress < 1) {
            throw new IllegalArgumentException("limites de seguranca devem ser positivos");
        }
        if (maxTtl < 1 || maxTtl > LuffyRouteMessage.MAX_TTL) {
            throw new IllegalArgumentException("maxTtl deve estar entre 1 e " + LuffyRouteMessage.MAX_TTL);
        }
    }

    public static AbuseProtectionConfig defaults() {
        return new AbuseProtectionConfig(DEFAULT_MAX_FIND_NODE_REQUESTS_PER_MINUTE,
                DEFAULT_MAX_FORWARDED_REQUESTS_PER_MINUTE, DEFAULT_MAX_CONCURRENT_ROUTE_SEARCHES,
                DEFAULT_MAX_CONCURRENT_RENDEZVOUS_SESSIONS, DEFAULT_MAX_RENDEZVOUS_REQUESTS_PER_PEER,
                DEFAULT_MAX_PAYLOAD_BYTES, LuffyRouteMessage.MAX_TTL, DEFAULT_MAX_PENDING_UTP_SESSIONS,
                DEFAULT_MAX_PENDING_UTP_SESSIONS_PER_ADDRESS);
    }
}
