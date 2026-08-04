package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerVisualReportTest {
    @Test void rendersConnectedUtpHolePunchSeparatelyFromTcpFailure() throws Exception {
        String infoHash = "0123456789012345678901234567890123456789";
        InetAddress address = InetAddress.getByName("168.181.241.221");
        var tcpEndpoint = new PeerConnectivityManager.PeerEndpoint(address, 43127, PeerConnectivityManager.Transport.TCP);
        var utpEndpoint = new PeerConnectivityManager.PeerEndpoint(address, 43127, PeerConnectivityManager.Transport.UTP);
        var tcpAttempt = new PeerConnectivityManager.SocketAttempt("TCP", PeerConnectivityManager.SocketAddresses.pending(tcpEndpoint),
                Instant.now(), Instant.now(), PeerConnectivityManager.SocketFailure.TIMEOUT, "timeout");
        var utpAttempt = new PeerConnectivityManager.SocketAttempt("uTP/UDP", PeerConnectivityManager.SocketAddresses.pending(utpEndpoint),
                Instant.now(), Instant.now(), PeerConnectivityManager.SocketFailure.NONE, "handshake aceito");
        var tcp = new PeerConnectivityManager.PeerState(infoHash, tcpEndpoint, PeerConnectivityManager.AddressFamily.IPV4,
                PeerConnectivityManager.TransportSupport.UNKNOWN, PeerConnectivityManager.TransportSupport.UNKNOWN,
                PeerConnectivityManager.Strategy.DIRECT_IPV4, PeerConnectivityManager.ConnectionState.DIRECT_CONNECT_FAILED, 1, Instant.now(),
                List.of(PeerConnectivityManager.DiscoveryOrigin.DHT), null, "timeout", tcpAttempt);
        var utp = new PeerConnectivityManager.PeerState(infoHash, utpEndpoint, PeerConnectivityManager.AddressFamily.IPV4,
                PeerConnectivityManager.TransportSupport.UNKNOWN, PeerConnectivityManager.TransportSupport.SUPPORTED,
                PeerConnectivityManager.Strategy.HOLE_PUNCHING, PeerConnectivityManager.ConnectionState.CONNECTED, 1, Instant.now(),
                List.of(PeerConnectivityManager.DiscoveryOrigin.DHT), null, "", utpAttempt);

        String report = PeerVisualReport.render(List.of(tcp, utp), (hash, peer) ->
                new PeerVisualReport.Bep55Status("disponível", "peer C 203.0.113.10:6891", "em andamento", "CONNECT recebido"));

        assertTrue(report.contains("origem: DHT"));
        assertTrue(report.contains("TCP: timeout"));
        assertTrue(report.contains("uTP: conectado"));
        assertTrue(report.contains("BEP 55: disponível"));
        assertTrue(report.contains("rendezvous: peer C 203.0.113.10:6891"));
        assertTrue(report.contains("resultado: CONNECTED VIA UTP HOLE PUNCH"));
    }
}
