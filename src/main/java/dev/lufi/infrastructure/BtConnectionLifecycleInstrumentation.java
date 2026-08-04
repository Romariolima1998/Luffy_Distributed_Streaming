package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.ConnectionHandler;
import bt.net.ConnectionResult;
import bt.net.IConnectionHandlerFactory;
import bt.net.IConnectionSource;
import bt.net.IPeerConnectionFactory;
import bt.net.Peer;
import bt.net.PeerConnection;
import bt.runtime.BtRuntime;
import dev.lufi.infrastructure.bootstrap.BootstrapPeerConnectionRegistry;
import java.lang.reflect.Field;

/**
 * Adaptador observável do motor bt. A biblioteca não expõe eventos separados
 * para abertura TCP e handshake; esta instrumentação observa seus dois pontos
 * internos sem criar uma conexão paralela.
 */
final class BtConnectionLifecycleInstrumentation {
    private BtConnectionLifecycleInstrumentation() { }

    /** Compatibilidade para testes e adaptadores que apenas observam o lifecycle. */
    static boolean install(BtRuntime runtime, PeerConnectivityManager connectivity, P2pDiagnostics diagnostics) {
        return install(runtime, connectivity, diagnostics, new BootstrapPeerConnectionRegistry());
    }

    static boolean install(BtRuntime runtime, PeerConnectivityManager connectivity, P2pDiagnostics diagnostics,
                           BootstrapPeerConnectionRegistry liveConnections) {
        try {
            IPeerConnectionFactory factory = runtime.service(IPeerConnectionFactory.class);
            IConnectionHandlerFactory handlers = runtime.service(IConnectionHandlerFactory.class);
            IConnectionSource source = runtime.service(IConnectionSource.class);
            if (factory instanceof ObservedPeerConnectionFactory) return true;

            Field handlerField = field(factory.getClass(), "connectionHandlerFactory");
            handlerField.set(factory, new ObservedConnectionHandlerFactory(handlers, connectivity, diagnostics, liveConnections));

            Field factoryField = field(source.getClass(), "connectionFactory");
            factoryField.set(source, new ObservedPeerConnectionFactory(factory, connectivity));
            diagnostics.log("CONNECTIVITY: instrumentação TCP/handshake instalada no motor BitTorrent.");
            return true;
        } catch (Exception error) {
            diagnostics.log("CONNECTIVITY: instrumentação TCP/handshake indisponível: " + message(error)
                    + ". O evento final do BitTorrent continuará sendo registrado.");
            return false;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static String infoHash(TorrentId id) {
        StringBuilder hex = new StringBuilder(id.getBytes().length * 2);
        for (byte value : id.getBytes()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private static String failure(ConnectionResult result) {
        return result.getMessage().orElseGet(() -> result.getError()
                .map(BtConnectionLifecycleInstrumentation::message).orElse("falha de conexão não detalhada"));
    }

    private static String message(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private static PeerConnectivityManager.SocketAddresses socketAddresses(PeerConnection connection, P2pDiagnostics diagnostics) {
        try {
            Object handler = field(connection.getClass(), "handler").get(connection);
            Object channel = field(handler.getClass(), "channel").get(handler);
            if (channel instanceof java.nio.channels.SocketChannel socket) {
                return new PeerConnectivityManager.SocketAddresses(
                        String.valueOf(socket.getLocalAddress()), String.valueOf(socket.getRemoteAddress()));
            }
            throw new IllegalStateException("canal do socket não encontrado");
        } catch (Exception error) {
            diagnostics.log("SOCKET DIAGNOSTIC ERROR: não foi possível ler os endereços do SocketChannel: " + message(error) + ".");
            Peer peer = connection.getRemotePeer();
            return new PeerConnectivityManager.SocketAddresses("desconhecido", peer.getInetAddress().getHostAddress() + ":" + connection.getRemotePort());
        }
    }

    private static final class ObservedPeerConnectionFactory implements IPeerConnectionFactory {
        private final IPeerConnectionFactory delegate;
        private final PeerConnectivityManager connectivity;

        private ObservedPeerConnectionFactory(IPeerConnectionFactory delegate, PeerConnectivityManager connectivity) {
            this.delegate = delegate; this.connectivity = connectivity;
        }

        @Override public ConnectionResult createOutgoingConnection(Peer peer, TorrentId torrentId) {
            String infoHash = infoHash(torrentId);
            // Este é o ponto imediatamente anterior ao SocketChannel.connect() do motor bt.
            connectivity.onTcpConnectStart(infoHash, peer);
            ConnectionResult result = delegate.createOutgoingConnection(peer, torrentId);
            if (!result.isSuccess()) connectivity.onOutgoingConnectionFactoryFailure(infoHash, peer,
                    result.getError().orElse(null), failure(result));
            return result;
        }

        @Override public ConnectionResult createIncomingConnection(Peer peer, java.nio.channels.SocketChannel channel) {
            return delegate.createIncomingConnection(peer, channel);
        }
    }

    private static final class ObservedConnectionHandlerFactory implements IConnectionHandlerFactory {
        private final IConnectionHandlerFactory delegate;
        private final PeerConnectivityManager connectivity;
        private final P2pDiagnostics diagnostics;
        private final BootstrapPeerConnectionRegistry liveConnections;

        private ObservedConnectionHandlerFactory(IConnectionHandlerFactory delegate, PeerConnectivityManager connectivity,
                                                 P2pDiagnostics diagnostics, BootstrapPeerConnectionRegistry liveConnections) {
            this.delegate = delegate; this.connectivity = connectivity; this.diagnostics = diagnostics;
            this.liveConnections = java.util.Objects.requireNonNull(liveConnections, "liveConnections");
        }

        @Override public ConnectionHandler getIncomingHandler() {
            return new ObservedIncomingConnectionHandler(delegate.getIncomingHandler(), liveConnections);
        }

        @Override public ConnectionHandler getOutgoingHandler(TorrentId torrentId) {
            return new ObservedOutgoingConnectionHandler(delegate.getOutgoingHandler(torrentId), infoHash(torrentId), connectivity,
                    diagnostics, liveConnections);
        }
    }

    /** O torrent de uma conexao de entrada so fica conhecido depois do handshake delegado. */
    private static final class ObservedIncomingConnectionHandler implements ConnectionHandler {
        private final ConnectionHandler delegate;
        private final BootstrapPeerConnectionRegistry liveConnections;

        private ObservedIncomingConnectionHandler(ConnectionHandler delegate, BootstrapPeerConnectionRegistry liveConnections) {
            this.delegate = delegate;
            this.liveConnections = liveConnections;
        }

        @Override public boolean handleConnection(PeerConnection connection) {
            boolean accepted = delegate.handleConnection(connection);
            if (accepted) liveConnections.register(connection);
            return accepted;
        }
    }

    private static final class ObservedOutgoingConnectionHandler implements ConnectionHandler {
        private final ConnectionHandler delegate;
        private final String infoHash;
        private final PeerConnectivityManager connectivity;
        private final P2pDiagnostics diagnostics;
        private final BootstrapPeerConnectionRegistry liveConnections;

        private ObservedOutgoingConnectionHandler(ConnectionHandler delegate, String infoHash, PeerConnectivityManager connectivity,
                                                 P2pDiagnostics diagnostics, BootstrapPeerConnectionRegistry liveConnections) {
            this.delegate = delegate; this.infoHash = infoHash; this.connectivity = connectivity; this.diagnostics = diagnostics;
            this.liveConnections = liveConnections;
        }

        @Override public boolean handleConnection(PeerConnection connection) {
            Peer peer = connection.getRemotePeer();
            int port = connection.getRemotePort();
            if (isUtpConnection(connection)) return handleUtpConnection(connection, peer, port);
            connectivity.onTcpConnectSuccess(infoHash, peer, port, socketAddresses(connection, diagnostics));
            connectivity.onBittorrentHandshakeStart(infoHash, peer, port);
            try {
                boolean accepted = delegate.handleConnection(connection);
                if (accepted) {
                    liveConnections.register(connection);
                    connectivity.onBittorrentHandshakeSuccess(infoHash, peer, port);
                }
                else connectivity.onBittorrentHandshakeFailure(infoHash, peer, port, "peer rejeitou o handshake");
                return accepted;
            } catch (RuntimeException error) {
                connectivity.onBittorrentHandshakeFailure(infoHash, peer, port, message(error));
                throw error;
            }
        }

        private boolean handleUtpConnection(PeerConnection connection, Peer peer, int port) {
            connectivity.onUtpBittorrentHandshakeStart(infoHash, peer, port);
            try {
                boolean accepted = delegate.handleConnection(connection);
                if (accepted) {
                    liveConnections.register(connection);
                    connectivity.onUtpBittorrentHandshakeSuccess(infoHash, peer, port);
                }
                else connectivity.onUtpBittorrentHandshakeFailure(infoHash, peer, port, "peer rejeitou o handshake sobre uTP");
                return accepted;
            } catch (RuntimeException error) {
                connectivity.onUtpBittorrentHandshakeFailure(infoHash, peer, port, message(error));
                throw error;
            }
        }

        private boolean isUtpConnection(PeerConnection connection) {
            try {
                Object handler = field(connection.getClass(), "handler").get(connection);
                Object channel = field(handler.getClass(), "channel").get(handler);
                return channel instanceof java.nio.channels.SocketChannel socket && UtpConnectionMarkers.isMarked(socket);
            } catch (Exception ignored) { return false; }
        }
    }
}
