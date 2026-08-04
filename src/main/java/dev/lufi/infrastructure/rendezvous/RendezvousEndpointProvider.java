package dev.lufi.infrastructure.rendezvous;

import java.util.Optional;

/** Fornece somente o endpoint uTP externo confirmado da propria instalacao. */
@FunctionalInterface
public interface RendezvousEndpointProvider {
    Optional<LuffyRendezvousMessage.RendezvousEndpoint> localConfirmedUtpEndpoint();
}
