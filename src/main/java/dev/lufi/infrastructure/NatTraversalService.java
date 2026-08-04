package dev.lufi.infrastructure;

import com.offbynull.portmapper.PortMapperFactory;
import com.offbynull.portmapper.gateways.network.NetworkGateway;
import com.offbynull.portmapper.gateways.process.ProcessGateway;
import com.offbynull.portmapper.mapper.MappedPort;
import com.offbynull.portmapper.mapper.PortMapper;
import com.offbynull.portmapper.mapper.PortType;
import com.offbynull.portmapper.mappers.natpmp.NatPmpPortMapper;
import com.offbynull.portmapper.mappers.pcp.PcpPortMapper;
import com.offbynull.portmapper.mappers.upnpigd.UpnpIgdPortMapper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Responsável exclusivamente pelo NAT traversal de portas locais.
 * A porta externa retornada pelo roteador é preservada exatamente como foi concedida.
 */
public final class NatTraversalService implements AutoCloseable {
    public static final long REQUESTED_LEASE_SECONDS = 3_600;
    private static final long MIN_RENEWAL_DELAY_SECONDS = 30;

    private final List<ManagedMapping> mappings = new ArrayList<>();
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> logger = ignored -> { };
    private volatile Consumer<PortMapping> mappingObserver = ignored -> { };
    private volatile NetworkGateway networkGateway;
    private volatile ProcessGateway processGateway;

    /** Tenta PCP, NAT-PMP e UPnP nessa ordem, porque PCP é o protocolo mais moderno e explícito. */
    public MappingResult openMappings(List<Inet4Address> localAddresses, int tcpLocalPort, int udpLocalPort, Consumer<String> diagnostics) {
        return openMappings(localAddresses, tcpLocalPort, udpLocalPort, diagnostics, ignored -> { });
    }

    /** Cada mapeamento aceito ou renovado é informado como observação, sem alegar alcance externo. */
    public MappingResult openMappings(List<Inet4Address> localAddresses, int tcpLocalPort, int udpLocalPort,
                                      Consumer<String> diagnostics, Consumer<PortMapping> observationListener) {
        logger = diagnostics == null ? ignored -> { } : diagnostics;
        mappingObserver = observationListener == null ? ignored -> { } : observationListener;
        closed.set(false);
        if (localAddresses == null || localAddresses.isEmpty()) {
            log("NAT mapping failed: nenhum IPv4 local utilizável para descobrir o roteador.");
            return MappingResult.none();
        }
        closeMappings();
        List<PortMapper> mappers;
        try {
            networkGateway = NetworkGateway.create();
            processGateway = ProcessGateway.create();
            mappers = new ArrayList<>(PortMapperFactory.discover(networkGateway.getBus(), processGateway.getBus(), localAddresses.toArray(InetAddress[]::new)));
            mappers.sort(Comparator.comparingInt(this::priority));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log("NAT mapping failed: descoberta de PCP/NAT-PMP/UPnP interrompida.");
            return MappingResult.none();
        } catch (RuntimeException error) {
            log("NAT mapping failed: descoberta de PCP/NAT-PMP/UPnP falhou: " + describe(error) + ".");
            return MappingResult.none();
        }
        if (mappers.isEmpty()) {
            log("NAT mapping failed: mechanism=PCP; reason=unsupported or no gateway response.");
            log("NAT mapping failed: mechanism=NAT-PMP; reason=unsupported or no gateway response.");
            log("NAT mapping failed: mechanism=UPnP; reason=unsupported or no IGD response.");
            log("NAT mapping failed: nenhum roteador com PCP, NAT-PMP ou UPnP respondeu.");
            return MappingResult.none();
        }
        logUnavailableMechanisms(mappers);

        Optional<ManagedMapping> tcp = mapFirst(mappers, PortType.TCP, tcpLocalPort);
        Optional<ManagedMapping> utp = mapFirst(mappers, PortType.UDP, tcpLocalPort);
        Optional<ManagedMapping> udp = mapFirst(mappers, PortType.UDP, udpLocalPort);
        List<ManagedMapping> accepted = new ArrayList<>();
        tcp.ifPresent(accepted::add); utp.ifPresent(accepted::add); udp.ifPresent(accepted::add);
        synchronized (mappings) {
            if (closed.get()) { accepted.forEach(ManagedMapping::unmap); return MappingResult.none(); }
            mappings.addAll(accepted);
        }
        accepted.forEach(mapping -> mappingObserver.accept(mapping.view()));
        accepted.forEach(this::scheduleRenewal);
        if (tcp.isEmpty()) log("NAT mapping result: TCP inbound unavailable; próximo=fallback TCP de saída, uTP ou BEP55.");
        if (utp.isEmpty()) log("NAT mapping result: uTP UDP inbound unavailable; próximo=tentativas de saída e BEP55 quando houver rendezvous.");
        return new MappingResult(tcp.map(ManagedMapping::view), utp.map(ManagedMapping::view), udp.map(ManagedMapping::view));
    }

