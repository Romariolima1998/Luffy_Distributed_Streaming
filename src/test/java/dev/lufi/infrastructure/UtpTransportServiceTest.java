package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UtpTransportServiceTest {
    @Test void transfersBytesInBothDirectionsOverUdp() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            P2pDiagnostics leftDiagnostics = new P2pDiagnostics();
            P2pDiagnostics rightDiagnostics = new P2pDiagnostics();
            try (UtpTransportService left = new UtpTransportService(loopback, 0, leftDiagnostics);
                 UtpTransportService right = new UtpTransportService(loopback, 0, rightDiagnostics)) {
                CompletableFuture<UtpTransportService.UtpSession> incoming = new CompletableFuture<>();
                right.setIncomingListener(incoming::complete);

                UtpTransportService.UtpSession client = left.connect(new InetSocketAddress(loopback, right.localPort())).get(3, TimeUnit.SECONDS);
                UtpTransportService.UtpSession server = incoming.get(3, TimeUnit.SECONDS);

                byte[] hello = "OLA LUFFY".getBytes(StandardCharsets.UTF_8);
                client.write(hello, 0, hello.length);
                byte[] received = new byte[hello.length];
                assertEquals(hello.length, server.read(received, 0, received.length));
                assertEquals("OLA LUFFY", new String(received, StandardCharsets.UTF_8));

                byte[] reply = "UTP OK".getBytes(StandardCharsets.UTF_8);
                server.write(reply, 0, reply.length);
                byte[] receivedReply = new byte[reply.length];
                assertEquals(reply.length, client.read(receivedReply, 0, receivedReply.length));
                assertEquals("UTP OK", new String(receivedReply, StandardCharsets.UTF_8));
                assertEquals(true, leftDiagnostics.snapshot().contains("[UTP] OUTBOUND PACKET"));
                assertEquals(true, leftDiagnostics.snapshot().contains("[UTP] INBOUND PACKET"));
            }
        });
    }
}
