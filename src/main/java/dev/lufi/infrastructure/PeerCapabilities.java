package dev.lufi.infrastructure;

import java.util.Collection;

/** Capacidades que um peer efetivamente anunciou no handshake estendido BitTorrent. */
public record PeerCapabilities(boolean extensionProtocol, boolean utHolePunch, boolean utMetadata, boolean utPex,
                               boolean utp, boolean lfIdentity, boolean lfRoute, boolean lfRendezvous) {
    public static PeerCapabilities fromExtensionHandshake(Collection<String> messageTypes) {
        boolean extensionProtocol = messageTypes != null;
        boolean holePunch = extensionProtocol && messageTypes.contains("ut_holepunch");
        // BEP 55 exige BEP 29. Nao ha uma chave BEP 10 padrao separada para
        // uTP; anunciar ut_holepunch e a evidencia de suporte a uTP nesse caso.
        return new PeerCapabilities(extensionProtocol, holePunch,
                extensionProtocol && messageTypes.contains("ut_metadata"),
                extensionProtocol && messageTypes.contains("ut_pex"), holePunch,
                extensionProtocol && messageTypes.contains("lf_identity"),
                extensionProtocol && messageTypes.contains("lf_route"),
                extensionProtocol && messageTypes.contains("lf_rendezvous"));
    }

    public boolean supportsBep55() { return extensionProtocol && utHolePunch && utp; }
    public boolean supportsLuffyIdentity() { return extensionProtocol && lfIdentity; }
    public boolean supportsLuffyRoute() { return extensionProtocol && lfRoute; }
    public boolean supportsLuffyRendezvous() { return extensionProtocol && lfRendezvous; }
}
