package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.Peer;
import bt.net.PeerConnection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Referencias efemeras de conexoes BitTorrent ja aceitas pelo bt-core.
 *
 * <p>Este registro nao identifica usuarios e nao toma decisoes de selecao.
 * Ele existe somente para que uma politica que ja escolheu um {@link
 * dev.lufi.infrastructure.identity.LuffyNodeId} possa encerrar, de modo
 * localizado, a conexao BitTorrent correspondente.</p>
 */
public final class BootstrapPeerConnectionRegistry {
    private final ConcurrentMap<ConnectionKey, LiveConnection> connections = new ConcurrentHashMap<>();

    /** Registra uma conexao somente depois de seu handler BitTorrent a aceitar. */
    public void register(PeerConnection connection) {
        Objects.requireNonNull(connection, "connection");
        TorrentId torrentId = connection.getTorrentId();
        if (torrentId == null) return;
        connections.put(new ConnectionKey(connection.getRemotePeer(), connection.getRemotePort(), torrentId),
                new LiveConnection(connection, Instant.now(), false));
    }

    /** Fecha uma conexao viva uma unica vez. O lifecycle tambem removera a referencia. */
    public boolean close(ConnectionKey key) {
        LiveConnection connection = connections.remove(Objects.requireNonNull(key, "key"));
        if (connection == null) return false;
        connection.connection().closeQuietly();
        return true;
    }

    /** Limpeza idempotente disparada pelo evento de desconexao do bt-core. */
    public int remove(TorrentId torrentId, Peer peer, int remotePort) {
        Objects.requireNonNull(torrentId, "torrentId");
        Objects.requireNonNull(peer, "peer");
        if (remotePort < 1 || remotePort > 65_535) throw new IllegalArgumentException("Porta remota invalida");
        int before = connections.size();
        connections.keySet().removeIf(key -> key.getTorrentId().equals(torrentId)
                && key.getPeer().getInetAddress().equals(peer.getInetAddress())
                && key.getRemotePort() == remotePort);
        return Math.max(0, before - connections.size());
    }

    public boolean contains(ConnectionKey key) {
        return connections.containsKey(Objects.requireNonNull(key, "key"));
    }

    /** Marca um peer que enviou bitfield com ao menos uma peça como útil para download. */
    public void markPeerHasPieces(TorrentId torrentId, Peer peer, int remotePort, boolean hasPieces) {
        if (!hasPieces || torrentId == null || peer == null || remotePort < 1 || remotePort > 65_535) return;
        ConnectionKey key = new ConnectionKey(peer, remotePort, torrentId);
        connections.computeIfPresent(key, (ignored, current) -> current.withAdvertisedPieces());
    }

    public boolean hasAdvertisedPieces(ConnectionKey key) {
        LiveConnection connection = connections.get(Objects.requireNonNull(key, "key"));
        return connection != null && connection.advertisedPieces();
    }

    public Optional<Instant> acceptedAt(ConnectionKey key) {
        LiveConnection connection = connections.get(Objects.requireNonNull(key, "key"));
        return connection == null ? Optional.empty() : Optional.of(connection.acceptedAt());
    }

    /** Fotografia das conexões aceitas; usada pelo orçamento global, sem expor o canal. */
    public Set<ConnectionKey> connectionKeys() { return Set.copyOf(connections.keySet()); }

    public int size() { return connections.size(); }

    public void clear() { connections.clear(); }

    private record LiveConnection(PeerConnection connection, Instant acceptedAt, boolean advertisedPieces) {
        private LiveConnection {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
        private LiveConnection withAdvertisedPieces() {
            return advertisedPieces ? this : new LiveConnection(connection, acceptedAt, true);
        }
    }
}
