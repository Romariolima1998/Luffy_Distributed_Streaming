package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Orquestra firewall, observacoes STUN/IPv6 e o componente isolado de NAT traversal. */
public final class ConnectivityService implements AutoCloseable {
    public static final int P2P_PORT = 6_891;
    public static final int DHT_PORT = 49_001;
    private static final long MAPPING_DISCOVERY_TIMEOUT_SECONDS = 12;
    private static final String STUN_HOST = "stun.cloudflare.com";
    private static final int STUN_PORT = 3_478;

    private final AtomicBoolean configuring = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final NatTraversalService natTraversal = new NatTraversalService();
    private final P2pDiagnostics diagnostics;
    private final ExternalEndpointRegistry externalEndpoints = new ExternalEndpointRegistry();
    private final EndpointObservationService endpointObservations = new EndpointObservationService(externalEndpoints);
    private volatile ConnectivityProfile profile = ConnectivityProfile.unavailable();
    private volatile Consumer<ConnectivityProfile> profileListener = ignored -> { };
    private volatile Consumer<String> statusListener = ignored -> { };

    public ConnectivityService() { this(new P2pDiagnostics()); }
    public ConnectivityService(P2pDiagnostics diagnostics) { this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics; }
    public ConnectivityProfile profile() { return profile; }

