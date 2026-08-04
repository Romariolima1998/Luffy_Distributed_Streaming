package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.ConnectionResult;
import bt.net.IPeerConnectionFactory;
import bt.net.IPeerConnectionPool;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.runtime.BtRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conecta uma sessao uTP BEP 29 a um par de sockets locais. O motor Bt continua
 * executando o handshake e as pecas BitTorrent normais; o par local apenas faz
 * ponte de bytes para o fluxo uTP que vai diretamente ao outro peer.
 */
final class UtpBitTorrentBridge implements AutoCloseable {
    private final P2pDiagnostics diagnostics;
    private final PeerConnectivityManager connectivity;
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();
    private final java.util.Set<String> activeTunnels = ConcurrentHashMap.newKeySet();
    private final java.util.Set<LoopbackPair> activeLoopbackPairs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activePumpTasks = new AtomicInteger();
    private volatile EstablishedPeerConnectionPromoter connectionPromoter;
    private volatile IPeerConnectionPool peerConnectionPool;
    private volatile UtpTransportService transport;

    UtpBitTorrentBridge(P2pDiagnostics diagnostics, PeerConnectivityManager connectivity) {
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.connectivity = Objects.requireNonNull(connectivity, "connectivity");
    }

    void attach(BtRuntime runtime, UtpTransportService utpTransport) {
        Objects.requireNonNull(runtime, "runtime");
        IPeerConnectionFactory factory = runtime.service(IPeerConnectionFactory.class);
        this.connectionPromoter = new BtCoreConnectionFactoryAdapter(factory);
        this.peerConnectionPool = runtime.service(IPeerConnectionPool.class);
        this.transport = Objects.requireNonNull(utpTransport, "utpTransport");
        utpTransport.setIncomingListener(this::acceptIncoming);
        diagnostics.log("uTP BRIDGE: integrado ao motor BitTorrent " + BtCoreConnectionFactoryAdapter.EXPECTED_BT_CORE_VERSION
                + "; TCP e uTP permanecem caminhos separados.");
    }

    /** A implementacao atual de uTP esta associada ao listener UDP IPv4. */
    boolean supports(InetAddress address) { return address instanceof Inet4Address && transport != null; }

    /** Verdadeiro somente depois de o listener uTP e a ponte para o bt-core estarem prontos. */
    boolean isReady() { return transport != null && connectionPromoter != null && peerConnectionPool != null; }

    /** Evita um segundo CONNECT para um tunel que ja esta abrindo ou transportando BitTorrent. */
    boolean hasActiveOrConnecting(String infoHash, InetAddress address, int port) {
        String key = key(infoHash, address, port);
        AtomicBoolean opening = activeConnections.get(key);
        return activeTunnels.contains(key) || opening != null && opening.get();
    }

    int activeLoopbackPairCount() { return activeLoopbackPairs.size(); }
    int activePumpTaskCount() { return activePumpTasks.get(); }
    int pendingConnectionCount() { return activeConnections.size(); }

    void markHolePunchUnavailable(String infoHash, InetAddress address, int port, String reason) {
        connectivity.onHolePunchUnavailable(infoHash,
                new PeerConnectivityManager.PeerEndpoint(address, port, PeerConnectivityManager.Transport.UTP), reason);
    }

    CompletableFuture<Void> connectDirect(String infoHash, InetAddress address, int port) {
        return connect(infoHash, address, port, PeerConnectivityManager.Strategy.DIRECT_UTP);
    }

    CompletableFuture<Void> connectViaHolePunch(String infoHash, InetAddress address, int port) {
        return connect(infoHash, address, port, PeerConnectivityManager.Strategy.HOLE_PUNCHING);
    }

