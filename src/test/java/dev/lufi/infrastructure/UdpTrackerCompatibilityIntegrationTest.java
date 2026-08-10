package dev.lufi.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.TorrentId;
import bt.net.Peer;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import dev.lufi.domain.MagnetLink;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifica a interoperabilidade UDP tracker sem depender da Internet: o
 * bt-core recebe um magnet com trackers repetidos, faz o announce BEP 15,
 * interpreta uma resposta com peers compactos e emite PeerDiscoveredEvent.
 */
class UdpTrackerCompatibilityIntegrationTest {
    private static final String INFO_HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path temporaryDirectory;

    @Test void announcesToUdpTrackerAndForwardsCompactPeersToTheNormalDiscoveryFlow() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (LocalUdpTracker tracker = new LocalUdpTracker(loopback, 49_091);
             PeerConnectivityManager connectivity = new PeerConnectivityManager(new P2pDiagnostics(), ignored -> { })) {
            String firstTracker = tracker.uri("announce");
            String secondTracker = tracker.uri("backup");
            String rawMagnet = "magnet:?xt=urn:btih:" + INFO_HASH + "&dn=tracker-test.txt"
                    + "&tr=" + encode(firstTracker) + "&tr=" + encode(secondTracker);
            MagnetLink magnet = MagnetLink.parse(rawMagnet);

            assertEquals(List.of(firstTracker, secondTracker), magnet.trackers());
            assertEquals(magnet.trackers(), MagnetLink.parse(magnet.toUri()).trackers());

            TorrentId torrentId = TorrentId.fromBytes(HexFormat.of().parseHex(INFO_HASH));
            CompletableFuture<Peer> discovered = new CompletableFuture<>();
            BtRuntime runtime = null;
            BtClient client = null;
            try {
                int listenerPort = freeTcpPort(loopback);
                runtime = BtRuntime.builder(runtimeConfig(loopback, listenerPort)).disableAutomaticShutdown().autoLoadModules().build();
                BtRuntime activeRuntime = runtime;
                activeRuntime.getEventSource().onPeerDiscovered(torrentId, event -> {
                    Peer peer = event.getPeer();
                    connectivity.onTrackerPeerDiscovered(INFO_HASH, peer);
                    discovered.complete(peer);
                });
                client = Bt.client(activeRuntime).storage(new FileSystemStorage(temporaryDirectory.resolve("download")))
                        .magnet(magnet.toUri()).build();
                client.startAsync(ignored -> { }, 25);

                LocalUdpTracker.Announce announce = tracker.awaitAnnounce();
                assertArrayEquals(HexFormat.of().parseHex(INFO_HASH), announce.infoHash());
                assertEquals(listenerPort, announce.port(), "o announce UDP deve informar a porta TCP do cliente");

                Peer peer = discovered.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertEquals(loopback, peer.getInetAddress());
                assertEquals(tracker.compactPeerPort(), peer.getPort());
                assertEquals(1, connectivity.peersFor(INFO_HASH).size());
                assertTrue(connectivity.peersFor(INFO_HASH).getFirst().origins()
                        .contains(PeerConnectivityManager.DiscoveryOrigin.TRACKER));
            } finally {
                if (client != null) client.stop();
                if (runtime != null) runtime.shutdown();
            }
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

    private static int freeTcpPort(InetAddress address) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(address, 0));
            return socket.getLocalPort();
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Implementacao minima e local do protocolo UDP tracker (BEP 15), usada somente no teste. */
    private static final class LocalUdpTracker implements AutoCloseable {
        private static final long PROTOCOL_ID = 0x41727101980L;
        private static final long CONNECTION_ID = 0x0102030405060708L;
        private final DatagramSocket socket;
        private final int compactPeerPort;
        private final CompletableFuture<Announce> announce = new CompletableFuture<>();
        private volatile boolean running = true;

        private LocalUdpTracker(InetAddress address, int compactPeerPort) throws IOException {
            this.socket = new DatagramSocket(new InetSocketAddress(address, 0));
            this.compactPeerPort = compactPeerPort;
            Thread.ofVirtual().start(this::serve);
        }

        private String uri(String path) {
            return "udp://" + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort() + "/" + path;
        }

        private int compactPeerPort() { return compactPeerPort; }

        private Announce awaitAnnounce() throws Exception {
            return announce.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        private void serve() {
            byte[] receive = new byte[2_048];
            while (running && !announce.isDone()) {
                try {
                    DatagramPacket packet = new DatagramPacket(receive, receive.length);
                    socket.receive(packet);
                    ByteBuffer request = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()).order(ByteOrder.BIG_ENDIAN);
                    if (packet.getLength() == 16 && request.getLong() == PROTOCOL_ID && request.getInt() == 0) {
                        sendConnectResponse(packet.getSocketAddress(), request.getInt());
                    } else if (packet.getLength() >= 98 && request.getLong() == CONNECTION_ID && request.getInt() == 1) {
                        int transactionId = request.getInt();
                        byte[] infoHash = new byte[20];
                        request.get(infoHash);
                        request.position(96);
                        int port = Short.toUnsignedInt(request.getShort());
                        announce.complete(new Announce(infoHash, port));
                        sendAnnounceResponse(packet.getSocketAddress(), transactionId);
                    }
                } catch (IOException error) {
                    if (running) announce.completeExceptionally(error);
                }
            }
        }

        private void sendConnectResponse(java.net.SocketAddress recipient, int transactionId) throws IOException {
            ByteBuffer response = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
            response.putInt(0).putInt(transactionId).putLong(CONNECTION_ID);
            send(recipient, response.array());
        }

        private void sendAnnounceResponse(java.net.SocketAddress recipient, int transactionId) throws IOException {
            byte[] address = socket.getLocalAddress().getAddress();
            ByteBuffer response = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
            response.putInt(1).putInt(transactionId).putInt(60).putInt(0).putInt(1);
            response.put(address).putShort((short) compactPeerPort);
            send(recipient, response.array());
        }

        private void send(java.net.SocketAddress recipient, byte[] data) throws IOException {
            socket.send(new DatagramPacket(data, data.length, recipient));
        }

        @Override public void close() {
            running = false;
            socket.close();
        }

        private record Announce(byte[] infoHash, int port) { }
    }
}