    /** Executa a preparacao em segundo plano. O Windows pede UAC apenas para a regra do Luffy. */
    public void configure(Path executable, boolean requestWindowsFirewall, Consumer<ConnectivityProfile> onReady, Consumer<String> status) {
        profileListener = onReady == null ? ignored -> { } : onReady;
        statusListener = status == null ? ignored -> { } : status;
        closed.set(false);
        if (!configuring.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try {
                recordLocalEndpoints();
                Optional<Inet6Address> ipv6 = discoverPublicIpv6();
                Optional<ObservedEndpoint> ipv4 = discoverPublicIpv4();
                NatTraversalService.MappingResult mappings = mapPortsWithinDeadline();
                boolean firewallReady = configureFirewall(executable, requestWindowsFirewall, P2P_PORT);
                profile = new ConnectivityProfile(firewallReady, P2P_PORT, DHT_PORT, ipv4, ipv6,
                        mappings.tcp().map(ConnectivityService::toProfileMapping),
                        mappings.dht().map(ConnectivityService::toProfileMapping),
                        mappings.utp().map(ConnectivityService::toProfileMapping),
                        isCgnatSuspected(ipv4, mappings.all()), externalEndpoints.externalSnapshot(), false);
                profileListener.accept(profile);
                statusListener.accept(summary(profile));
            } catch (Exception error) {
                profile = ConnectivityProfile.unavailable();
                profileListener.accept(profile);
                statusListener.accept("Conectividade P2P limitada: " + message(error));
                diagnostics.log("CONNECTIVITY ERROR: " + message(error));
            } finally {
                configuring.set(false);
            }
        });
    }

    private NatTraversalService.MappingResult mapPortsWithinDeadline() {
        var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        Future<NatTraversalService.MappingResult> task = executor.submit(() -> natTraversal.openMappings(
                localIpv4Addresses(), P2P_PORT, DHT_PORT, this::natLog, this::recordNatMappingObservation));
        try {
            return task.get(MAPPING_DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            natLog("NAT mapping failed: descoberta interrompida.");
            return NatTraversalService.MappingResult.none();
        } catch (TimeoutException error) {
            natLog("NAT mapping failed: PCP, NAT-PMP e UPnP nao responderam em " + MAPPING_DISCOVERY_TIMEOUT_SECONDS + " s.");
            return NatTraversalService.MappingResult.none();
        } catch (ExecutionException error) {
            natLog("NAT mapping failed: " + message(error) + ".");
            return NatTraversalService.MappingResult.none();
        } finally {
            task.cancel(true);
            executor.shutdownNow();
        }
    }

    private void natLog(String message) { diagnostics.log(P2pDiagnostics.Layer.NAT, message); statusListener.accept(message); }

    private boolean configureFirewall(Path executable, boolean requestWindowsFirewall, int torrentPort) {
        if (!isWindows()) return true;
        try {
            WindowsFirewallManager firewall = new WindowsFirewallManager();
            if (!requestWindowsFirewall && firewall.isLuffyAllowed(executable, torrentPort, DHT_PORT)) return true;
            statusListener.accept("Solicitando permissao do Windows para o Luffy receber conexoes P2P...");
            boolean allowed = firewall.allowLuffy(executable, torrentPort, DHT_PORT);
            statusListener.accept("Firewall do Luffy autorizado para TCP " + torrentPort + ", uTP UDP " + torrentPort + " e DHT UDP " + DHT_PORT + ".");
            return allowed;
        } catch (Exception error) {
            statusListener.accept("A regra de firewall nao foi criada: " + message(error) + ". O Luffy ainda pode iniciar conexoes de saida.");
            diagnostics.log("FIREWALL ERROR: " + message(error));
            return false;
        }
    }

    private Optional<Inet6Address> discoverPublicIpv6() {
        statusListener.accept("Verificando conectividade IPv6 direta...");
        StunClient stun = new StunClient();
        for (Inet6Address candidate : localIpv6Addresses()) {
            Optional<ObservedEndpoint> endpoint = stun.discover(candidate, STUN_HOST, STUN_PORT, this::natLog);
            endpoint.ifPresent(this::recordObservation);
            if (endpoint.isPresent() && endpoint.get().publicIp() instanceof Inet6Address address) return Optional.of(address);
        }
        return Optional.empty();
    }

    private Optional<ObservedEndpoint> discoverPublicIpv4() {
        statusListener.accept("Descobrindo o endereco IPv4 publico via STUN...");
        for (Inet4Address candidate : localIpv4Addresses()) {
            Optional<ObservedEndpoint> endpoint = new StunClient().discover(candidate, STUN_HOST, STUN_PORT, this::natLog);
            endpoint.ifPresent(this::recordObservation);
            if (endpoint.isPresent()) return endpoint;
        }
        return Optional.empty();
    }

    private boolean isCgnatSuspected(Optional<ObservedEndpoint> stun, List<NatTraversalService.PortMapping> mappings) {
        return mappings.stream().anyMatch(mapping -> !isPublicIpv4(mapping.externalAddress()))
                || stun.isPresent() && mappings.stream().map(NatTraversalService.PortMapping::externalAddress)
                .anyMatch(address -> !address.equals(stun.get().publicIp()));
    }

    /** Endereços IPv4 da máquina, expostos apenas para o painel local de diagnóstico. */
    public List<Inet4Address> localIpv4Addresses() { return List.copyOf(localAddresses(Inet4Address.class)); }
    private List<Inet6Address> localIpv6Addresses() { return localAddresses(Inet6Address.class).stream().filter(IpAddressClassifier::isGlobalUnicastIpv6).toList(); }
    private <T extends InetAddress> List<T> localAddresses(Class<T> type) {
        List<T> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (type.isInstance(address) && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) found.add(type.cast(address));
                }
            }
        } catch (Exception error) {
            diagnostics.log("CONNECTIVITY ERROR: nao foi possivel listar enderecos locais: " + message(error));
        }
        return found;
    }

    private void recordLocalEndpoints() {
        for (Inet4Address address : localIpv4Addresses()) {
            endpointObservations.observeLocal(address, P2P_PORT, Transport.TCP);
            endpointObservations.observeLocal(address, P2P_PORT, Transport.UTP);
            endpointObservations.observeLocal(address, DHT_PORT, Transport.DHT);
        }
    }

    private void recordNatMappingObservation(NatTraversalService.PortMapping mapping) {
        ConnectivityProfile.PortMapping profileMapping = toProfileMapping(mapping);
        recordObservation(ConnectivityProfile.observationFromMapping(profileMapping));
    }

    private void recordObservation(ObservedEndpoint endpoint) {
        try {
            endpointObservations.recordExternal(endpoint);
        } catch (IllegalArgumentException error) {
            diagnostics.log("OBSERVED ENDPOINT REJECTED: address=" + endpoint.address().getHostAddress()
                    + "; transport=" + endpoint.transport() + "; reason=" + message(error) + ".");
            return;
        }
        diagnostics.log("OBSERVED ENDPOINT: publicIp=" + endpoint.address().getHostAddress()
                + "; publicPort=" + endpoint.port() + "; transport=" + endpoint.transport()
                + "; observedAt=" + endpoint.observedAt() + "; expiresAt=" + endpoint.expiresAt()
                + "; source=" + endpoint.source().label() + "; confirmed=" + endpoint.confirmed() + ".");
        ConnectivityProfile state = profile;
        if (!state.equals(ConnectivityProfile.unavailable())) {
            profile = state.withObservedEndpoints(externalEndpoints.externalSnapshot());
            profileListener.accept(profile);
        }
    }

    private static ConnectivityProfile.PortMapping toProfileMapping(NatTraversalService.PortMapping mapping) {
        return new ConnectivityProfile.PortMapping(mapping.mechanism(), mapping.protocol(), mapping.externalAddress(),
                mapping.externalPort(), mapping.localPort(), mapping.lifetimeSeconds());
    }

    private static boolean isPublicIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
        return first != 0 && first != 10 && first != 127 && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31) && !(first == 192 && second == 168)
                && !(first == 100 && second >= 64 && second <= 127);
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    private String summary(ConnectivityProfile state) {
        List<String> details = new ArrayList<>();
        state.publicIpv6().ifPresent(address -> details.add("IPv6 publico detectado: " + address.getHostAddress()));
        state.publicIpv4().ifPresent(endpoint -> details.add("IPv4 externo observou uTP/UDP " + endpoint.display()));
        state.tcpMapping().ifPresent(mapping -> details.add("TCP local " + mapping.internalPort() + " -> externo " + mapping.externalPort() + " por " + mapping.mechanism()));
        state.utpMapping().ifPresent(mapping -> details.add("uTP UDP local " + mapping.internalPort() + " -> externo " + mapping.externalPort() + " por " + mapping.mechanism()));
        state.dhtMapping().ifPresent(mapping -> details.add("UDP local " + mapping.internalPort() + " -> externo " + mapping.externalPort() + " por " + mapping.mechanism()));
        if (state.cgnatSuspected()) details.add("CGNAT ou duplo NAT detectado; o peer continuara em modo de saida quando necessario");
        state.ipv4PublicPeerEndpoint().ifPresent(endpoint -> details.add("endpoint TCP IPv4 observado para DHT: "
                + endpoint.address().getHostAddress() + ":" + endpoint.port() + " (" + endpoint.mechanism() + ")"));
        if (state.publicPeerEndpoint().isEmpty()) details.add("nenhuma rota publica confirmada; observacoes de STUN e NAT nao sao tratadas como alcance entrante");
        return "Conectividade P2P: " + String.join(". ", details) + ".";
    }

    @Override public void close() { closed.set(true); natTraversal.close(); }
}
