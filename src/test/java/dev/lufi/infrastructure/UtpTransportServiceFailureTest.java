package dev.lufi.infrastructure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import dev.lufi.infrastructure.security.AbuseProtectionConfig;
import dev.lufi.infrastructure.security.AbuseProtectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class UtpTransportServiceFailureTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test void expiresAnUnansweredSynAndRemovesThePendingSession() throws Exception {
        UtpTransportService.SessionLimits limits = limits(Duration.ofMillis(180), Duration.ofSeconds(1));
        try (UtpTransportService transport = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits);
             DatagramSocket unreachablePeer = socket()) {
            CompletableFuture<UtpTransportService.UtpSession> connection = transport.connect(endpoint(unreachablePeer), 410, 20);

            assertThrows(ExecutionException.class, () -> connection.get(2, TimeUnit.SECONDS));
            await(() -> transport.pendingSessionCount() == 0, "o SYN sem resposta expirar");
            assertTrue(transport.activeSessionCount() == 0);
        }
    }

    @Test void resetFromTheCorrectPeerClosesAndCleansTheSession() throws Exception {
        try (UtpTransportService transport = service(); DatagramSocket peer = socket()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            transport.setIncomingListener(accepted::complete);
            send(peer, transport, UtpTransportService.PacketType.SYN, 720, 10, 0, new byte[0]);
            UtpTransportService.UtpSession session = accepted.get(1, TimeUnit.SECONDS);

            send(peer, transport, UtpTransportService.PacketType.RESET, 720, 11, 10, new byte[0]);

            await(() -> transport.activeSessionCount() == 0, "o RESET remover a sessao");
            assertFalse(session.isConnected());
        }
    }

    @Test void localSessionCloseNotifiesThePeerAndCleansBothContexts() throws Exception {
        try (UtpTransportService left = service(); UtpTransportService right = service()) {
            CompletableFuture<UtpTransportService.UtpSession> incoming = new CompletableFuture<>();
            right.setIncomingListener(incoming::complete);
            UtpTransportService.UtpSession outgoing = left.connect(new InetSocketAddress(LOOPBACK, right.localPort()))
                    .get(1, TimeUnit.SECONDS);
            incoming.get(1, TimeUnit.SECONDS);

            outgoing.close();

            await(() -> left.activeSessionCount() == 0, "a sessao local ser removida");
            await(() -> right.activeSessionCount() == 0, "o peer receber RESET e limpar sua sessao");
        }
    }

    @Test void peerTransportClosingEventuallyExpiresTheOtherSideWithoutLeakingContext() throws Exception {
        UtpTransportService.SessionLimits limits = limits(Duration.ofSeconds(1), Duration.ofMillis(180));
        try (UtpTransportService left = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits);
             UtpTransportService right = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits)) {
            CompletableFuture<UtpTransportService.UtpSession> incoming = new CompletableFuture<>();
            right.setIncomingListener(incoming::complete);
            UtpTransportService.UtpSession outgoing = left.connect(new InetSocketAddress(LOOPBACK, right.localPort()))
                    .get(1, TimeUnit.SECONDS);
            incoming.get(1, TimeUnit.SECONDS);

            right.close();

            await(() -> left.activeSessionCount() == 0, "a sessao do peer desconectado expirar");
            assertFalse(outgoing.isConnected());
        }
    }

    @Test void invalidUdpPacketIsIgnoredAndDoesNotPreventTheNextSyn() throws Exception {
        try (UtpTransportService transport = service(); DatagramSocket peer = socket()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            transport.setIncomingListener(accepted::complete);
            peer.send(new DatagramPacket(new byte[] { 0x01, 0x02, 0x03 }, 3,
                    new InetSocketAddress(LOOPBACK, transport.localPort())));

            send(peer, transport, UtpTransportService.PacketType.SYN, 880, 10, 0, new byte[0]);

            assertTrue(accepted.get(1, TimeUnit.SECONDS).isConnected());
        }
    }

    @Test void limitsInboundSynsByAddressAndKeepsTheFirstSession() throws Exception {
        UtpTransportService.SessionLimits limits = new UtpTransportService.SessionLimits(8, 8, 8,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        AbuseProtectionService protection = new AbuseProtectionService(new AbuseProtectionConfig(
                20, 20, 2, 2, 2, 512, 6, 8, 1));
        try (UtpTransportService transport = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits, protection);
             DatagramSocket firstPeer = socket(); DatagramSocket secondPeer = socket()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            transport.setIncomingListener(accepted::complete);

            send(firstPeer, transport, UtpTransportService.PacketType.SYN, 991, 10, 0, new byte[0]);
            assertTrue(accepted.get(1, TimeUnit.SECONDS).isConnected());
            send(secondPeer, transport, UtpTransportService.PacketType.SYN, 992, 10, 0, new byte[0]);

            await(() -> transport.activeSessionCount() == 1, "o primeiro contexto uTP permanecer ativo");
            await(() -> !protection.isAllowed(AbuseProtectionService.peerKey(LOOPBACK), java.time.Instant.now()),
                    "a origem excedente receber bloqueio temporario");
        }
    }

    private static UtpTransportService service() throws IOException {
        return new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics());
    }

    private static UtpTransportService.SessionLimits limits(Duration pending, Duration idle) {
        return new UtpTransportService.SessionLimits(8, 8, 4, pending, idle);
    }

    private static DatagramSocket socket() throws IOException {
        return new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
    }

    private static InetSocketAddress endpoint(DatagramSocket socket) {
        return new InetSocketAddress(LOOPBACK, socket.getLocalPort());
    }

    private static void send(DatagramSocket sender, UtpTransportService target, UtpTransportService.PacketType type,
                             int connectionId, int sequence, int acknowledgement, byte[] payload) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(20 + payload.length);
        bytes.put((byte) ((packetTypeId(type) << 4) | 1));
        bytes.put((byte) 0);
        bytes.putShort((short) connectionId);
        bytes.putInt(0);
        bytes.putInt(0);
        bytes.putInt(64 * 1024);
        bytes.putShort((short) sequence);
        bytes.putShort((short) acknowledgement);
        bytes.put(payload);
        sender.send(new DatagramPacket(bytes.array(), bytes.position(),
                new InetSocketAddress(target.localAddress(), target.localPort())));
    }

    private static int packetTypeId(UtpTransportService.PacketType type) {
        return switch (type) {
            case DATA -> 0;
            case FIN -> 1;
            case STATE -> 2;
            case RESET -> 3;
            case SYN -> 4;
        };
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Tempo limite aguardando " + description);
    }
}
