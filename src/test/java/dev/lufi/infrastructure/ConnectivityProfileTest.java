package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityProfileTest {
    @Test void activatesIpv6DhtOnlyForGlobalUnicastIpv6() throws Exception {
        ConnectivityProfile linkLocal = new ConnectivityProfile(true, 6891, 49001, Optional.empty(),
                Optional.of((java.net.Inet6Address) InetAddress.getByName("fe80::1")), Optional.empty(), Optional.empty(), false);
        ConnectivityProfile global = new ConnectivityProfile(true, 6891, 49001, Optional.empty(),
                Optional.of((java.net.Inet6Address) InetAddress.getByName("2606:4700:4700::1111")), Optional.empty(), Optional.empty(), false);

        assertTrue(!linkLocal.useIpv6Dht());
        assertTrue(global.useIpv6Dht());
    }

    @Test void neverExposesPrivateLanAddressAsObservedPeerEndpoint() throws Exception {
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.empty(), Optional.empty(),
                Optional.of(new ConnectivityProfile.PortMapping("UPnP", "TCP", InetAddress.getByName("192.168.1.1"), 6891, 6891, 3600)),
                Optional.empty(), false);

        assertTrue(profile.ipv4PublicPeerEndpoint().isEmpty());
        assertTrue(profile.publicPeerEndpoint().isEmpty());
    }

    @Test void preservesRouterSelectedExternalPortButDoesNotAnnounceBeforeInboundConfirmation() throws Exception {
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.empty(), Optional.empty(),
                Optional.of(new ConnectivityProfile.PortMapping("UPnP", "TCP", InetAddress.getByName("203.0.113.12"), 43817, 6891, 3600)),
                Optional.empty(), false);

        ConnectivityProfile.PublicPeerEndpoint endpoint = profile.ipv4PublicPeerEndpoint().orElseThrow();
        assertEquals("203.0.113.12", endpoint.address().getHostAddress());
        assertEquals(43817, endpoint.port());
        assertTrue(profile.ipv4DhtAnnouncePort().isEmpty());
        assertEquals(ConnectivityProfile.DhtAnnouncementMode.OUTBOUND_ONLY_FIREWALLED, profile.dhtAnnouncement().mode());
        assertTrue(profile.publicPeerEndpoint().isEmpty());
    }

    @Test void doesNotPromoteNatMappingToConfirmedInboundRoute() throws Exception {
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.empty(), Optional.empty(),
                Optional.of(new ConnectivityProfile.PortMapping("PCP", "TCP", InetAddress.getByName("203.0.113.12"), 6891, 6891, 3600)),
                Optional.empty(), false);

        assertTrue(profile.ipv4PublicPeerEndpoint().isPresent());
        assertTrue(profile.publicPeerEndpoint().isEmpty());
    }

    @Test void confirmedInboundRouteNeedsTcpObservationAndExplicitConfirmation() throws Exception {
        ObservedEndpoint observed = endpoint("203.0.113.12", 43817, Transport.TCP, ObservationSource.PEER_OBSERVED, true);
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false, List.of(observed), true);

        assertEquals(43817, profile.publicPeerEndpoint().orElseThrow().port());
        assertEquals(43817, profile.ipv4DhtAnnouncePort().orElseThrow());
        assertEquals(ConnectivityProfile.DhtAnnouncementMode.PUBLIC_INBOUND, profile.dhtAnnouncement().mode());
    }

    @Test void stunUdpObservationNeverBecomesTcpAnnouncement() throws Exception {
        ObservedEndpoint observed = endpoint("203.0.113.12", 54321, Transport.UTP, ObservationSource.EXTERNAL_PROBE, false);
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.of(observed), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false, List.of(observed), false);

        assertTrue(profile.ipv4DhtAnnouncePort().isEmpty());
        assertTrue(profile.ipv4PublicPeerEndpoint().isEmpty());
    }

    private static ObservedEndpoint endpoint(String address, int port, Transport transport, ObservationSource source,
                                             boolean confirmed) throws Exception {
        Instant now = Instant.now();
        return new ObservedEndpoint(InetAddress.getByName(address), port, transport, source, now, now.plusSeconds(60), confirmed);
    }
}
