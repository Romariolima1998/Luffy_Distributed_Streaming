package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class UtpTransportServiceSessionIsolationTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test void keepsSeparateSessionsWhenDifferentEndpointsUseTheSameConnectionId() throws Exception {
        try (UtpTransportService service = service();
             DatagramSocket first = sender();
             DatagramSocket second = sender()) {
            LinkedBlockingQueue<UtpTransportService.UtpSession> accepted = new LinkedBlockingQueue<>();
            service.setIncomingListener(accepted::offer);

            send(first, service, UtpTransportService.PacketType.SYN, 321, 10, 0, new byte[0]);
            send(second, service, UtpTransportService.PacketType.SYN, 321, 20, 0, new byte[0]);

            UtpTransportService.UtpSession firstSession = accepted.poll(1, TimeUnit.SECONDS);
            UtpTransportService.UtpSession secondSession = accepted.poll(1, TimeUnit.SECONDS);
            assertTrue(firstSession != null && secondSession != null, "os dois endpoints devem receber contextos próprios");
            assertEquals(2, service.activeSessionCount());
            assertEquals(321, firstSession.receiveConnectionId());
            assertEquals(321, secondSession.receiveConnectionId());
            assertFalse(firstSession.remote().equals(secondSession.remote()));
        }
    }

    @Test void treatsDuplicateSynFromTheSameEndpointAsIdempotent() throws Exception {
        try (UtpTransportService service = service(); DatagramSocket sender = sender()) {
            AtomicInteger accepted = new AtomicInteger();
            service.setIncomingListener(ignored -> accepted.incrementAndGet());

            send(sender, service, UtpTransportService.PacketType.SYN, 654, 10, 0, new byte[0]);
            await(() -> accepted.get() == 1, "o primeiro SYN ser aceito");
            send(sender, service, UtpTransportService.PacketType.SYN, 654, 10, 0, new byte[0]);

            Thread.sleep(150);
            assertEquals(1, accepted.get(), "SYN duplicado não pode criar uma segunda sessão");
            assertEquals(1, service.activeSessionCount());
        }
    }

    @Test void ignoresDataFromAnotherEndpointEvenWhenTheConnectionIdMatches() throws Exception {
        try (UtpTransportService service = service();
             DatagramSocket owner = sender();
             DatagramSocket foreign = sender()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            service.setIncomingListener(accepted::complete);
            send(owner, service, UtpTransportService.PacketType.SYN, 700, 10, 0, new byte[0]);
            UtpTransportService.UtpSession session = accepted.get(1, TimeUnit.SECONDS);

            send(foreign, service, UtpTransportService.PacketType.DATA, 700, 11, 0, "BAD".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(100);
            send(owner, service, UtpTransportService.PacketType.DATA, 700, 11, 0, "GOOD".getBytes(StandardCharsets.UTF_8));

            byte[] received = new byte[4];
            assertEquals(4, session.read(received, 0, received.length));
            assertEquals("GOOD", new String(received, StandardCharsets.UTF_8));
        }
    }

    @Test void ignoresStateFromAnotherEndpointEvenWhenTheConnectionIdMatches() throws Exception {
        try (UtpTransportService service = service();
             DatagramSocket expectedRemote = sender();
             DatagramSocket foreign = sender()) {
            CompletableFuture<UtpTransportService.UtpSession> connected = service.connect(endpoint(expectedRemote), 100, 10);

            send(foreign, service, UtpTransportService.PacketType.STATE, 101, 20, 10, new byte[0]);
            Thread.sleep(100);
            assertFalse(connected.isDone(), "STATE de outro endpoint não pode concluir a sessão pendente");
            assertEquals(1, service.pendingSessionCount());

            send(expectedRemote, service, UtpTransportService.PacketType.STATE, 101, 20, 10, new byte[0]);
            UtpTransportService.UtpSession session = connected.get(1, TimeUnit.SECONDS);
            assertTrue(session.isConnected());
            assertEquals(0, service.pendingSessionCount());
            assertEquals(1, service.activeSessionCount());
            session.close();
        }
    }

    @Test void removesAClosedSessionFromTheSessionMap() throws Exception {
        try (UtpTransportService service = service(); DatagramSocket sender = sender()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            service.setIncomingListener(accepted::complete);
            send(sender, service, UtpTransportService.PacketType.SYN, 800, 10, 0, new byte[0]);
            UtpTransportService.UtpSession session = accepted.get(1, TimeUnit.SECONDS);
            assertEquals(1, service.activeSessionCount());

            session.close();
            await(() -> service.activeSessionCount() == 0, "a sessão encerrada ser removida do mapa");
        }
    }

    @Test void expiresAnAbandonedSessionAndCleansTheMap() throws Exception {
        UtpTransportService.SessionLimits limits = new UtpTransportService.SessionLimits(
                8, 8, 4, Duration.ofSeconds(1), Duration.ofMillis(150));
        try (UtpTransportService service = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits);
             DatagramSocket sender = sender()) {
            CompletableFuture<UtpTransportService.UtpSession> accepted = new CompletableFuture<>();
            service.setIncomingListener(accepted::complete);
            send(sender, service, UtpTransportService.PacketType.SYN, 900, 10, 0, new byte[0]);
            accepted.get(1, TimeUnit.SECONDS);

            await(() -> service.activeSessionCount() == 0, "a sessão abandonada expirar e sair do mapa");
        }
    }

    @Test void enforcesThePendingSessionLimit() throws Exception {
        UtpTransportService.SessionLimits limits = new UtpTransportService.SessionLimits(
                8, 1, 4, Duration.ofSeconds(2), Duration.ofSeconds(2));
        try (UtpTransportService service = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits);
             DatagramSocket first = sender();
             DatagramSocket second = sender()) {
            CompletableFuture<UtpTransportService.UtpSession> firstPending = service.connect(endpoint(first), 1_000, 10);
            CompletableFuture<UtpTransportService.UtpSession> rejected = service.connect(endpoint(second), 1_001, 10);

            assertFalse(firstPending.isDone());
            assertTrue(rejected.isCompletedExceptionally());
            assertEquals(1, service.pendingSessionCount());
        }
    }

    @Test void enforcesTheInboundSynLimitPerEndpointWithoutReplacingTheLiveSession() throws Exception {
        UtpTransportService.SessionLimits limits = new UtpTransportService.SessionLimits(
                8, 8, 1, Duration.ofSeconds(2), Duration.ofSeconds(2));
        try (UtpTransportService service = new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics(), limits);
             DatagramSocket sender = sender()) {
            AtomicInteger accepted = new AtomicInteger();
            service.setIncomingListener(ignored -> accepted.incrementAndGet());

            send(sender, service, UtpTransportService.PacketType.SYN, 1_200, 10, 0, new byte[0]);
            await(() -> accepted.get() == 1, "o primeiro SYN ser aceito");
            send(sender, service, UtpTransportService.PacketType.SYN, 1_201, 10, 0, new byte[0]);

            Thread.sleep(150);
            assertEquals(1, accepted.get());
            assertEquals(1, service.activeSessionCount());
        }
    }

    private static UtpTransportService service() throws IOException {
        return new UtpTransportService(LOOPBACK, 0, new P2pDiagnostics());
    }

    private static DatagramSocket sender() throws IOException {
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
        sender.send(new DatagramPacket(bytes.array(), bytes.position(), targetEndpoint(target)));
    }

    private static InetSocketAddress targetEndpoint(UtpTransportService service) {
        return new InetSocketAddress(service.localAddress(), service.localPort());
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
