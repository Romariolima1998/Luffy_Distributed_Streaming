package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ObservationSource;
import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.Transport;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousEndpointSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T19:00:00Z");

    @Test void selectsOnlyConfirmedPublicAndUnexpiredUtpEndpoint() throws Exception {
        ObservedEndpoint selected = endpoint("203.0.113.15", 43_127, true, NOW.plusSeconds(30));
        assertEquals(43_127, RendezvousEndpointSelector.selectConfirmedUtp(List.of(
                endpoint("203.0.113.14", 43_126, false, NOW.plusSeconds(30)),
                endpoint("203.0.113.13", 43_125, true, NOW.minusSeconds(1)), selected), NOW).orElseThrow().port());
    }

    @Test void rejectsPrivateOrExpiredObservation() throws Exception {
        assertTrue(RendezvousEndpointSelector.selectConfirmedUtp(List.of(
                endpoint("192.168.1.5", 6_891, true, NOW.plusSeconds(30)),
                endpoint("203.0.113.15", 43_127, true, NOW.minusSeconds(1))), NOW).isEmpty());
    }

    private static ObservedEndpoint endpoint(String address, int port, boolean confirmed, Instant expiry) throws Exception {
        return new ObservedEndpoint(InetAddress.getByName(address), port, Transport.UTP, ObservationSource.EXTERNAL_PROBE,
                NOW.minusSeconds(1), expiry, confirmed);
    }
}
