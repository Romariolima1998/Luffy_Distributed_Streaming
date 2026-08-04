package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Estado de conectividade observado nesta execução. Evidência de endpoint e
 * confirmação de alcance TCP de entrada permanecem conceitos distintos.
 */
public record ConnectivityProfile(
        boolean firewallConfigured,
        int torrentListeningPort,
        int dhtListeningPort,
        Optional<ObservedEndpoint> publicIpv4,
        Optional<Inet6Address> publicIpv6,
        Optional<PortMapping> tcpMapping,
        Optional<PortMapping> dhtMapping,
        Optional<PortMapping> utpMapping,
        boolean cgnatSuspected,
        List<ObservedEndpoint> observedEndpoints,
        boolean inboundTcpReachabilityConfirmed) {

    public ConnectivityProfile {
        publicIpv4 = publicIpv4 == null ? Optional.empty() : publicIpv4;
        publicIpv6 = publicIpv6 == null ? Optional.empty() : publicIpv6;
        tcpMapping = tcpMapping == null ? Optional.empty() : tcpMapping;
        dhtMapping = dhtMapping == null ? Optional.empty() : dhtMapping;
        utpMapping = utpMapping == null ? Optional.empty() : utpMapping;
        observedEndpoints = observedEndpoints == null ? List.of() : List.copyOf(observedEndpoints);
    }

    /** Compatibilidade para os pontos de montagem anteriores ao registro externo. */
    public ConnectivityProfile(boolean firewallConfigured, int torrentListeningPort, int dhtListeningPort,
                               Optional<StunEndpoint> publicIpv4, Optional<Inet6Address> publicIpv6,
                               Optional<PortMapping> tcpMapping, Optional<PortMapping> dhtMapping,
                               boolean cgnatSuspected) {
        this(firewallConfigured, torrentListeningPort, dhtListeningPort,
                publicIpv4.map(endpoint -> estimate(endpoint.address(), endpoint.port(), Transport.UTP, ObservationSource.EXTERNAL_PROBE)),
                publicIpv6, tcpMapping, dhtMapping, Optional.empty(), cgnatSuspected,
                observationsFromMappings(tcpMapping, dhtMapping, Optional.empty()), false);
    }

    public static ConnectivityProfile unavailable() {
        return new ConnectivityProfile(false, ConnectivityService.P2P_PORT, ConnectivityService.DHT_PORT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, List.of(), false);
    }

    /** A presença de listener não basta: apenas IPv6 unicast global ativa DHT IPv6. */
    public boolean useIpv6Dht() { return publicIpv6.filter(IpAddressClassifier::isGlobalUnicastIpv6).isPresent(); }
    public boolean hasGlobalIpv6() { return useIpv6Dht(); }
    public boolean hasAdvertisableIpv4PeerPort() { return ipv4PublicPeerEndpoint().isPresent(); }

    /** Endpoint TCP externo observado. Nunca consulta endpoint UTP como substituto. */
    public Optional<PublicPeerEndpoint> ipv4PublicPeerEndpoint() {
        return latestPublic(Transport.TCP, Inet4Address.class).map(ConnectivityProfile::publicPeerEndpoint);
    }

    public DhtAnnouncement dhtAnnouncement() {
        return publicPeerEndpoint()
                .map(endpoint -> new DhtAnnouncement(DhtAnnouncementMode.PUBLIC_INBOUND, Optional.of(endpoint),
                        "endpoint TCP externo confirmado por " + endpoint.mechanism()))
                .orElseGet(() -> new DhtAnnouncement(DhtAnnouncementMode.OUTBOUND_ONLY_FIREWALLED, Optional.empty(),
                        "nenhuma conectividade TCP de entrada foi confirmada; o peer permanece somente de saída"));
    }

    public OptionalInt ipv4DhtAnnouncePort() {
        return dhtAnnouncement().endpoint().map(PublicPeerEndpoint::port).stream().mapToInt(Integer::intValue).findFirst();
    }

    /** Endpoint uTP externo observado. Nunca reutiliza a porta TCP como substituto. */
    public Optional<PublicPeerEndpoint> observedUtpPeerEndpoint() {
        return latestPublic(Transport.UTP, Inet4Address.class).map(ConnectivityProfile::publicPeerEndpoint);
    }

    /** A política de anúncio exige, além da evidência, uma prova independente de entrada TCP. */
    public Optional<PublicPeerEndpoint> publicPeerEndpoint() {
        if (!inboundTcpReachabilityConfirmed) return Optional.empty();
        return latestPublic(Transport.TCP, InetAddress.class).filter(ObservedEndpoint::confirmed)
                .map(ConnectivityProfile::publicPeerEndpoint);
    }

    public boolean hasConfirmedInboundTcpEndpoint() { return publicPeerEndpoint().isPresent(); }

    public ConnectivityProfile withObservedEndpoints(List<ObservedEndpoint> observations) {
        return new ConnectivityProfile(firewallConfigured, torrentListeningPort, dhtListeningPort, publicIpv4, publicIpv6,
                tcpMapping, dhtMapping, utpMapping, cgnatSuspected, observations, inboundTcpReachabilityConfirmed);
    }

    public ConnectivityProfile withInboundTcpReachabilityConfirmed(boolean confirmed) {
        return new ConnectivityProfile(firewallConfigured, torrentListeningPort, dhtListeningPort, publicIpv4, publicIpv6,
                tcpMapping, dhtMapping, utpMapping, cgnatSuspected, observedEndpoints, confirmed);
    }

    private <T extends InetAddress> Optional<ObservedEndpoint> latestPublic(Transport transport, Class<T> family) {
        Instant now = Instant.now();
        return observedEndpoints.stream().filter(endpoint -> endpoint.transport() == transport)
                .filter(endpoint -> family.isInstance(endpoint.address()))
                .filter(endpoint -> !endpoint.isExpired(now))
                .filter(endpoint -> ExternalEndpointRegistry.isPublicAddress(endpoint.address()))
                .max(ExternalEndpointRegistry::comparePreference);
    }

    private static List<ObservedEndpoint> observationsFromMappings(Optional<PortMapping> tcp, Optional<PortMapping> dht,
                                                                     Optional<PortMapping> utp) {
        List<ObservedEndpoint> observations = new ArrayList<>();
        tcp.ifPresent(mapping -> observations.add(observationFromMapping(mapping)));
        dht.ifPresent(mapping -> observations.add(observationFromMapping(mapping)));
        utp.ifPresent(mapping -> observations.add(observationFromMapping(mapping)));
        return observations;
    }

    public static ObservedEndpoint observationFromMapping(PortMapping mapping) {
        Transport transport = "TCP".equalsIgnoreCase(mapping.protocol()) ? Transport.TCP
                : mapping.internalPort() == ConnectivityService.DHT_PORT ? Transport.DHT : Transport.UTP;
        Instant now = Instant.now();
        long lifetime = mapping.lifetimeSeconds() > 0 ? mapping.lifetimeSeconds()
                : EndpointObservationService.DEFAULT_CONFIRMED_TTL.toSeconds();
        return new ObservedEndpoint(mapping.externalAddress(), mapping.externalPort(), transport,
                ObservedEndpoint.mappingSource(mapping.mechanism()), now, now.plusSeconds(lifetime), true);
    }

    private static ObservedEndpoint estimate(InetAddress address, int port, Transport transport, ObservationSource source) {
        Instant now = Instant.now();
        return new ObservedEndpoint(address, port, transport, source, now,
                now.plus(EndpointObservationService.DEFAULT_ESTIMATE_TTL), false);
    }

    private static PublicPeerEndpoint publicPeerEndpoint(ObservedEndpoint endpoint) {
        return new PublicPeerEndpoint(endpoint.address(), endpoint.port(), endpoint.source().label());
    }

    /** Legado de leitura: a observação STUN continua sendo somente um endpoint UDP estimado. */
    public record StunEndpoint(InetAddress address, int port) { }
    public record PublicPeerEndpoint(InetAddress address, int port, String mechanism) { }
    public enum DhtAnnouncementMode { PUBLIC_INBOUND, OUTBOUND_ONLY_FIREWALLED }
    public record DhtAnnouncement(DhtAnnouncementMode mode, Optional<PublicPeerEndpoint> endpoint, String reason) {
        public DhtAnnouncement {
            endpoint = endpoint == null ? Optional.empty() : endpoint;
            reason = reason == null ? "" : reason;
        }
        public boolean shouldAnnounce() { return mode == DhtAnnouncementMode.PUBLIC_INBOUND && endpoint.isPresent(); }
    }
    public record PortMapping(String mechanism, String protocol, InetAddress externalAddress, int externalPort,
                              int internalPort, long lifetimeSeconds) { }
}
