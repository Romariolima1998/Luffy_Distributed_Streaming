package dev.lufi.infrastructure.overlay;

import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deduplica FIND_NODE e detecta reutilizacao maliciosa de requestId com outro alvo. */
public final class RouteRequestCache {
    private final Map<UUID, Entry> requests = new ConcurrentHashMap<>();

    public Registration register(LuffyRouteMessage request, Instant now, Instant expiresAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (request.type() != LuffyRouteMessage.Type.FIND_NODE) {
            throw new IllegalArgumentException("RouteRequestCache aceita somente FIND_NODE");
        }
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiracao de rota invalida");
        expire(now);
        Entry incoming = new Entry(request.requesterNodeId(), request.targetNodeId(), request.contentInfoHash(), expiresAt);
        Entry existing = requests.putIfAbsent(request.requestId(), incoming);
        if (existing == null) return Registration.NEW;
        return existing.sameRequest(incoming) ? Registration.DUPLICATE : Registration.CONFLICT;
    }

    public int expire(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = requests.size();
        requests.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        return Math.max(0, before - requests.size());
    }

    public int size() { return requests.size(); }
    public void clear() { requests.clear(); }

    public enum Registration { NEW, DUPLICATE, CONFLICT }

    private record Entry(LuffyNodeId requester, LuffyNodeId target, String contentInfoHash, Instant expiresAt) {
        private boolean sameRequest(Entry other) {
            return requester.equals(other.requester) && target.equals(other.target)
                    && contentInfoHash.equals(other.contentInfoHash);
        }
    }
}
