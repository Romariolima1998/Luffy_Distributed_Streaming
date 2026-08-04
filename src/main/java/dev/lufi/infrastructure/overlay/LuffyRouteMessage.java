package dev.lufi.infrastructure.overlay;

import bt.protocol.extended.ExtendedMessage;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mensagem BEP 10 privada {@code lf_route}; transporta somente controle de rota. */
public final class LuffyRouteMessage extends ExtendedMessage {
    /** Versao 2 acrescenta o caminho limitado de participantes para evitar ciclos. */
    public static final int PROTOCOL_VERSION = 2;
    /** Limite do protocolo; a configuracao local pode escolher um valor menor. */
    public static final int MAX_TTL = 6;
    public static final int MAX_ROUTE_PARTICIPANTS = MAX_TTL;

    public enum Type {
        FIND_NODE(0), NODE_FOUND(1), NODE_NOT_FOUND(2), ROUTE_ERROR(3);

        private final int code;
        Type(int code) { this.code = code; }
        int code() { return code; }
        static Type fromCode(int code) {
            for (Type type : values()) if (type.code == code) return type;
            throw new IllegalArgumentException("tipo lf_route desconhecido");
        }
    }

    public enum RouteErrorCode {
        INVALID_REQUEST(0), EXPIRED(1), TTL_EXHAUSTED(2), NO_ROUTE(3), DUPLICATE_REQUEST(4), REQUEST_CONFLICT(5),
        SEARCH_TIMEOUT(6);

        private final int code;
        RouteErrorCode(int code) { this.code = code; }
        int code() { return code; }
        static RouteErrorCode fromCode(int code) {
            for (RouteErrorCode value : values()) if (value.code == code) return value;
            throw new IllegalArgumentException("codigo ROUTE_ERROR lf_route desconhecido");
        }
    }

    private final Type type;
    private final int protocolVersion;
    private final UUID requestId;
    private final LuffyNodeId requesterNodeId;
    private final LuffyNodeId targetNodeId;
    private final String contentInfoHash;
    private final int ttl;
    private final Instant createdAt;
    private final List<LuffyNodeId> routeParticipants;
    private final LuffyNodeId rendezvousNodeId;
    private final int distance;
    private final TargetCapabilities targetCapabilities;
    private final RouteErrorCode errorCode;

    private LuffyRouteMessage(Type type, UUID requestId, LuffyNodeId requesterNodeId, LuffyNodeId targetNodeId,
                              String contentInfoHash, int ttl, Instant createdAt, LuffyNodeId rendezvousNodeId,
                              int distance, TargetCapabilities targetCapabilities, RouteErrorCode errorCode,
                              List<LuffyNodeId> routeParticipants) {
        this.type = Objects.requireNonNull(type, "type");
        this.protocolVersion = PROTOCOL_VERSION;
        this.requestId = requireRequestId(requestId);
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
        this.requesterNodeId = requesterNodeId;
        this.contentInfoHash = contentInfoHash;
        this.ttl = ttl;
        this.createdAt = createdAt;
        this.routeParticipants = routeParticipants == null ? List.of() : List.copyOf(routeParticipants);
        this.rendezvousNodeId = rendezvousNodeId;
        this.distance = distance;
        this.targetCapabilities = targetCapabilities;
        this.errorCode = errorCode;
        validateShape();
    }

    public static LuffyRouteMessage findNode(UUID requestId, LuffyNodeId requesterNodeId, LuffyNodeId targetNodeId,
                                             String contentInfoHash, int ttl, Instant createdAt) {
        return new LuffyRouteMessage(Type.FIND_NODE, requestId, Objects.requireNonNull(requesterNodeId, "requesterNodeId"),
                targetNodeId, normalizeInfoHash(contentInfoHash), ttl, Objects.requireNonNull(createdAt, "createdAt"),
                null, 0, null, null, List.of(requesterNodeId));
    }

    static LuffyRouteMessage decodedFindNode(UUID requestId, LuffyNodeId requesterNodeId, LuffyNodeId targetNodeId,
                                              String contentInfoHash, int ttl, Instant createdAt,
                                              List<LuffyNodeId> routeParticipants) {
        return new LuffyRouteMessage(Type.FIND_NODE, requestId, requesterNodeId, targetNodeId,
                normalizeInfoHash(contentInfoHash), ttl, createdAt, null, 0, null, null, routeParticipants);
    }

    public static LuffyRouteMessage nodeFound(UUID requestId, LuffyNodeId targetNodeId, LuffyNodeId rendezvousNodeId,
                                              int distance, TargetCapabilities targetCapabilities) {
        return new LuffyRouteMessage(Type.NODE_FOUND, requestId, null, targetNodeId, null, 0, null,
                Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId"), distance,
                Objects.requireNonNull(targetCapabilities, "targetCapabilities"), null, List.of());
    }

    public static LuffyRouteMessage nodeNotFound(UUID requestId, LuffyNodeId targetNodeId) {
        return new LuffyRouteMessage(Type.NODE_NOT_FOUND, requestId, null, targetNodeId, null, 0, null,
                null, 0, null, null, List.of());
    }

