package dev.lufi.infrastructure.security;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protecao efemera contra abuso da camada de controle. A chave e somente uma
 * origem de rede ou uma identidade ja validada; nao ha blacklist permanente.
 */
public final class AbuseProtectionService {
    public static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    public static final Duration TEMPORARY_BLOCK = Duration.ofMinutes(5);

    public enum Violation { FLOOD, INVALID_PAYLOAD, IDENTITY_CHANGED, TTL_ABUSE, REPEATED_SESSION, INVALID_ENDPOINT }

    private final Map<String, PeerWindow> peers = new ConcurrentHashMap<>();
    private final AtomicInteger activeRouteSearches = new AtomicInteger();
    private final Map<UUID, Instant> activeRendezvousSessions = new ConcurrentHashMap<>();
    private volatile AbuseProtectionConfig config;

    public AbuseProtectionService() { this(AbuseProtectionConfig.defaults()); }
    public AbuseProtectionService(AbuseProtectionConfig config) { this.config = Objects.requireNonNull(config, "config"); }

    public void setConfig(AbuseProtectionConfig config) { this.config = Objects.requireNonNull(config, "config"); }
    public AbuseProtectionConfig config() { return config; }

    public boolean allowFindNode(String peerKey, Instant now) {
        return consume(peerKey, now, config.maxFindNodeRequestsPerMinute(), RequestClass.FIND_NODE);
    }
    public boolean allowForward(String peerKey, Instant now) {
        return consume(peerKey, now, config.maxForwardedRequestsPerMinute(), RequestClass.FORWARDED);
    }
    public boolean allowRendezvousRequest(String peerKey, Instant now) {
        return consume(peerKey, now, config.maxRendezvousRequestsPerPeer(), RequestClass.RENDEZVOUS);
    }
    public boolean isAllowed(String peerKey, Instant now) {
        PeerWindow peer = peers.get(normalize(peerKey));
        return peer == null || !peer.blockedUntil.isAfter(now);
    }

    public void recordViolation(String peerKey, Violation violation, Instant now) {
        Objects.requireNonNull(violation, "violation");
        PeerWindow peer = peers.computeIfAbsent(normalize(peerKey), ignored -> new PeerWindow());
        synchronized (peer) {
            purge(peer.violations, now);
            peer.violations.addLast(now);
            boolean immediate = violation == Violation.FLOOD || violation == Violation.IDENTITY_CHANGED
                    || violation == Violation.TTL_ABUSE || peer.violations.size() >= 3;
            if (immediate) peer.blockedUntil = now.plus(TEMPORARY_BLOCK);
        }
    }

    public boolean tryAcquireRouteSearch() {
        while (true) {
            int current = activeRouteSearches.get();
            if (current >= config.maxConcurrentRouteSearches()) return false;
            if (activeRouteSearches.compareAndSet(current, current + 1)) return true;
        }
    }
    public void releaseRouteSearch() { activeRouteSearches.updateAndGet(value -> Math.max(0, value - 1)); }
    public int activeRouteSearches() { return activeRouteSearches.get(); }

    public boolean tryAcquireRendezvousSession(UUID sessionId, Instant expiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (activeRendezvousSessions.containsKey(sessionId)) return true;
        synchronized (activeRendezvousSessions) {
            if (activeRendezvousSessions.containsKey(sessionId)) return true;
            if (activeRendezvousSessions.size() >= config.maxConcurrentRendezvousSessions()) return false;
            activeRendezvousSessions.put(sessionId, expiresAt);
            return true;
        }
    }
    public void releaseRendezvousSession(UUID sessionId) { if (sessionId != null) activeRendezvousSessions.remove(sessionId); }
    public int activeRendezvousSessions() { return activeRendezvousSessions.size(); }
    public void expireRendezvousSessions(Instant now) {
        activeRendezvousSessions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    public static String peerKey(InetAddress address) {
        return address == null ? "unknown" : address.getHostAddress();
    }

    private boolean consume(String peerKey, Instant now, int maximum, RequestClass requestClass) {
        PeerWindow peer = peers.computeIfAbsent(normalize(peerKey), ignored -> new PeerWindow());
        synchronized (peer) {
            if (peer.blockedUntil.isAfter(now)) return false;
            Deque<Instant> requests = peer.requests(requestClass);
            purge(requests, now);
            if (requests.size() >= maximum) {
                peer.blockedUntil = now.plus(TEMPORARY_BLOCK);
                return false;
            }
            requests.addLast(now);
            return true;
        }
    }

    private static void purge(Deque<Instant> instants, Instant now) {
        Instant cutoff = now.minus(RATE_WINDOW);
        while (!instants.isEmpty() && !instants.getFirst().isAfter(cutoff)) instants.removeFirst();
    }
    private static String normalize(String peerKey) {
        return peerKey == null || peerKey.isBlank() ? "unknown" : peerKey;
    }
    private enum RequestClass { FIND_NODE, FORWARDED, RENDEZVOUS }
    private static final class PeerWindow {
        private final Deque<Instant> findNodeRequests = new ArrayDeque<>();
        private final Deque<Instant> forwardedRequests = new ArrayDeque<>();
        private final Deque<Instant> rendezvousRequests = new ArrayDeque<>();
        private final Deque<Instant> violations = new ArrayDeque<>();
        private Instant blockedUntil = Instant.EPOCH;
        private Deque<Instant> requests(RequestClass requestClass) {
            return switch (requestClass) {
                case FIND_NODE -> findNodeRequests;
                case FORWARDED -> forwardedRequests;
                case RENDEZVOUS -> rendezvousRequests;
            };
        }
    }
}