    private void logUnavailableMechanisms(List<PortMapper> mappers) {
        if (mappers.stream().noneMatch(PcpPortMapper.class::isInstance)) {
            log("NAT mapping failed: mechanism=PCP; reason=unsupported or not advertised by gateway.");
        }
        if (mappers.stream().noneMatch(NatPmpPortMapper.class::isInstance)) {
            log("NAT mapping failed: mechanism=NAT-PMP; reason=unsupported or not advertised by gateway.");
        }
        if (mappers.stream().noneMatch(UpnpIgdPortMapper.class::isInstance)) {
            log("NAT mapping failed: mechanism=UPnP; reason=unsupported or no IGD response.");
        }
    }

    private Optional<ManagedMapping> mapFirst(List<PortMapper> mappers, PortType type, int localPort) {
        for (PortMapper mapper : mappers) {
            String mechanism = mechanism(mapper);
            log("NAT mapping requested: protocol=" + type + "; mechanism=" + mechanism + "; local port=" + localPort
                    + "; requested external port=" + localPort + ".");
            try {
                MappedPort mapped = mapper.mapPort(type, localPort, localPort, REQUESTED_LEASE_SECONDS);
                if (mapped == null || mapped.getExternalAddress() == null || mapped.getExternalPort() < 1) {
                    log("NAT mapping failed: protocol=" + type + "; mechanism=" + mechanism + "; local port=" + localPort
                            + "; reason=roteador não retornou endpoint externo válido.");
                    continue;
                }
                ManagedMapping result = new ManagedMapping(mapper, mapped);
                PortMapping view = result.view();
                log("NAT mapping protocol=" + view.mechanism() + "; protocol=" + view.protocol() + "; local port=" + view.localPort()
                        + "; requested external port=" + localPort + "; actual external port=" + view.externalPort()
                        + "; external IP=" + view.externalAddress().getHostAddress() + "; mapping lifetime=" + view.lifetimeSeconds()
                        + "s; renewal required=" + (view.lifetimeSeconds() > 0) + ".");
                return Optional.of(result);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log("NAT mapping failed: protocol=" + type + "; mechanism=" + mechanism + "; local port=" + localPort + "; reason=interrompido.");
                return Optional.empty();
            } catch (RuntimeException error) {
                log("NAT mapping failed: protocol=" + type + "; mechanism=" + mechanism + "; local port=" + localPort
                        + "; reason=" + describe(error) + ".");
            }
        }
        return Optional.empty();
    }

    private void scheduleRenewal(ManagedMapping mapping) {
        long lifetime = mapping.view().lifetimeSeconds();
        if (closed.get() || lifetime <= 0) return;
        long delay = Math.max(MIN_RENEWAL_DELAY_SECONDS, lifetime / 2);
        mapping.renewal = renewer.schedule(() -> renew(mapping), delay, TimeUnit.SECONDS);
    }

