package dev.lufi.infrastructure.rendezvous;

import dev.lufi.infrastructure.ExternalEndpointRegistry;
import java.util.Objects;

/**
 * Regra de divulgacao do overlay: um endpoint de rendezvous deve ser um
 * endpoint publico ja observado. A politica de loopback existe somente para
 * testes de integracao locais e nunca e usada pelo gateway da aplicacao.
 */
public final class OverlayPrivacyPolicy {
    private static final OverlayPrivacyPolicy STRICT = new OverlayPrivacyPolicy(false);
    private static final OverlayPrivacyPolicy LOOPBACK_TEST = new OverlayPrivacyPolicy(true);
    private final boolean allowPrivateForLoopbackTest;

    private OverlayPrivacyPolicy(boolean allowPrivateForLoopbackTest) {
        this.allowPrivateForLoopbackTest = allowPrivateForLoopbackTest;
    }

    public static OverlayPrivacyPolicy strict() { return STRICT; }
    public static OverlayPrivacyPolicy loopbackTestOnly() { return LOOPBACK_TEST; }

    public boolean allows(LuffyRendezvousMessage.RendezvousEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return allowPrivateForLoopbackTest || ExternalEndpointRegistry.isPublicAddress(endpoint.address());
    }
}
