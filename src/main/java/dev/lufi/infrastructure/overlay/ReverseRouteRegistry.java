package dev.lufi.infrastructure.overlay;

import bt.net.ConnectionKey;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantem a rota inversa de uma consulta encaminhada e agrega as respostas do
 * fan-out. Um resultado NODE_FOUND vence imediatamente; ausencia e erros so
 * sobem quando todos os filhos responderam.
 */
public final class ReverseRouteRegistry {
    private final Map<UUID, ReverseRoute> routes = new ConcurrentHashMap<>();

    public void register(UUID requestId, ConnectionKey previousHop, LuffyNodeId targetNodeId,
                         Instant expiresAt, Set<ConnectionKey> forwardedHops) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(previousHop, "previousHop");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Set<ConnectionKey> targets = Set.copyOf(Objects.requireNonNull(forwardedHops, "forwardedHops"));
        if (targets.isEmpty()) throw new IllegalArgumentException("rota inversa exige ao menos um salto encaminhado");
        routes.putIfAbsent(requestId, new ReverseRoute(previousHop, targetNodeId, expiresAt, targets));
    }

    public Optional<ReverseRoute> remove(UUID requestId, Instant now) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(now, "now");
        ReverseRoute route = routes.remove(requestId);
        if (route == null || !route.expiresAt().isAfter(now)) return Optional.empty();
        return Optional.of(route);
    }

    public Optional<ReverseRoute> find(UUID requestId, Instant now) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(now, "now");
        ReverseRoute route = routes.get(requestId);
        if (route == null || !route.expiresAt().isAfter(now)) return Optional.empty();
        return Optional.of(route);
    }

    /**
     * Aceita somente uma resposta de cada proximo salto que recebeu a consulta.
     * Respostas repetidas ou de outras conexoes nao podem alterar a rota.
     */
    public Optional<ForwardedResponse> acceptResponse(UUID requestId, ConnectionKey source,
                                                       LuffyRouteMessage response, Instant now) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(now, "now");
        ReverseRoute route = routes.get(requestId);
        if (route == null) return Optional.empty();
        if (!route.expiresAt().isAfter(now)) {
            routes.remove(requestId, route);
            return Optional.empty();
        }
        synchronized (route) {
            if (!routes.containsKey(requestId) || !route.expiresAt().isAfter(now)
                    || !route.targetNodeId().equals(response.targetNodeId())
                    || !route.forwardedHops().contains(source)
                    || !route.respondedHops().add(source)) {
                return Optional.empty();
            }
            if (response.type() == LuffyRouteMessage.Type.NODE_FOUND) {
                routes.remove(requestId, route);
                return Optional.of(new ForwardedResponse(route.previousHop(), source, response));
            }
            route.responses().put(source, response);
            if (route.respondedHops().size() < route.forwardedHops().size()) return Optional.empty();

            routes.remove(requestId, route);
            return Optional.of(new ForwardedResponse(route.previousHop(), source, terminalResponse(route, response)));
        }
    }

    public int expire(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = routes.size();
        routes.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        return Math.max(0, before - routes.size());
    }

    public int size() { return routes.size(); }
    public void clear() { routes.clear(); }

    private static LuffyRouteMessage terminalResponse(ReverseRoute route, LuffyRouteMessage fallback) {
        boolean onlyNotFound = route.responses().values().stream()
                .allMatch(message -> message.type() == LuffyRouteMessage.Type.NODE_NOT_FOUND);
        if (onlyNotFound) return LuffyRouteMessage.nodeNotFound(fallback.requestId(), route.targetNodeId());
        return route.responses().values().stream()
                .filter(message -> message.type() == LuffyRouteMessage.Type.ROUTE_ERROR)
                .findFirst().orElse(fallback);
    }

    /** A chave de conexao fica local; as mensagens nunca listam as conexoes filhas. */
    public static final class ReverseRoute {
        private final ConnectionKey previousHop;
        private final LuffyNodeId targetNodeId;
        private final Instant expiresAt;
        private final Set<ConnectionKey> forwardedHops;
        private final Set<ConnectionKey> respondedHops = new LinkedHashSet<>();
        private final Map<ConnectionKey, LuffyRouteMessage> responses = new LinkedHashMap<>();

        private ReverseRoute(ConnectionKey previousHop, LuffyNodeId targetNodeId, Instant expiresAt,
                             Set<ConnectionKey> forwardedHops) {
            this.previousHop = previousHop;
            this.targetNodeId = targetNodeId;
            this.expiresAt = expiresAt;
            this.forwardedHops = forwardedHops;
        }

        public ConnectionKey previousHop() { return previousHop; }
        public LuffyNodeId targetNodeId() { return targetNodeId; }
        public Instant expiresAt() { return expiresAt; }
        public Set<ConnectionKey> forwardedHops() { return forwardedHops; }
        private Set<ConnectionKey> respondedHops() { return respondedHops; }
        private Map<ConnectionKey, LuffyRouteMessage> responses() { return responses; }
    }

    public record ForwardedResponse(ConnectionKey previousHop, ConnectionKey source, LuffyRouteMessage response) {
        public ForwardedResponse {
            Objects.requireNonNull(previousHop, "previousHop");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(response, "response");
        }
    }
}
