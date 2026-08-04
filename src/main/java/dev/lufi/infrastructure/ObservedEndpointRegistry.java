package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** @deprecated Use {@link ExternalEndpointRegistry}; mantido para compatibilidade local. */
@Deprecated
public final class ObservedEndpointRegistry {
    private final ExternalEndpointRegistry delegate = new ExternalEndpointRegistry();

    public ObservedEndpoint record(ObservedEndpoint endpoint) { return delegate.recordExternal(endpoint); }

    public ObservedEndpoint record(InetAddress address, int port, Transport transport, ObservationSource source) {
        Instant now = Instant.now();
        return record(new ObservedEndpoint(address, port, transport, source, now,
                now.plus(EndpointObservationService.DEFAULT_ESTIMATE_TTL), false));
    }

    public List<ObservedEndpoint> snapshot() { return delegate.externalSnapshot(); }
    public Optional<ObservedEndpoint> latest(Transport transport) { return delegate.bestExternal(transport); }
}
