package dev.lufi.infrastructure.rendezvous;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayPrivacyPolicyTest {
    @Test void strictPolicyNeverSharesPrivateOrLocalEndpoints() throws Exception {
        OverlayPrivacyPolicy policy = OverlayPrivacyPolicy.strict();

        assertFalse(policy.allows(endpoint("192.168.1.9")));
        assertFalse(policy.allows(endpoint("10.0.0.9")));
        assertFalse(policy.allows(endpoint("127.0.0.1")));
        assertFalse(policy.allows(endpoint("fc00::9")));
        assertTrue(policy.allows(endpoint("203.0.113.9")));
    }

    @Test void loopbackPolicyIsExplicitAndReservedForTransportIntegrationTests() throws Exception {
        assertTrue(OverlayPrivacyPolicy.loopbackTestOnly().allows(endpoint("127.0.0.1")));
    }

    private static LuffyRendezvousMessage.RendezvousEndpoint endpoint(String address) throws Exception {
        return new LuffyRendezvousMessage.RendezvousEndpoint(InetAddress.getByName(address), 43_127);
    }
}
