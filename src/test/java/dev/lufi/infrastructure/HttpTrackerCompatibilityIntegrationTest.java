package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.Peer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import com.sun.net.httpserver.HttpServer;
import dev.lufi.domain.MagnetLink;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifica o módulo oficial bt-http-tracker-client: o autoLoadModules recebe
 * um magnet HTTP, anuncia e encaminha a lista compacta ao fluxo BitTorrent
 * normal. O mesmo módulo registra os esquemas http e https.
 */
class HttpTrackerCompatibilityIntegrationTest {
    private static final String INFO_HASH = "89abcdef0123456789abcdef0123456789abcdef";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path temporaryDirectory;

    @Test void announcesToHttpTrackerAndForwardsCompactPeersToNormalDiscovery() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer tracker = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        tracker.createContext("/announce", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] response = compactTrackerResponse(loopback, 49_092);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        tracker.start();

        BtRuntime runtime = null;
        BtClient client = null;
        try (PeerConnectivityManager connectivity = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            int listenerPort = freeTcpPort(loopback);
            String trackerUrl = "http://" + loopback.getHostAddress() + ":" + tracker.getAddress().getPort() + "/announce";
            MagnetLink magnet = new MagnetLink(INFO_HASH, Optional.of("http-tracker-test.txt"), Map.of(), List.of(trackerUrl));
            CompletableFuture<Peer> discovered = new CompletableFuture<>();
            TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH));

            runtime = BtRuntime.builder(runtimeConfig(loopback, listenerPort)).disableAutomaticShutdown().autoLoadModules().build();
            runtime.getEventSource().onPeerDiscovered(torrentId, event -> {
                connectivity.onTrackerPeerDiscovered(INFO_HASH, event.getPeer());
                discovered.complete(event.getPeer());
            });
            client = Bt.client(runtime).storage(new FileSystemStorage(temporaryDirectory.resolve("download")))
                    .magnet(magnet.toUri()).build();
            client.startAsync(ignored -> { }, 25);

            Peer peer = discovered.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(loopback, peer.getInetAddress());
            assertEquals(49_092, peer.getPort());
            assertNotNull(request.get(), "O módulo HTTP deve receber um announce.");
            String decodedRequest = URLDecoder.decode(request.get(), StandardCharsets.ISO_8859_1);
            assertTrue(decodedRequest.startsWith("GET /announce?"), request.get());
            assertTrue(decodedRequest.contains("info_hash="), request.get());
            assertTrue(decodedRequest.contains("port=" + listenerPort), request.get());
            assertEquals(1, connectivity.peersFor(INFO_HASH).size());
            assertTrue(connectivity.peersFor(INFO_HASH).getFirst().origins()
                    .contains(PeerConnectivityManager.DiscoveryOrigin.TRACKER));
        } finally {
            if (client != null) client.stop();
            if (runtime != null) runtime.shutdown();
            tracker.stop(0);
        }
    }

    private static Config runtimeConfig(InetAddress address, int port) {
        Config config = new Config();
        config.setAcceptorAddress(address);
        config.setAcceptorPort(port);
        config.setPeerConnectionTimeout(Duration.ofSeconds(2));
        config.setPeerConnectionRetryCount(0);
        return config;
    }

    private static int freeTcpPort(InetAddress address) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(address, 0));
            return socket.getLocalPort();
        }
    }

    private static byte[] compactTrackerResponse(InetAddress address, int port) {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.writeBytes("d8:intervali60e5:peers6:".getBytes(StandardCharsets.ISO_8859_1));
        response.writeBytes(address.getAddress());
        response.write((port >>> 8) & 0xff);
        response.write(port & 0xff);
        response.write('e');
        return response.toByteArray();
    }
}
