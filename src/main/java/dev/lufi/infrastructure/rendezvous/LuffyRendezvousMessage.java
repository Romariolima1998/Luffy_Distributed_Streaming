package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import bt.protocol.extended.ExtendedMessage;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Mensagem BEP 10 privada {@code lf_rendezvous}; nunca carrega metadata ou pieces. */
public final class LuffyRendezvousMessage extends ExtendedMessage {
    public static final int PROTOCOL_VERSION = 1;

    public enum Type {
        RENDEZVOUS_REQUEST(0), RENDEZVOUS_PREPARE(1), RENDEZVOUS_ACCEPTED(2),
        RENDEZVOUS_REJECTED(3), RENDEZVOUS_RESULT(4), RENDEZVOUS_ERROR(5);
        private final int code;
        Type(int code) { this.code = code; }
        int code() { return code; }
        static Type fromCode(int code) {
            for (Type value : values()) if (value.code == code) return value;
            throw new IllegalArgumentException("tipo lf_rendezvous desconhecido");
        }
    }

    /** Indica o sentido na rota ja criada por lf_route, nao um novo caminho de rede. */
    public enum Direction {
        TO_RENDEZVOUS(0), TO_REQUESTER(1), TO_TARGET(2);
        private final int code;
        Direction(int code) { this.code = code; }
        int code() { return code; }
        static Direction fromCode(int code) {
            for (Direction value : values()) if (value.code == code) return value;
            throw new IllegalArgumentException("direcao lf_rendezvous desconhecida");
        }
    }

    /** Razoes curtas, sem endpoint, topologia, dados de torrent ou detalhes do sistema remoto. */
    public enum Code {
        NONE(0), TARGET_UNAVAILABLE(1), ROUTE_UNAVAILABLE(2), TARGET_REJECTED(3),
        PREPARED(4), PUNCH_SUCCEEDED(5), PUNCH_FAILED(6), PROTOCOL_ERROR(7), EXPIRED(8);
        private final int code;
        Code(int code) { this.code = code; }
        int code() { return code; }
        static Code fromCode(int code) {
            for (Code value : values()) if (value.code == code) return value;
            throw new IllegalArgumentException("codigo lf_rendezvous desconhecido");
        }
    }

    private final Type type;
    private final UUID sessionId;
    private final UUID routeRequestId;
    private final LuffyNodeId requesterNodeId;
    private final LuffyNodeId targetNodeId;
    private final LuffyNodeId rendezvousNodeId;
    private final TorrentId contentTorrentId;
    private final Direction direction;
    private final Code code;
    /** Endpoint uTP confirmado pelo proprio dono antes de ser enviado ao coordenador. */
    private final Optional<RendezvousEndpoint> endpoint;

    private LuffyRendezvousMessage(Type type, UUID sessionId, UUID routeRequestId, LuffyNodeId requesterNodeId,
                                   LuffyNodeId targetNodeId, LuffyNodeId rendezvousNodeId,
                                   TorrentId contentTorrentId, Direction direction, Code code, RendezvousEndpoint endpoint) {
        this.type = Objects.requireNonNull(type, "type");
        this.sessionId = requireUuid(sessionId, "sessionId");
        this.routeRequestId = requireUuid(routeRequestId, "routeRequestId");
        this.requesterNodeId = Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
        this.rendezvousNodeId = Objects.requireNonNull(rendezvousNodeId, "rendezvousNodeId");
        this.contentTorrentId = Objects.requireNonNull(contentTorrentId, "contentTorrentId");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.code = Objects.requireNonNull(code, "code");
        this.endpoint = Optional.ofNullable(endpoint);
        if (requesterNodeId.equals(targetNodeId)) throw new IllegalArgumentException("requisitante e alvo lf_rendezvous coincidem");
        validateShape();
    }

    public static LuffyRendezvousMessage request(RendezvousSession session, RendezvousEndpoint requesterEndpoint) {
        return create(Type.RENDEZVOUS_REQUEST, session, Direction.TO_RENDEZVOUS, Code.NONE, requesterEndpoint);
    }
    public static LuffyRendezvousMessage prepare(RendezvousSession session, Direction direction, RendezvousEndpoint endpoint) {
        return create(Type.RENDEZVOUS_PREPARE, session, direction, Code.NONE, endpoint);
    }
    public static LuffyRendezvousMessage accepted(RendezvousSession session, RendezvousEndpoint endpoint) {
        return create(Type.RENDEZVOUS_ACCEPTED, session, Direction.TO_RENDEZVOUS, Code.NONE, endpoint);
    }
    public static LuffyRendezvousMessage rejected(RendezvousSession session, Code code) {
        return create(Type.RENDEZVOUS_REJECTED, session, Direction.TO_REQUESTER, code, null);
    }
    public static LuffyRendezvousMessage result(RendezvousSession session, Code code) {
        return result(session, Direction.TO_REQUESTER, code);
    }
    public static LuffyRendezvousMessage result(RendezvousSession session, Direction direction, Code code) {
        if (direction != Direction.TO_REQUESTER && direction != Direction.TO_RENDEZVOUS) {
            throw new IllegalArgumentException("RESULT lf_rendezvous exige requester ou coordenador como destino");
        }
        return create(Type.RENDEZVOUS_RESULT, session, direction, code, null);
    }
    public static LuffyRendezvousMessage error(RendezvousSession session, Direction direction, Code code) {
        return create(Type.RENDEZVOUS_ERROR, session, direction, code, null);
    }

