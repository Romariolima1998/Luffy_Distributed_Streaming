package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.util.List;
import java.util.Optional;

/** Texto copiável para o painel visual de rede; não executa operações de rede. */
final class ConnectivityVisualReport {
    private ConnectivityVisualReport() { }

    static String render(ConnectivityProfile profile, List<Inet4Address> localIpv4,
                         boolean tcpListening, boolean utpListening, boolean dhtListening) {
        ConnectivityProfile state = profile == null ? ConnectivityProfile.unavailable() : profile;
        List<Inet4Address> locals = localIpv4 == null ? List.of() : localIpv4;
        List<ConnectivityProfile.PortMapping> mappings = java.util.stream.Stream.of(state.tcpMapping(), state.utpMapping(), state.dhtMapping())
                .flatMap(Optional::stream).toList();

        String local = locals.isEmpty() ? "não disponível" : locals.stream().map(Inet4Address::getHostAddress).distinct().reduce((a, b) -> a + ", " + b).orElse("não disponível");
        String ipv6 = state.publicIpv6().filter(IpAddressClassifier::isGlobalUnicastIpv6)
                .map(address -> address.getHostAddress()).orElse("não disponível");
        String observedIpv4 = state.publicIpv4().map(endpoint -> endpoint.publicIp().getHostAddress())
                .or(() -> state.ipv4PublicPeerEndpoint().map(endpoint -> endpoint.address().getHostAddress()))
                .orElse("não disponível");
        String confirmedPort = state.publicPeerEndpoint()
                .map(endpoint -> "sim — " + endpoint.address().getHostAddress() + ":" + endpoint.port())
                .orElseGet(() -> state.ipv4PublicPeerEndpoint()
                        .map(endpoint -> "não (observada: " + endpoint.port() + ")").orElse("não"));

        boolean natDetected = state.cgnatSuspected() || !mappings.isEmpty()
                || state.publicIpv4().isPresent() && locals.stream().noneMatch(address -> address.equals(state.publicIpv4().orElseThrow().publicIp()));
        String nat = state.cgnatSuspected() ? "CGNAT / duplo NAT suspeito" : natDetected ? "detectado" : "não detectado ou desconhecido";

        return String.join(System.lineSeparator(),
                "REDE LOCAL",
                "",
                "IPv4 local: " + local,
                "IPv6 global: " + ipv6,
                "TCP: " + state.torrentListeningPort() + " " + listenerStatus(tcpListening),
                "uTP: " + state.torrentListeningPort() + " " + listenerStatus(utpListening),
                "DHT UDP: " + state.dhtListeningPort() + " " + listenerStatus(dhtListening),
                "",
                "NAT: " + nat,
                "UPnP: " + mappingStatus(mappings, "UPnP"),
                "NAT-PMP: " + mappingStatus(mappings, "NAT-PMP"),
                "PCP: " + mappingStatus(mappings, "PCP"),
                "",
                "IPv4 público observado: " + observedIpv4,
                "Porta pública confirmada: " + confirmedPort,
                "Firewall local: " + (state.firewallConfigured() ? "autorizado" : "não confirmado"),
                "Estado: " + (state.dhtAnnouncement().shouldAnnounce() ? "PUBLIC INBOUND" : "FIREWALLED / OUTBOUND ONLY"));
    }

    private static String listenerStatus(boolean listening) { return listening ? "LISTEN" : "aguardando motor"; }

    private static String mappingStatus(List<ConnectivityProfile.PortMapping> mappings, String mechanism) {
        Optional<ConnectivityProfile.PortMapping> mapping = mappings.stream()
                .filter(current -> mechanism.equalsIgnoreCase(current.mechanism())).findFirst();
        return mapping.map(current -> "disponível — " + current.protocol() + " local " + current.internalPort()
                + " → público " + current.externalPort()).orElse("não disponível");
    }
}
