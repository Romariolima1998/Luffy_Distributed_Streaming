package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class ExternalEndpointRegistryTest {
    @Test void keepsPublicTcpAndUtpPortsAsSeparateEndpoints() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        registry.recordExternal(endpoint("203.0.113.12", 43127, Transport.TCP, ObservationSource.PCP, true));
        registry.recordExternal(endpoint("203.0.113.12", 51234, Transport.UTP, ObservationSource.PCP, true));

        assertEquals(43127, registry.bestExternal(Transport.TCP, Inet4Address.class).orElseThrow().port());
        assertEquals(51234, registry.bestExternal(Transport.UTP, Inet4Address.class).orElseThrow().port());
    }

    @Test void removesExpiredEndpointsBeforeSelectingOrPublishingThem() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        Instant now = Instant.now();
        registry.recordExternal(new ObservedEndpoint(InetAddress.getByName("203.0.113.15"), 43127, Transport.TCP,
                ObservationSource.PCP, now.minusSeconds(10), now.minusSeconds(1), true));

        assertTrue(registry.bestExternal(Transport.TCP).isEmpty());
        assertTrue(registry.externalSnapshot().isEmpty());
    }

    @Test void keepsConfirmedEndpointAheadOfNewerEstimate() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        registry.recordExternal(endpoint("203.0.113.16", 43127, Transport.TCP, ObservationSource.NAT_PMP, true));
        registry.recordExternal(endpoint("203.0.113.17", 43128, Transport.TCP, ObservationSource.EXTERNAL_PROBE, false));

        ObservedEndpoint selected = registry.bestExternal(Transport.TCP).orElseThrow();
        assertEquals("203.0.113.16", selected.address().getHostAddress());
        assertTrue(selected.confirmed());
    }

    @Test void rejectsPrivateAddressFromTheExternalRegistry() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.recordExternal(
                endpoint("192.168.1.7", 6891, Transport.UTP, ObservationSource.CONFIGURED, false)));
    }

    @Test void keepsLocalAndExternalEndpointsInSeparateCollections() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        EndpointObservationService observations = new EndpointObservationService(registry);

        observations.observeLocal(InetAddress.getByName("192.168.1.7"), 6891, Transport.UTP);
        observations.observeExternal(InetAddress.getByName("203.0.113.18"), 43127, Transport.UTP,
                ObservationSource.PCP, true);

        assertEquals(1, registry.localSnapshot().size());
        assertEquals(1, registry.externalSnapshot().size());
        assertEquals("203.0.113.18", registry.bestExternal(Transport.UTP).orElseThrow().address().getHostAddress());
    }

    @Test void acceptsConcurrentObservationsWithoutMixingTransportSlots() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        List<Future<?>> tasks = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 80; index++) {
                int current = index;
                tasks.add(executor.submit(() -> registry.recordExternal(endpointUnchecked(
                        "203.0.113." + (20 + current % 4), 40000 + current,
                        current % 2 == 0 ? Transport.TCP : Transport.UTP,
                        current % 3 == 0 ? ObservationSource.PCP : ObservationSource.UNKNOWN,
                        current % 3 == 0))));
            }
            for (Future<?> task : tasks) task.get();
        }

        assertTrue(registry.bestExternal(Transport.TCP).isPresent());
        assertTrue(registry.bestExternal(Transport.UTP).isPresent());
        assertEquals(80, registry.externalSnapshot().size());
    }

    @Test void replacesAnInferiorSourceWithMoreReliableConfirmedEvidence() throws Exception {
        ExternalEndpointRegistry registry = new ExternalEndpointRegistry();
        registry.recordExternal(endpoint("203.0.113.21", 43127, Transport.UTP, ObservationSource.UPNP, true));
        registry.recordExternal(endpoint("203.0.113.21", 43127, Transport.UTP, ObservationSource.PCP, true));
        registry.recordExternal(endpoint("203.0.113.21", 43127, Transport.UTP, ObservationSource.UPNP, true));

        ObservedEndpoint selected = registry.bestExternal(Transport.UTP).orElseThrow();
        assertEquals(ObservationSource.PCP, selected.source());
        assertTrue(selected.confirmed());
    }

    private static ObservedEndpoint endpoint(String address, int port, Transport transport, ObservationSource source,
                                             boolean confirmed) throws Exception {
        return endpointUnchecked(address, port, transport, source, confirmed);
    }

    private static ObservedEndpoint endpointUnchecked(String address, int port, Transport transport, ObservationSource source,
                                                      boolean confirmed) {
        try {
            Instant now = Instant.now();
            return new ObservedEndpoint(InetAddress.getByName(address), port, transport, source, now, now.plusSeconds(60), confirmed);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