    static LuffyRendezvousMessage decoded(Type type, UUID sessionId, UUID routeRequestId, LuffyNodeId requesterNodeId,
                                          LuffyNodeId targetNodeId, LuffyNodeId rendezvousNodeId,
                                          TorrentId contentTorrentId, Direction direction, Code code, RendezvousEndpoint endpoint) {
        return new LuffyRendezvousMessage(type, sessionId, routeRequestId, requesterNodeId, targetNodeId,
                rendezvousNodeId, contentTorrentId, direction, code, endpoint);
    }

    private static LuffyRendezvousMessage create(Type type, RendezvousSession session, Direction direction, Code code,
                                                 RendezvousEndpoint endpoint) {
        Objects.requireNonNull(session, "session");
        return new LuffyRendezvousMessage(type, session.sessionId(), session.routeRequestId(), session.requesterNodeId(),
                session.targetNodeId(), session.rendezvousNodeId(), session.contentTorrentId(), direction,
                Objects.requireNonNull(code, "code"), endpoint);
    }

    private void validateShape() {
        boolean errorLike = type == Type.RENDEZVOUS_REJECTED || type == Type.RENDEZVOUS_RESULT || type == Type.RENDEZVOUS_ERROR;
        if (!errorLike && code != Code.NONE) throw new IllegalArgumentException("codigo inesperado em lf_rendezvous");
        if (type == Type.RENDEZVOUS_REJECTED && (code == Code.NONE || code == Code.PREPARED || code == Code.PUNCH_SUCCEEDED)) {
            throw new IllegalArgumentException("rejeicao lf_rendezvous sem motivo valido");
        }
        if (type == Type.RENDEZVOUS_RESULT && (code != Code.PREPARED && code != Code.PUNCH_SUCCEEDED && code != Code.PUNCH_FAILED)) {
            throw new IllegalArgumentException("resultado lf_rendezvous invalido");
        }
        if (type == Type.RENDEZVOUS_ERROR && code == Code.NONE) throw new IllegalArgumentException("erro lf_rendezvous sem codigo");
        boolean needsEndpoint = type == Type.RENDEZVOUS_REQUEST || type == Type.RENDEZVOUS_PREPARE;
        boolean allowsEndpoint = needsEndpoint || type == Type.RENDEZVOUS_ACCEPTED;
        if ((needsEndpoint && endpoint.isEmpty()) || (!allowsEndpoint && endpoint.isPresent())) {
            throw new IllegalArgumentException("endpoint lf_rendezvous invalido para " + type);
        }
        if (type == Type.RENDEZVOUS_ACCEPTED && endpoint.isPresent() && direction != Direction.TO_RENDEZVOUS) {
            throw new IllegalArgumentException("ACCEPTED com endpoint deve retornar ao coordenador");
        }
        if (type == Type.RENDEZVOUS_PREPARE && direction != Direction.TO_REQUESTER && direction != Direction.TO_TARGET) {
            throw new IllegalArgumentException("PREPARE lf_rendezvous exige requester ou target como destino");
        }
        if (type == Type.RENDEZVOUS_REQUEST && direction != Direction.TO_RENDEZVOUS) {
            throw new IllegalArgumentException("REQUEST lf_rendezvous exige coordenador como destino");
        }
    }

    private static UUID requireUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " nulo nao e permitido");
        }
        return value;
    }

    public int protocolVersion() { return PROTOCOL_VERSION; }
    public Type type() { return type; }
    public UUID sessionId() { return sessionId; }
    public UUID routeRequestId() { return routeRequestId; }
    public LuffyNodeId requesterNodeId() { return requesterNodeId; }
    public LuffyNodeId targetNodeId() { return targetNodeId; }
    public LuffyNodeId rendezvousNodeId() { return rendezvousNodeId; }
    public TorrentId contentTorrentId() { return contentTorrentId; }
    public Direction direction() { return direction; }
    public Code code() { return code; }
    public Optional<RendezvousEndpoint> endpoint() { return endpoint; }

    public record RendezvousEndpoint(InetAddress address, int port) {
        public RendezvousEndpoint {
            Objects.requireNonNull(address, "address");
            if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("familia de endpoint rendezvous invalida");
            }
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("endpoint rendezvous invalido");
            }
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("porta rendezvous invalida");
        }
    }
}
