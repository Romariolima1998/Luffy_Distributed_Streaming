package dev.lufi.infrastructure.overlay;

import bt.net.ConnectionKey;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda somente os dois saltos locais da rota vencedora de {@code lf_route}.
 * Cada instalacao conhece apenas seu anterior e seu proximo; a mensagem nunca
 * carrega a topologia completa nem uma lista de vizinhos.
 */
public final class OverlayRoutePathRegistry {
    private final Map<UUID, RoutePath> paths = new ConcurrentHashMap<>();

    public void recordOrigin(UUID requestId, ConnectionKey nextHop, Instant expiresAt) {
        put(requestId, null, nextHop, expiresAt);
    }

    public void recordRelay(UUID requestId, ConnectionKey previousHop, ConnectionKey nextHop, Instant expiresAt) {
        put(requestId, Objects.requireNonNull(previousHop, "previousHop"),
                Objects.requireNonNull(nextHop, "nextHop"), expiresAt);
    }

    public void recordTerminal(UUID requestId, ConnectionKey previousHop, Instant expiresAt) {
        put(requestId, Objects.requireNonNull(previousHop, "previousHop"), null, expiresAt);
    }

    public Optional<ConnectionKey> nextHop(UUID requestId, Instant now) {
        return find(requestId, now).flatMap(path -> Optional.ofNullable(path.nextHop()));
    }

    public Optional<ConnectionKey> previousHop(UUID requestId, Instant now) {
        return find(requestId, now).flatMap(path -> Optional.ofNullable(path.previousHop()));
    }

    public Optional<RoutePath> find(UUID requestId, Instant now) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(now, "now");
        RoutePath path = paths.get(requestId);
        if (path == null) return Optional.empty();
        if (path.expiresAt().isAfter(now)) return Optional.of(path);
        paths.remove(requestId, path);
        return Optional.empty();
    }

    public int expire(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = paths.size();
        paths.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        return Math.max(0, before - paths.size());
    }

    public void remove(UUID requestId) { paths.remove(Objects.requireNonNull(requestId, "requestId")); }
    public int removeConnection(ConnectionKey connectionKey) {
        Objects.requireNonNull(connectionKey, "connectionKey");
        int before = paths.size();
        paths.entrySet().removeIf(entry -> connectionKey.equals(entry.getValue().previousHop())
                || connectionKey.equals(entry.getValue().nextHop()));
        return Math.max(0, before - paths.size());
    }
    public void clear() { paths.clear(); }
    public int size() { return paths.size(); }

    private void put(UUID requestId, ConnectionKey previousHop, ConnectionKey nextHop, Instant expiresAt) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (previousHop == null && nextHop == null) throw new IllegalArgumentException("rota sem saltos");
        RoutePath candidate = new RoutePath(previousHop, nextHop, expiresAt);
        paths.compute(requestId, (ignored, current) -> current == null || !current.expiresAt().isAfter(expiresAt)
                ? candidate : current);
    }

    public record RoutePath(ConnectionKey previousHop, ConnectionKey nextHop, Instant expiresAt) {
        public RoutePath { Objects.requireNonNull(expiresAt, "expiresAt"); }
    }
}
