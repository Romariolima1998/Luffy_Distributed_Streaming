package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Cria observações com prazo de validade e as encaminha ao registro correto. */
public final class EndpointObservationService {
    public static final Duration DEFAULT_ESTIMATE_TTL = Duration.ofMinutes(2);
    public static final Duration DEFAULT_CONFIRMED_TTL = Duration.ofHours(1);

    private final ExternalEndpointRegistry registry;

    public EndpointObservationService(ExternalEndpointRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ObservedEndpoint observeExternal(InetAddress address, int port, Transport transport, ObservationSource source,
                                            Instant observedAt, Instant expiresAt, boolean confirmed) {
        return recordExternal(new ObservedEndpoint(address, port, transport, source, observedAt, expiresAt, confirmed));
    }

    public ObservedEndpoint observeExternal(InetAddress address, int port, Transport transport, ObservationSource source,
                                            boolean confirmed) {
        Instant now = Instant.now();
        return observeExternal(address, port, transport, source, now,
                now.plus(confirmed ? DEFAULT_CONFIRMED_TTL : DEFAULT_ESTIMATE_TTL), confirmed);
    }

    public ObservedEndpoint observeLocal(InetAddress address, int port, Transport transport) {
        Instant now = Instant.now();
        return registry.recordLocal(new ObservedEndpoint(address, port, transport, ObservationSource.LOCAL, now,
                now.plus(DEFAULT_CONFIRMED_TTL), true));
    }

    public ObservedEndpoint recordExternal(ObservedEndpoint endpoint) { return registry.recordExternal(endpoint); }

    public ExternalEndpointRegistry registry() { return registry; }
}
