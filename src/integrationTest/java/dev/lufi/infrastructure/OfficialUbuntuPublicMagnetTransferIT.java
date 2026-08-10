package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.dht.DHTConfig;
import bt.dht.DHTService;
import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.peer.IPeerRegistry;
import bt.peerexchange.PeerExchangeModule;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import dev.lufi.domain.MagnetLink;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Interoperabilidade publica contra um torrent oficial do Ubuntu. O teste
 * baixa no maximo a primeira piece validada e encerra o cliente imediatamente.
 */
@Tag("real-network")
class OfficialUbuntuPublicMagnetTransferIT {
    private static final URI OFFICIAL_TORRENT = URI.create(
            "https://releases.ubuntu.com/24.04.4/ubuntu-24.04.4-desktop-amd64.iso.torrent");
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @TempDir Path temporaryDirectory;

    @Test void receivesMetadataAndOnePieceFromTheOfficialUbuntuSwarm() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("LUFFY_LEGAL_PUBLIC_MAGNET_TESTS")),
                "Defina LUFFY_LEGAL_PUBLIC_MAGNET_TESTS=true para executar a transferencia publica legal.");

        List<String> log = new ArrayList<>();
        Torrent official = new MetadataService().fromUrl(OFFICIAL_TORRENT.toURL());
        MagnetLink magnet = officialMagnet(official);
        TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(magnet.infoHash()));
        event(log, "MAGNET PARSED", "infoHash=" + magnet.infoHash() + " TRACKERS=" + magnet.trackers().size());
        assertTrue(!magnet.trackers().isEmpty(), "O torrent oficial precisa trazer tracker para esta verificacao.");

        BtRuntime dhtRuntime = null;
        BtRuntime clientRuntime = null;
        BtClient client = null;
        try {
            dhtRuntime = dhtRuntime();
            DhtLookupRuntimeInitializer.ReadyDhtState ready = DhtLookupRuntimeInitializer.startupAndAwait(dhtRuntime);
            event(log, "DHT READY", "rpcServers=" + ready.runningRpcServers());
            event(log, "DHT NODES", "count=" + ready.knownNodes());
            List<Peer> dhtPeers = lookupDhtPeers(dhtRuntime, torrentId);
            event(log, "DHT PEERS", "count=" + dhtPeers.size());

            P2pDiagnostics diagnostics = new P2pDiagnostics();
            AtomicBoolean tcpConnectStarted = new AtomicBoolean();
            diagnostics.subscribe(line -> {
                if (line.contains("TCP CONNECT START") && tcpConnectStarted.compareAndSet(false, true)) {
                    event(log, "PEER CONNECT START", "transport=TCP");
                }
            });
            AtomicReference<BtRuntime> runtimeReference = new AtomicReference<>();
            AtomicInteger trackerPeers = new AtomicInteger();
            AtomicInteger pexPeers = new AtomicInteger();
            AtomicBoolean pieceRequest = new AtomicBoolean();
            AtomicBoolean stoppedAfterFirstPiece = new AtomicBoolean();
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch metadata = new CountDownLatch(1);
            CountDownLatch piece = new CountDownLatch(1);

            try (PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, promotion -> {
                BtRuntime active = runtimeReference.get();
                active.service(IPeerRegistry.class).addPeer(TorrentId.fromBytes(HexFormat.of().parseHex(promotion.infoHash())),
                        InetPeer.build(promotion.endpoint().address(), promotion.endpoint().port()));
            })) {
                Config config = new Config();
                config.setAcceptorPort(freeTcpPort());
                config.setPeerConnectionTimeout(Duration.ofSeconds(10));
                config.setPeerHandshakeTimeout(Duration.ofSeconds(10));
                config.setPeerConnectionRetryCount(0);
                clientRuntime = BtRuntime.builder(config).disableAutomaticShutdown().autoLoadModules()
                        .module(new PeerExchangeModule())
                        .module(new PexObservationModule((id, via, added, dropped) -> {
                            if (!id.equals(torrentId)) return;
                            int count = added == null ? 0 : added.size();
                            if (count > 0) {
                                pexPeers.addAndGet(count);
                                for (Peer peer : added) connectivity.onPexPeerDiscovered(magnet.infoHash(), peer);
                            }
                        })).build();
                runtimeReference.set(clientRuntime);
                assertTrue(BtConnectionLifecycleInstrumentation.install(clientRuntime, connectivity, diagnostics));

                AtomicReference<BtClient> clientReference = new AtomicReference<>();
                clientRuntime.getEventSource().onPeerDiscovered(torrentId, ignored -> {
                    Peer peer = ignored.getPeer();
                    if (!peer.isPortUnknown() && !connectivity.isKnownTcpEndpoint(magnet.infoHash(), peer)) {
                        connectivity.onTrackerPeerDiscovered(magnet.infoHash(), peer);
                        int count = trackerPeers.incrementAndGet();
                        event(log, "TRACKER PEER", "count=" + count);
                    }
                });
                clientRuntime.getEventSource().onPeerConnected(torrentId, ignored -> {
                    event(log, "PEER CONNECTED", "transport=TCP");
                    event(log, "BITTORRENT HANDSHAKE ACCEPTED", "infoHash=" + magnet.infoHash());
                    connected.countDown();
                });
                clientRuntime.getEventSource().onMetadataAvailable(torrentId, ignored -> {
                    event(log, "METADATA RECEIVED", "name=" + ignored.getTorrent().getName());
                    metadata.countDown();
                });
                clientRuntime.getEventSource().onPieceVerified(torrentId, ignored -> {
                    event(log, "PIECE RECEIVED", "index=" + ignored.getPieceIndex());
                    piece.countDown();
                    BtClient active = clientReference.get();
                    if (active != null && stoppedAfterFirstPiece.compareAndSet(false, true)) {
                        Thread.startVirtualThread(active::stop);
                    }
                });

                client = Bt.client(clientRuntime).storage(new FileSystemStorage(temporaryDirectory.resolve("ubuntu")))
                        .magnet(magnet.toUri()).sequentialSelector().build();
                clientReference.set(client);
                event(log, "TRACKER ANNOUNCE START", "trackers=" + magnet.trackers().size());
                client.startAsync(state -> {
                    if (metadata.getCount() == 0 && state.getPiecesRemaining() > 0 && !state.getConnectedPeers().isEmpty()
                            && pieceRequest.compareAndSet(false, true)) {
                        event(log, "PIECE REQUEST", "remaining=" + state.getPiecesRemaining());
                    }
                }, 100);
                for (Peer peer : dhtPeers.stream().limit(24).toList()) {
                    connectivity.onDhtPeerDiscovered(magnet.infoHash(), peer);
                }

                long deadlineNanos = System.nanoTime() + TIMEOUT.toNanos();
                assertTrue(awaitUntil(connected, deadlineNanos), failure(log, diagnostics, "PEER CONNECTED"));
                assertTrue(awaitUntil(metadata, deadlineNanos), failure(log, diagnostics, "METADATA RECEIVED"));
                assertTrue(awaitUntil(piece, deadlineNanos), failure(log, diagnostics, "PIECE RECEIVED"));
                event(log, "TRACKER PEERS", "count=" + trackerPeers.get());
                event(log, "PEX PEERS", "count=" + pexPeers.get());

                assertTrue(tcpConnectStarted.get(), failure(log, diagnostics, "PEER CONNECT START"));
                assertTrue(pieceRequest.get(), failure(log, diagnostics, "PIECE REQUEST"));
            }
        } finally {
            if (client != null) client.stop();
            if (clientRuntime != null) clientRuntime.shutdown();
            if (dhtRuntime != null) dhtRuntime.shutdown();
        }
    }

    private static MagnetLink officialMagnet(Torrent torrent) {
        List<String> trackers = torrent.getAnnounceKey().stream().flatMap(key -> key.getTrackerUrls().stream())
                .flatMap(List::stream).distinct().toList();
        String infoHash = HexFormat.of().formatHex(torrent.getTorrentId().getBytes());
        MagnetLink generated = new MagnetLink(infoHash, Optional.of(torrent.getName()), Map.of(), trackers);
        return MagnetLink.parse(generated.toUri());
    }

    private static BtRuntime dhtRuntime() throws Exception {
        Config network = new Config();
        network.setAcceptorPort(0);
        network.setShutdownHookTimeout(Duration.ofSeconds(15));
        DHTConfig dht = new DHTConfig();
        DhtBootstrapNodes.configure(dht, false);
        dht.setShouldUseIPv6(false);
        dht.setListeningPort(freeUdpPort());
        return BtRuntime.builder(network).disableAutomaticShutdown().autoLoadModules()
                .module(new LuffyDhtDiscoveryModule(dht)).build();
    }

    private static List<Peer> lookupDhtPeers(BtRuntime runtime, TorrentId torrentId) {
        try (Stream<Peer> peers = runtime.service(DHTService.class).getPeers(torrentId)) {
            return peers.limit(64).toList();
        }
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static boolean awaitUntil(CountDownLatch latch, long deadlineNanos) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos > 0 && latch.await(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return socket.getLocalPort();
        }
    }

    private static void event(List<String> log, String event, String details) {
        String line = event + " " + details;
        synchronized (log) { log.add(line); }
        System.out.println(line);
    }

    private static String failure(List<String> log, P2pDiagnostics diagnostics, String expected) {
        return "Nao ocorreu " + expected + ". Eventos: " + String.join(" | ", log) + "\nDiagnostico:\n" + diagnostics.snapshot();
    }
}
