package dev.lufi.infrastructure;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.ConnectionResult;
import bt.net.IConnectionSource;
import bt.net.InetPeer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import dev.lufi.infrastructure.bootstrap.OfficialBootstrapSwarm;
import dev.lufi.infrastructure.identity.ConnectedLuffyRegistry;
import dev.lufi.infrastructure.identity.LuffyIdentityExtension;
import dev.lufi.infrastructure.identity.LuffyIdentityMessage;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import dev.lufi.infrastructure.identity.LuffyNodeIdentity;
import dev.lufi.infrastructure.identity.LuffyPeerCapabilities;
import dev.lufi.infrastructure.overlay.FindNodeService;
import dev.lufi.infrastructure.overlay.LuffyRouteExtension;
import dev.lufi.infrastructure.overlay.RouteSearchResult;
import dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtension;
import dev.lufi.infrastructure.rendezvous.OverlayPrivacyPolicy;
import dev.lufi.infrastructure.rendezvous.LuffyRendezvousMessage;
import dev.lufi.infrastructure.rendezvous.RendezvousSession;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prova local da arquitetura completa: as extensões {@code lf_route} e
 * {@code lf_rendezvous} usam conexões BitTorrent reais já estabelecidas, e
 * somente A e B recebem o torrent de conteúdo sobre a ponte uTP/bt-core.
 */
class OverlayLocalIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @TempDir Path temporaryDirectory;

    @Test void downloadsTesteTxtThroughAcxzOverlayWithoutContentOnIntermediatePeers() throws Exception {
        Path seedDirectory = Files.createDirectories(temporaryDirectory.resolve("seed-b"));
        Path downloadDirectory = Files.createDirectories(temporaryDirectory.resolve("download-a"));
        Path contentSource = seedDirectory.resolve("teste.txt");
        Files.writeString(contentSource, "OLA LUFFY", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent content = new TorrentMetainfoGenerator()
                .publish(contentSource, temporaryDirectory.resolve("content-metainfo"));
        TorrentId contentTorrentId = TorrentId.fromBytes(HexFormat.of().parseHex(content.magnet().infoHash()));

        Path controlSource = seedDirectory.resolve("control-link.txt");
        Files.writeString(controlSource, "CONTROLE", StandardCharsets.UTF_8);
        TorrentMetainfoGenerator.PublishedTorrent otherTorrent = new TorrentMetainfoGenerator()
                .publish(controlSource, temporaryDirectory.resolve("control-metainfo"));
        TorrentId otherTorrentId = TorrentId.fromBytes(HexFormat.of().parseHex(otherTorrent.magnet().infoHash()));

        CompletableFuture<Void> downloaded = new CompletableFuture<>();
        try (Node a = Node.open("A", 1, temporaryDirectory.resolve("a"));
             Node c = Node.open("C", 2, temporaryDirectory.resolve("c"));
             Node x = Node.open("X", 3, temporaryDirectory.resolve("x"));
             Node z = Node.open("Z", 4, temporaryDirectory.resolve("z"));
             Node b = Node.open("B", 5, temporaryDirectory.resolve("b"))) {
            try {
                // A-C-X-Z são vizinhos reais no torrent oficial Olá Luffy.
                a.startBootstrap();
                c.startBootstrap();
                x.startBootstrap();
                z.startBootstrap();
                await(() -> a.hasTorrent(OfficialBootstrapSwarm.loadAndValidate().torrentId())
                                && c.hasTorrent(OfficialBootstrapSwarm.loadAndValidate().torrentId())
                                && x.hasTorrent(OfficialBootstrapSwarm.loadAndValidate().torrentId())
                                && z.hasTorrent(OfficialBootstrapSwarm.loadAndValidate().torrentId()),
                        Duration.ofSeconds(10), "os cinco nós iniciarem o swarm Olá Luffy", diagnostics(a, c, x, z, b));
                TorrentId bootstrapTorrent = OfficialBootstrapSwarm.loadAndValidate().torrentId();
                a.connectTcpTo(bootstrapTorrent, c).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                c.connectTcpTo(bootstrapTorrent, x).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                x.connectTcpTo(bootstrapTorrent, z).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                await(() -> a.isConnectedTo(c) && c.isConnectedTo(x) && x.isConnectedTo(z), TIMEOUT,
                        "A-C-X-Z negociarem lf_identity/lf_route", diagnostics(a, c, x, z, b));

                // Z-B tem uma conexão BitTorrent real, porém em outro torrent;
                // isto testa que ConnectedLuffyRegistry não depende do swarm bootstrap.
                b.startClient(otherTorrent.torrentFile(), seedDirectory, null);
                z.startClient(otherTorrent.torrentFile(), temporaryDirectory.resolve("z-control"), null);
                await(() -> b.hasTorrent(otherTorrentId) && z.hasTorrent(otherTorrentId), Duration.ofSeconds(10),
                        "Z e B iniciarem o torrent de controle separado", diagnostics(a, c, x, z, b));
                z.connectTcpTo(otherTorrentId, b).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                await(() -> z.isConnectedTo(b), TIMEOUT, "Z reconhecer B em outro torrent", diagnostics(a, c, x, z, b));
                assertFalse(a.isConnectedTo(b), "a conexão direta A-B deve permanecer bloqueada antes do fallback");

                // B semeia e A conhece o mesmo metainfo, mas nenhum socket TCP
                // do torrent de conteúdo é criado entre eles.
                b.startClient(content.torrentFile(), seedDirectory, null);
                a.startClient(content.torrentFile(), downloadDirectory, downloaded);
                await(() -> b.hasTorrent(contentTorrentId) && a.hasTorrent(contentTorrentId), Duration.ofSeconds(10),
                        "A e B registrarem o torrent de conteúdo", diagnostics(a, c, x, z, b));
                assertFalse(c.hasTorrent(contentTorrentId));
                assertFalse(x.hasTorrent(contentTorrentId));
                assertFalse(z.hasTorrent(contentTorrentId));

                // FIND_NODE percorre A -> C -> X -> Z. Z encontra B porque a
                // conexão Z-B vive em outro torrent, e devolve NODE_FOUND pela rota inversa.
                FindNodeService.RouteSearch search = a.route.startFindNode(b.identity.nodeId(), content.magnet().infoHash());
                RouteSearchResult routeResult = search.result().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                RouteSearchResult.NodeFound found = assertInstanceOf(RouteSearchResult.NodeFound.class, routeResult);
                assertEquals(z.identity.nodeId(), found.rendezvousNodeId());
                assertTrue(c.route.routePaths().find(search.requestId(), Instant.now()).isPresent(),
                        "C deve preservar os saltos anterior e seguinte da rota FIND_NODE");
                assertTrue(x.route.routePaths().find(search.requestId(), Instant.now()).isPresent(),
                        "X deve preservar os saltos anterior e seguinte da rota FIND_NODE");
                assertTrue(z.route.routePaths().find(search.requestId(), Instant.now()).isPresent(),
                        "Z deve preservar o último salto para a resposta NODE_FOUND");

                // A solicita o rendezvous por Z. Z prepara B pela conexão Z-B;
                // os dois extremos iniciam uTP e o bridge só considera sucesso
                // após o aceite do handshake pelo bt-core.
                Optional<RendezvousSession> session = a.rendezvous.request(search.requestId(), b.identity.nodeId(),
                        found.rendezvousNodeId(), contentTorrentId);
                assertTrue(session.isPresent(), "A deve iniciar uma sessão lf_rendezvous com endpoint uTP confirmado");
                await(downloaded::isDone, TIMEOUT, "A baixar teste.txt por uTP após o rendezvous", diagnostics(a, c, x, z, b));
                downloaded.get(1, TimeUnit.SECONDS);

                assertEquals("OLA LUFFY", Files.readString(downloadDirectory.resolve("teste.txt"), StandardCharsets.UTF_8));
                String aLog = a.diagnostics.snapshot();
                String bLog = b.diagnostics.snapshot();
                assertTrue(aLog.contains("uTP BITTORRENT INCOMING START: infoHash=" + content.magnet().infoHash())
                                || aLog.contains("uTP BITTORRENT HANDSHAKE START: infoHash=" + content.magnet().infoHash()),
                        "A deve aceitar ou iniciar o handshake BitTorrent sobre o uTP direto");
                assertTrue(aLog.contains("uTP BITTORRENT PEER REGISTERED: infoHash=" + content.magnet().infoHash())
                                || bLog.contains("uTP BITTORRENT PEER REGISTERED: infoHash=" + content.magnet().infoHash()),
                        "o bt-core deve registrar o peer aceito antes das pieces");
                assertTrue(bLog.contains("uTP BITTORRENT"));

                // C, X e Z somente carregam os dois torrents de controle. Sem
                // TorrentRegistry do conteúdo, eles não podem receber handshake,
                // metadata, requests, pieces ou bytes de teste.txt.
                assertFalse(c.hasTorrent(contentTorrentId));
                assertFalse(x.hasTorrent(contentTorrentId));
                assertFalse(z.hasTorrent(contentTorrentId));
                assertFalse(c.diagnostics.snapshot().contains("infoHash=" + content.magnet().infoHash()));
                assertFalse(x.diagnostics.snapshot().contains("infoHash=" + content.magnet().infoHash()));
                assertFalse(z.diagnostics.snapshot().contains("infoHash=" + content.magnet().infoHash()));
            } finally {
                a.stopAllClients();
                c.stopAllClients();
                x.stopAllClients();
                z.stopAllClients();
                b.stopAllClients();
            }
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description, Supplier<String> context) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Tempo limite aguardando " + description + context.get());
    }

    private static Supplier<String> diagnostics(Node a, Node c, Node x, Node z, Node b) {
        return () -> "\n--- A ---\n" + a.diagnostics.snapshot() + "\n--- C ---\n" + c.diagnostics.snapshot()
                + "\n--- X ---\n" + x.diagnostics.snapshot() + "\n--- Z ---\n" + z.diagnostics.snapshot()
                + "\n--- B ---\n" + b.diagnostics.snapshot();
    }

    private static final class Node implements AutoCloseable {
        private final String name;
        private final InetAddress address = InetAddress.getLoopbackAddress();
        private final P2pDiagnostics diagnostics = new P2pDiagnostics();
        private final PeerConnectivityManager connectivity = new PeerConnectivityManager(diagnostics, ignored -> { });
        private final UtpBitTorrentBridge bridge = new UtpBitTorrentBridge(diagnostics, connectivity);
        private final Bep55HolePunchAgent agent = new Bep55HolePunchAgent(diagnostics, bridge);
        private final ConnectedLuffyRegistry registry = new ConnectedLuffyRegistry();
        private final LuffyNodeIdentity identity;
        private final LuffyIdentityExtension identityExtension;
        private final LuffyRouteExtension route;
        private final LuffyRendezvousExtension rendezvous;
        private final BtRuntime runtime;
        private final UtpTransportService utp;
        private final int tcpPort;
        private final Path root;
        private final java.util.List<BtClient> clients = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Node(String name, int nodeNumber, Path root) throws Exception {
            this.name = name;
            this.root = Files.createDirectories(root);
            this.identity = new LuffyNodeIdentity(node(nodeNumber), Instant.parse("2026-08-04T18:00:00Z"));
            this.identityExtension = new LuffyIdentityExtension(identity,
                    () -> new LuffyIdentityMessage(LuffyIdentityMessage.PROTOCOL_VERSION, identity.nodeId(), "Luffy-test/0.1.0",
                            true, true, true, true), diagnostics, registry);
            this.route = new LuffyRouteExtension(identity,
                    () -> new LuffyPeerCapabilities(1, identity.nodeId(), "Luffy-test/0.1.0", true, true, true, true),
                    OfficialBootstrapSwarm.loadAndValidate().torrentId(), registry, diagnostics);
            this.utp = new UtpTransportService(address, 0, diagnostics);
            this.rendezvous = new LuffyRendezvousExtension(identity, route.routePaths(), registry,
                    () -> Optional.of(new LuffyRendezvousMessage.RendezvousEndpoint(address, utp.localPort())),
                    (torrentId, endpoint) -> agent.startDistributedHolePunch(torrentId, endpoint.address(), endpoint.port()), diagnostics,
                    new dev.lufi.infrastructure.security.AbuseProtectionService(), OverlayPrivacyPolicy.loopbackTestOnly());
            agent.setExtensionHandshakeListener((key, capabilities) -> identityExtension.onExtendedHandshake(key, capabilities));

            Config config = new Config();
            config.setAcceptorAddress(address);
            tcpPort = freeTcpPort(address);
            config.setAcceptorPort(tcpPort);
            config.setPeerHandshakeTimeout(Duration.ofSeconds(5));
            config.setPeerConnectionTimeout(Duration.ofSeconds(5));
            config.setPeerConnectionRetryCount(0);
            // A topologia deste teste é deliberada. A e Z aceitam somente um
            // vizinho no bootstrap; C e X aceitam exatamente os dois saltos
            // adjacentes. Assim, PEX não pode criar atalhos A-Z ou C-Z.
            config.setMaxPeerConnectionsPerTorrent(nodeNumber == 2 || nodeNumber == 3 ? 2 : 1);
            config.setMaxPeerConnections(4);
            config.setPeerDiscoveryInterval(Duration.ofHours(1));
            runtime = BtRuntime.builder(config).disableAutomaticShutdown()
                    .module(new Bep55HolePunchModule(agent)).module(identityExtension).module(route).module(rendezvous).build();
            assertTrue(BtConnectionLifecycleInstrumentation.install(runtime, connectivity, diagnostics));
            bridge.attach(runtime, utp);
        }

        static Node open(String name, int nodeNumber, Path root) throws Exception { return new Node(name, nodeNumber, root); }

        void startBootstrap() throws Exception {
            Path bootstrapFile = root.resolve("ola-luffy.txt");
            try (var input = Node.class.getClassLoader().getResourceAsStream(OfficialBootstrapSwarm.TEXT_RESOURCE)) {
                if (input == null) throw new IllegalStateException("recurso ola-luffy.txt ausente");
                Files.write(bootstrapFile, input.readAllBytes());
            }
            try (var input = Node.class.getClassLoader().getResourceAsStream(OfficialBootstrapSwarm.TORRENT_RESOURCE)) {
                if (input == null) throw new IllegalStateException("recurso ola-luffy.torrent ausente");
                Path torrent = root.resolve("ola-luffy.torrent");
                Files.write(torrent, input.readAllBytes());
                startClient(torrent, root, null);
            }
        }

        void startClient(Path torrentFile, Path storageDirectory, CompletableFuture<Void> completed) throws Exception {
            Files.createDirectories(storageDirectory);
            var builder = Bt.client(runtime).storage(new FileSystemStorage(storageDirectory))
                    .torrent(torrentFile.toUri().toURL());
            if (completed != null) builder.afterDownloaded(ignored -> completed.complete(null));
            BtClient client = builder.build();
            clients.add(client);
            client.startAsync(ignored -> { }, 25).whenComplete((ignored, error) -> {
                if (error != null) diagnostics.log("TEST CLIENT " + name + " FINISHED: " + error);
            });
        }

        boolean hasTorrent(TorrentId torrentId) {
            return runtime.service(bt.torrent.TorrentRegistry.class).getTorrent(torrentId).isPresent();
        }

        boolean isConnectedTo(Node peer) {
            return registry.hasDirectConnection(peer.identity.nodeId());
        }

        CompletableFuture<Void> connectTcpTo(TorrentId torrentId, Node peer) {
            return runtime.service(IConnectionSource.class)
                    .getConnectionAsync(InetPeer.build(peer.address, peer.tcpPort), torrentId)
                    .thenAccept(result -> requireSuccess(result, name + " -> " + peer.name));
        }

        void stopAllClients() {
            clients.forEach(BtClient::stop);
            clients.clear();
        }

        @Override public void close() throws Exception {
            bridge.close();
            await(() -> bridge.activeLoopbackPairCount() == 0 && bridge.activePumpTaskCount() == 0
                            && bridge.pendingConnectionCount() == 0,
                    Duration.ofSeconds(5), "os recursos uTP da ponte " + name + " encerrarem", diagnostics::snapshot);
            rendezvous.close();
            route.close();
            connectivity.close();
            runtime.shutdown();
        }

        private static void requireSuccess(ConnectionResult result, String direction) {
            if (!result.isSuccess()) {
                throw new IllegalStateException("bt-core recusou conexão TCP " + direction + ": "
                        + result.getMessage().orElse("sem detalhe"), result.getError().orElse(null));
            }
        }

        private static int freeTcpPort(InetAddress address) throws Exception {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(address, 0));
                return socket.getLocalPort();
            }
        }
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }
}