    private CompletableFuture<Void> connect(String infoHash, InetAddress address, int port, PeerConnectivityManager.Strategy strategy) {
        UtpTransportService currentTransport = transport;
        if (currentTransport == null || connectionPromoter == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ponte uTP ainda nao foi inicializada"));
        }
        String key = key(infoHash, address, port);
        AtomicBoolean guard = activeConnections.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (activeTunnels.contains(key) || !guard.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        PeerConnectivityManager.PeerEndpoint endpoint = new PeerConnectivityManager.PeerEndpoint(address, port, PeerConnectivityManager.Transport.UTP);
        connectivity.onUtpConnectStart(infoHash, endpoint, socketAddresses(currentTransport, address, port), strategy);
        diagnostics.log(P2pDiagnostics.Layer.UTP, "BITTORRENT CONNECT START: infoHash=" + infoHash + "; peer=" + endpoint(address, port)
                + "; estratégia=" + strategy + ".");
        // A factory pode bloquear durante o handshake. Ela não pode executar no
        // loop que recebe UDP, pois esse loop precisa continuar processando os
        // ACKs e dados que fazem a própria sessão uTP avançar.
        return currentTransport.connect(new InetSocketAddress(address, port)).<Void>thenApplyAsync(session -> {
            try {
                connectivity.onUtpConnectSuccess(infoHash, endpoint, socketAddresses(currentTransport, address, port));
                bridgeOutgoing(infoHash, address, port, session, key);
            } catch (Exception error) {
                diagnostics.log(P2pDiagnostics.Layer.UTP, "BITTORRENT CONNECT FAILED: infoHash=" + infoHash + "; peer=" + endpoint(address, port)
                        + "; reason=" + message(error) + ".");
                session.close();
                throw new CompletionException(error);
            } finally {
                releaseConnection(key, guard);
            }
            // Unreachable when bridgeOutgoing fails; required by thenApply's type.
            //noinspection UnreachableCode
            return null;
        }).exceptionally(error -> {
            releaseConnection(key, guard);
            connectivity.onUtpFailure(infoHash, endpoint, error);
            diagnostics.log(P2pDiagnostics.Layer.UTP, "CONNECT FAILED: infoHash=" + infoHash + "; peer=" + endpoint(address, port)
                    + "; estratégia=" + strategy + "; reason=" + message(error) + ".");
            throw new CompletionException(error);
        });
    }

    private void bridgeOutgoing(String infoHash, InetAddress address, int port, UtpTransportService.UtpSession session, String key) throws Exception {
        LoopbackPair pair = LoopbackPair.open();
        UtpConnectionMarkers.mark(pair.btSide());
        activeLoopbackPairs.add(pair);
        activeTunnels.add(key);
        startPumps(session, pair, () -> {
            activeTunnels.remove(key);
            activeLoopbackPairs.remove(pair);
        });
        Peer peer = InetPeer.build(address, port);
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(infoHash));
        ConnectionResult result = requirePromoter().promoteOutgoing(torrentId, peer, session, pair.btSide())
                .toCompletableFuture().join().connectionResult();
        if (!result.isSuccess()) {
            pair.close(); session.close();
            throw new IOException(result.getMessage().orElse("handshake BitTorrent sobre uTP recusado"), result.getError().orElse(null));
        }
        ConnectionRegistration registration = registerBitTorrentConnection(result);
        if (registration == ConnectionRegistration.REGISTERED) {
            diagnostics.log("uTP BITTORRENT PEER REGISTERED: infoHash=" + infoHash + "; peer=" + endpoint(address, port) + ".");
        } else {
            // Hole punching intentionally starts from both ends.  The other uTP
            // session may already have completed the BitTorrent handshake and
            // won the peer-pool race.  Keep that accepted connection and close
            // only this duplicate tunnel; it is not a failed rendezvous.
            diagnostics.log("uTP BITTORRENT PEER DUPLICATE CLOSED: infoHash=" + infoHash + "; peer=" + endpoint(address, port)
                    + "; mantendo a primeira conexão aceita pelo bt-core.");
        }
        diagnostics.log("uTP BITTORRENT HANDSHAKE START: infoHash=" + infoHash + "; peer=" + endpoint(address, port)
                + "; dados seguem diretamente por uTP, sem relay.");
    }