    public static LuffyRouteMessage routeError(UUID requestId, LuffyNodeId targetNodeId, RouteErrorCode errorCode) {
        return new LuffyRouteMessage(Type.ROUTE_ERROR, requestId, null, targetNodeId, null, 0, null,
                null, 0, null, Objects.requireNonNull(errorCode, "errorCode"), List.of());
    }

    /** Cria o proximo salto sem alterar quem iniciou a busca nem o momento original. */
    public LuffyRouteMessage forwardedBy(LuffyNodeId forwardingNodeId) {
        if (type != Type.FIND_NODE) throw new IllegalStateException("somente FIND_NODE pode ser encaminhado");
        if (ttl <= 0) throw new IllegalStateException("TTL lf_route esgotado");
        Objects.requireNonNull(forwardingNodeId, "forwardingNodeId");
        List<LuffyNodeId> nextParticipants = new ArrayList<>(routeParticipants);
        if (!nextParticipants.contains(forwardingNodeId)) nextParticipants.add(forwardingNodeId);
        return decodedFindNode(requestId, requesterNodeId, targetNodeId, contentInfoHash, ttl - 1, createdAt,
                nextParticipants);
    }

    /** Cada relay contabiliza um salto ao devolver NODE_FOUND pela rota reversa. */
    public LuffyRouteMessage withIncreasedDistance() {
        if (type != Type.NODE_FOUND) return this;
        if (distance >= 255) throw new IllegalStateException("distancia lf_route excede o limite");
        return nodeFound(requestId, targetNodeId, rendezvousNodeId, distance + 1, targetCapabilities);
    }

    public Type type() { return type; }
    public int protocolVersion() { return protocolVersion; }
    public UUID requestId() { return requestId; }
    public LuffyNodeId requesterNodeId() { return requesterNodeId; }
    public LuffyNodeId targetNodeId() { return targetNodeId; }
    public String contentInfoHash() { return contentInfoHash; }
    public int ttl() { return ttl; }
    public Instant createdAt() { return createdAt; }
    /** Nos que ja encaminharam esta mesma consulta; nunca representa a lista de vizinhos do peer. */
    public List<LuffyNodeId> routeParticipants() { return routeParticipants; }
    public LuffyNodeId rendezvousNodeId() { return rendezvousNodeId; }
    public int distance() { return distance; }
    public TargetCapabilities targetCapabilities() { return targetCapabilities; }
    public RouteErrorCode errorCode() { return errorCode; }

    private void validateShape() {
        switch (type) {
            case FIND_NODE -> {
                Objects.requireNonNull(requesterNodeId, "requesterNodeId");
                normalizeInfoHash(contentInfoHash);
                // TTL zero e valido apenas quando o ultimo relay ja entregou a
                // consulta ao destino. Esse destino pode responder, mas jamais
                // encaminhar novamente.
                if (ttl < 0 || ttl > MAX_TTL) throw new IllegalArgumentException("TTL lf_route invalido");
                if (createdAt.isBefore(Instant.EPOCH)) throw new IllegalArgumentException("createdAt lf_route invalido");
                if (routeParticipants.isEmpty() || routeParticipants.size() > MAX_ROUTE_PARTICIPANTS
                        || !routeParticipants.getFirst().equals(requesterNodeId)
                        || routeParticipants.stream().distinct().count() != routeParticipants.size()) {
                    throw new IllegalArgumentException("participantes da rota lf_route invalidos");
                }
            }
            case NODE_FOUND -> {
                Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId");
                Objects.requireNonNull(targetCapabilities, "targetCapabilities");
                if (distance < 0 || distance > 255) throw new IllegalArgumentException("distancia lf_route invalida");
            }
            case NODE_NOT_FOUND -> { }
            case ROUTE_ERROR -> Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    private static UUID requireRequestId(UUID value) {
        Objects.requireNonNull(value, "requestId");
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("requestId lf_route nulo nao e permitido");
        }
        return value;
    }

    static String normalizeInfoHash(String value) {
        if (value == null || !value.matches("(?i)[a-f0-9]{40}")) {
            throw new IllegalArgumentException("contentInfoHash lf_route invalido");
        }
        return HexFormat.of().formatHex(HexFormat.of().parseHex(value));
    }

    /** Capacidades minimas que nao revelam conexoes, endpoint, IP ou versao do cliente alvo. */
    public record TargetCapabilities(boolean supportsRoute, boolean supportsRendezvous,
                                     boolean supportsUtp, boolean supportsHolePunch) {
        public TargetCapabilities {
            if (supportsHolePunch && !supportsUtp) {
                throw new IllegalArgumentException("hole punch exige uTP");
            }
            if (supportsRendezvous && (!supportsUtp || !supportsHolePunch)) {
                throw new IllegalArgumentException("rendezvous exige uTP e hole punch");
            }
        }

        public static TargetCapabilities from(LuffyPeerCapabilities capabilities) {
            Objects.requireNonNull(capabilities, "capabilities");
            return new TargetCapabilities(capabilities.supportsRoute(), capabilities.supportsRendezvous(),
                    capabilities.supportsUtp(), capabilities.supportsHolePunch());
        }
    }
}