    private void renew(ManagedMapping mapping) {
        if (closed.get() || !isManaged(mapping)) return;
        PortMapping before = mapping.view();
        log("NAT renewal required: protocol=" + before.protocol() + "; mechanism=" + before.mechanism() + "; local port="
                + before.localPort() + "; actual external port=" + before.externalPort() + "; mapping lifetime=" + before.lifetimeSeconds() + "s.");
        try {
            MappedPort refreshed = mapping.mapper.refreshPort(mapping.port, REQUESTED_LEASE_SECONDS);
            if (refreshed == null || refreshed.getExternalAddress() == null || refreshed.getExternalPort() < 1) {
                log("NAT mapping failed: protocol=" + before.protocol() + "; mechanism=" + before.mechanism() + "; reason=renovação não retornou endpoint válido.");
                return;
            }
            mapping.port = refreshed;
            PortMapping after = mapping.view();
            log("NAT mapping renewed: protocol=" + after.protocol() + "; mechanism=" + after.mechanism() + "; local port="
                    + after.localPort() + "; actual external port=" + after.externalPort() + "; external IP="
                    + after.externalAddress().getHostAddress() + "; mapping lifetime=" + after.lifetimeSeconds() + "s.");
            mappingObserver.accept(after);
            scheduleRenewal(mapping);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log("NAT mapping failed: protocol=" + before.protocol() + "; mechanism=" + before.mechanism() + "; reason=renovação interrompida.");
        } catch (RuntimeException error) {
            log("NAT mapping failed: protocol=" + before.protocol() + "; mechanism=" + before.mechanism() + "; reason=renovação: " + describe(error) + ".");
        }
    }

    private boolean isManaged(ManagedMapping mapping) {
        synchronized (mappings) { return mappings.contains(mapping); }
    }

    private int priority(PortMapper mapper) {
        if (mapper instanceof PcpPortMapper) return 0;
        if (mapper instanceof NatPmpPortMapper) return 1;
        if (mapper instanceof UpnpIgdPortMapper) return 2;
        return 3;
    }

    private String mechanism(PortMapper mapper) {
        if (mapper instanceof PcpPortMapper) return "PCP";
        if (mapper instanceof NatPmpPortMapper) return "NAT-PMP";
        if (mapper instanceof UpnpIgdPortMapper) return "UPnP";
        return mapper.getClass().getSimpleName();
    }

    private void closeMappings() {
        List<ManagedMapping> previous;
        synchronized (mappings) { previous = new ArrayList<>(mappings); mappings.clear(); }
        previous.forEach(ManagedMapping::unmap);
    }

    private void log(String message) { logger.accept(message); }
    private String describe(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    @Override public void close() {
        closed.set(true);
        renewer.shutdownNow();
        closeMappings();
    }

    public record MappingResult(Optional<PortMapping> tcp, Optional<PortMapping> utp, Optional<PortMapping> dht) {
        public static MappingResult none() { return new MappingResult(Optional.empty(), Optional.empty(), Optional.empty()); }
        public List<PortMapping> all() {
            List<PortMapping> result = new ArrayList<>(); tcp.ifPresent(result::add); utp.ifPresent(result::add); dht.ifPresent(result::add); return result;
        }
    }

    public record PortMapping(String mechanism, String protocol, InetAddress externalAddress, int externalPort,
                              int localPort, long lifetimeSeconds) { }

    private final class ManagedMapping {
        private final PortMapper mapper;
        private MappedPort port;
        private ScheduledFuture<?> renewal;
        private ManagedMapping(PortMapper mapper, MappedPort port) { this.mapper = mapper; this.port = port; }
        private PortMapping view() {
            return new PortMapping(mechanism(mapper), port.getPortType().name(), port.getExternalAddress(), port.getExternalPort(),
                    port.getInternalPort(), port.getLifetime());
        }
        private void unmap() {
            if (renewal != null) renewal.cancel(false);
            try { mapper.unmapPort(port); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); log("NAT mapping failed: remoção interrompida para " + view().protocol() + "."); }
            catch (RuntimeException error) { log("NAT mapping failed: remoção " + view().protocol() + ": " + describe(error) + "."); }
        }
    }
}