    private void acceptIncoming(UtpTransportService.UtpSession session) {
        EstablishedPeerConnectionPromoter promoter = connectionPromoter;
        if (promoter == null) {
            session.close();
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                LoopbackPair pair = LoopbackPair.open();
                UtpConnectionMarkers.mark(pair.btSide());
                activeLoopbackPairs.add(pair);
                startPumps(session, pair, () -> activeLoopbackPairs.remove(pair));
                Peer peer = InetPeer.build(session.remote().getAddress(), session.remote().getPort());
                ConnectionResult result = promoter.promoteIncoming(peer, session, pair.btSide())
                        .toCompletableFuture().join().connectionResult();
                if (!result.isSuccess()) {
                    pair.close(); session.close();
                    diagnostics.log("uTP BITTORRENT INCOMING FAILED: peer=" + UtpTransportService.display(session.remote())
                            + "; reason=" + result.getMessage().orElse("handshake recusado") + ".");
                    return;
                }
                String infoHash = bytesToHex(result.getConnection().getTorrentId().getBytes());
                registerBitTorrentConnectionAsync(result, infoHash, UtpTransportService.display(session.remote()));
                diagnostics.log("uTP BITTORRENT INCOMING START: infoHash=" + infoHash + "; peer="
                        + UtpTransportService.display(session.remote()) + "; handshake BitTorrent iniciado sobre uTP.");
            } catch (Exception error) {
                session.close();
                diagnostics.log("uTP BITTORRENT INCOMING FAILED: peer=" + UtpTransportService.display(session.remote())
                        + "; reason=" + message(error) + ".");
            }
        });
    }

    private void startPumps(UtpTransportService.UtpSession session, LoopbackPair pair, Runnable onClosed) {
        startPump(() -> pumpUtpToSocket(session, pair, onClosed));
        startPump(() -> pumpSocketToUtp(session, pair, onClosed));
    }

    private void startPump(Runnable pump) {
        activePumpTasks.incrementAndGet();
        try {
            Thread.startVirtualThread(() -> {
                try {
                    pump.run();
                } finally {
                    activePumpTasks.decrementAndGet();
                }
            });
        } catch (RuntimeException error) {
            activePumpTasks.decrementAndGet();
            throw error;
        }
    }

    private void pumpUtpToSocket(UtpTransportService.UtpSession session, LoopbackPair pair, Runnable onClosed) {
        try (OutputStream output = Channels.newOutputStream(pair.bridgeSide())) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = session.read(buffer, 0, buffer.length)) >= 0;) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException error) {
            diagnostics.log("uTP PUMP UTP_TO_SOCKET CLOSED: peer=" + UtpTransportService.display(session.remote())
                    + "; reason=" + message(error) + ".");
        } finally {
            session.close(); pair.close(); onClosed.run();
        }
    }

    private void pumpSocketToUtp(UtpTransportService.UtpSession session, LoopbackPair pair, Runnable onClosed) {
        try (InputStream input = Channels.newInputStream(pair.bridgeSide())) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) session.write(buffer, 0, read);
        } catch (IOException error) {
            diagnostics.log("uTP PUMP SOCKET_TO_UTP CLOSED: peer=" + UtpTransportService.display(session.remote())
                    + "; reason=" + message(error) + ".");
        } finally {
            session.close(); pair.close(); onClosed.run();
        }
    }

    /**
     * Espelha o passo pós-handshake de ConnectionSource fora do loop que recebe UDP.
     * Os callbacks da pool podem escrever mensagens BitTorrent e aguardar ACKs uTP.
     */
    private void registerBitTorrentConnectionAsync(ConnectionResult result, String infoHash, String peer) {
        Thread.startVirtualThread(() -> {
            try {
                ConnectionRegistration registration = registerBitTorrentConnection(result);
                diagnostics.log(registration == ConnectionRegistration.REGISTERED
                        ? "uTP BITTORRENT PEER REGISTERED: infoHash=" + infoHash + "; peer=" + peer + "."
                        : "uTP BITTORRENT PEER DUPLICATE CLOSED: infoHash=" + infoHash + "; peer=" + peer
                                + "; mantendo a primeira conexão aceita pelo bt-core.");
            } catch (Exception error) {
                result.getConnection().closeQuietly();
                diagnostics.log("uTP BITTORRENT PEER REGISTER FAILED: infoHash=" + infoHash + "; peer=" + peer
                        + "; reason=" + message(error) + ".");
            }
        });
    }

    private ConnectionRegistration registerBitTorrentConnection(ConnectionResult result) throws IOException {
        IPeerConnectionPool pool = peerConnectionPool;
        if (pool == null) throw new IllegalStateException("pool de conexões BitTorrent ainda não foi inicializado");
        var created = result.getConnection();
        var registered = pool.addConnectionIfAbsent(created);
        if (registered != created) {
            created.closeQuietly();
            return ConnectionRegistration.DUPLICATE;
        }
        return ConnectionRegistration.REGISTERED;
    }

    private enum ConnectionRegistration {
        REGISTERED,
        DUPLICATE
    }

    private EstablishedPeerConnectionPromoter requirePromoter() {
        EstablishedPeerConnectionPromoter promoter = connectionPromoter;
        if (promoter == null) throw new IllegalStateException("promotor de conexao bt-core ainda nao foi inicializado");
        return promoter;
    }

    private void releaseConnection(String key, AtomicBoolean guard) {
        guard.set(false);
        activeConnections.remove(key, guard);
    }

    @Override public void close() {
        activeConnections.clear();
        activeTunnels.clear();
        activeLoopbackPairs.forEach(LoopbackPair::close);
        activeLoopbackPairs.clear();
        UtpTransportService current = transport;
        if (current != null) current.close();
    }

    private static void close(SocketChannel channel) { try { channel.close(); } catch (IOException ignored) { } }
    private static String key(String infoHash, InetAddress address, int port) { return infoHash + "|" + address.getHostAddress() + ":" + port; }
    private static String endpoint(InetAddress address, int port) { return (address instanceof java.net.Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress()) + ":" + port; }
    private static PeerConnectivityManager.SocketAddresses socketAddresses(UtpTransportService transport, InetAddress remote, int port) {
        return new PeerConnectivityManager.SocketAddresses("UDP " + transport.localAddress().getHostAddress() + ":" + transport.localPort(),
                "UDP " + endpoint(remote, port));
    }
    private static String message(Throwable error) {
        Throwable root = error.getCause() == null ? error : error.getCause();
        String detail = root.getMessage();
        return root.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
    private static byte[] hexBytes(String infoHash) {
        if (infoHash == null || !infoHash.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("infoHash invalido");
        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(infoHash.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private record LoopbackPair(SocketChannel btSide, SocketChannel bridgeSide) implements AutoCloseable {
        static LoopbackPair open() throws IOException {
            try (ServerSocketChannel server = ServerSocketChannel.open()) {
                server.bind(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
                SocketChannel bt = SocketChannel.open();
                bt.connect(server.getLocalAddress());
                SocketChannel bridge = server.accept();
                bridge.configureBlocking(true);
                return new LoopbackPair(bt, bridge);
            }
        }
        @Override public void close() { UtpConnectionMarkers.unmark(btSide); UtpBitTorrentBridge.close(btSide); UtpBitTorrentBridge.close(bridgeSide); }
    }
}
