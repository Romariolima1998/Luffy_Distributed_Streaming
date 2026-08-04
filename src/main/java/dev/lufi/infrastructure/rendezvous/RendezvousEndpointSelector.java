package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ExternalEndpointRegistry;
import dev.lufi.infrastructure.ObservedEndpoint;
import dev.lufi.infrastructure.Transport;
import java.net.Inet4Address;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/** Converte somente evidencia uTP publica, confirmada e vigente em um endpoint de rendezvous. */
public final class RendezvousEndpointSelector {
    private RendezvousEndpointSelector() { }

    public static Optional<LuffyRendezvousMessage.RendezvousEndpoint> selectConfirmedUtp(
            Collection<ObservedEndpoint> observations, Instant now) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(now, "now");
        return observations.stream().filter(endpoint -> endpoint.transport() == Transport.UTP)
                .filter(ObservedEndpoint::confirmed).filter(endpoint -> !endpoint.isExpired(now))
                .filter(endpoint -> endpoint.address() instanceof Inet4Address)
                .filter(endpoint -> ExternalEndpointRegistry.isPublicAddress(endpoint.address()))
                .max(java.util.Comparator.comparing(ObservedEndpoint::observedAt)
                        .thenComparing(ObservedEndpoint::expiresAt))
                .map(endpoint -> new LuffyRendezvousMessage.RendezvousEndpoint(endpoint.address(), endpoint.port()));
    }
}
