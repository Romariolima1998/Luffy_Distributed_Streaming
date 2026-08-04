package dev.lufi.infrastructure.identity;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.Peer;
import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.Transport;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registro local, em toda a aplicacao, das conexoes BitTorrent que concluiram
 * a negociacao {@code lf_identity}. Ele nao abre conexoes e nao participa da
 * transferencia: apenas permite localizar uma conexao de controle ja viva por
 * {@link LuffyNodeId}, mesmo que ela tenha surgido em outro torrent.
 */
public final class ConnectedLuffyRegistry {
    private final ConcurrentMap<ConnectionKey, ConnectedLuffy> connections = new ConcurrentHashMap<>();

    /**
     * Registra ou atualiza uma conexao cuja identidade Luffy ja foi validada.
     * Uma mesma conexao BitTorrent jamais pode mudar de {@code nodeId}.
     */
    public ConnectedLuffy registerConnection(ConnectedLuffy candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return connections.compute(candidate.connectionKey(), (key, existing) -> {
            if (existing == null) return candidate;
            if (!existing.nodeId().equals(candidate.nodeId())) {
                throw new IllegalStateException("Uma conexao BitTorrent nao pode possuir duas identidades Luffy");
            }
            Instant lastSeen = candidate.lastSeen().isAfter(existing.lastSeen())
                    ? candidate.lastSeen() : existing.lastSeen();
            return new ConnectedLuffy(candidate.nodeId(), candidate.sourceTorrent(), candidate.peer(),
                    candidate.connectionKey(), candidate.capabilities(), candidate.tcpEndpoint(), candidate.utpEndpoint(),
                    candidate.direction(), existing.connectedAt(), lastSeen);
        });
    }

    /** Remove exatamente a referencia da conexao encerrada. */
    public boolean removeConnection(ConnectionKey connectionKey) {
        return connections.remove(Objects.requireNonNull(connectionKey, "connectionKey")) != null;
    }

    /** Consulta interna por uma conexao especifica, sem expor o conjunto de peers registrados. */
    public Optional<ConnectedLuffy> findConnection(ConnectionKey connectionKey) {
        return Optional.ofNullable(connections.get(Objects.requireNonNull(connectionKey, "connectionKey")));
    }

    /**
     * Limpeza para o lifecycle do bt-core, que informa torrent, peer e porta
     * quando uma conexao e encerrada antes de expor novamente o ConnectionKey.
     */
    public int removeConnection(TorrentId sourceTorrent, Peer peer, int remotePort) {
        Objects.requireNonNull(sourceTorrent, "sourceTorrent");
        Objects.requireNonNull(peer, "peer");
        if (remotePort < 1 || remotePort > 65_535) throw new IllegalArgumentException("Porta remota invalida");
        int before = connections.size();
        connections.keySet().removeIf(key -> key.getTorrentId().equals(sourceTorrent)
                && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort);
        return Math.max(0, before - connections.size());
    }

    /** Retorna somente as conexoes vivas do NodeId pedido, sem expor o registro completo. */
    public List<ConnectedLuffy> findConnections(LuffyNodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return connections.values().stream().filter(connection -> connection.nodeId().equals(nodeId))
                .sorted(Comparator.comparing(ConnectedLuffy::lastSeen).reversed()
                        .thenComparing(ConnectedLuffy::connectedAt, Comparator.reverseOrder()))
                .toList();
    }

    /** Escolhe a melhor conexao ja estabelecida para mensagens futuras de controle. */
    public Optional<ConnectedLuffy> findBestControlConnection(LuffyNodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        Comparator<ConnectedLuffy> preference = Comparator
                .comparingInt(ConnectedLuffyRegistry::controlScore).reversed()
                .thenComparing(ConnectedLuffy::lastSeen, Comparator.reverseOrder())
                .thenComparing(ConnectedLuffy::connectedAt, Comparator.reverseOrder());
        return connections.values().stream().filter(connection -> connection.nodeId().equals(nodeId)).min(preference);
    }

    public boolean hasDirectConnection(LuffyNodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return connections.values().stream().anyMatch(connection -> connection.nodeId().equals(nodeId));
    }

    /** Exibe identidades conectadas, mas nunca as referencias de todas as conexoes. */
    public Set<LuffyNodeId> listConnectedNodeIds() {
        return connections.values().stream().map(ConnectedLuffy::nodeId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static int controlScore(ConnectedLuffy connection) {
        LuffyPeerCapabilities capabilities = connection.capabilities();
        int score = 0;
        if (capabilities.supportsDistributedRendezvous()) score += 8;
        if (capabilities.supportsRoute()) score += 4;
        if (capabilities.supportsUtp()) score += 2;
        if (capabilities.supportsHolePunch()) score++;
        return score;
    }

    /** A direcao ainda nao e inferida: ela so pode ser preenchida por instrumentacao que a associe ao ConnectionKey. */
    public enum ConnectionDirection {
        INCOMING,
        OUTGOING,
        UNKNOWN
    }

    /**
     * Referencia a uma conexao viva. Endpoints externos sao opcionais porque a
     * identidade nao transforma o endereco do socket em endpoint publico sem
     * uma observacao independente do registro de endpoints.
     */
    public record ConnectedLuffy(
            LuffyNodeId nodeId,
            TorrentId sourceTorrent,
            Peer peer,
            ConnectionKey connectionKey,
            LuffyPeerCapabilities capabilities,
            Optional<ObservedEndpoint> tcpEndpoint,
            Optional<ObservedEndpoint> utpEndpoint,
            ConnectionDirection direction,
            Instant connectedAt,
            Instant lastSeen) {

        public ConnectedLuffy {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(sourceTorrent, "sourceTorrent");
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(connectionKey, "connectionKey");
            Objects.requireNonNull(capabilities, "capabilities");
            tcpEndpoint = tcpEndpoint == null ? Optional.empty() : tcpEndpoint;
            utpEndpoint = utpEndpoint == null ? Optional.empty() : utpEndpoint;
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(connectedAt, "connectedAt");
            Objects.requireNonNull(lastSeen, "lastSeen");
            if (!sourceTorrent.equals(connectionKey.getTorrentId())) {
                throw new IllegalArgumentException("O torrent da conexao deve ser o torrent de origem");
            }
            if (!peer.getInetAddress().equals(connectionKey.getPeer().getInetAddress())) {
                throw new IllegalArgumentException("O peer deve corresponder a referencia da conexao");
            }
            if (lastSeen.isBefore(connectedAt)) throw new IllegalArgumentException("lastSeen antecede connectedAt");
            tcpEndpoint.ifPresent(endpoint -> requireTransport(endpoint, Transport.TCP));
            utpEndpoint.ifPresent(endpoint -> requireTransport(endpoint, Transport.UTP));
        }

        public static ConnectedLuffy identified(ConnectionKey connectionKey, LuffyPeerCapabilities capabilities, Instant now) {
            Objects.requireNonNull(connectionKey, "connectionKey");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(now, "now");
            return new ConnectedLuffy(capabilities.nodeId(), connectionKey.getTorrentId(), connectionKey.getPeer(), connectionKey,
                    capabilities, Optional.empty(), Optional.empty(), ConnectionDirection.UNKNOWN, now, now);
        }

        private static void requireTransport(ObservedEndpoint endpoint, Transport expected) {
            if (endpoint.transport() != expected) {
                throw new IllegalArgumentException("Endpoint " + expected + " esperado, recebido " + endpoint.transport());
            }
        }
    }
}
