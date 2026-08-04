package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerCapabilitiesTest {
    @Test void onlyEnablesBep55WhenThePeerAnnouncedHolePunchAndUtpSupport() {
        PeerCapabilities capable = PeerCapabilities.fromExtensionHandshake(Set.of("ut_holepunch", "ut_metadata", "ut_pex"));
        assertTrue(capable.extensionProtocol());
        assertTrue(capable.utHolePunch());
        assertTrue(capable.utp());
        assertTrue(capable.utMetadata());
        assertTrue(capable.utPex());
        assertTrue(capable.supportsBep55());
        assertFalse(capable.supportsLuffyIdentity());

        PeerCapabilities luffyIdentity = PeerCapabilities.fromExtensionHandshake(Set.of("lf_identity"));
        assertTrue(luffyIdentity.supportsLuffyIdentity());
        assertFalse(luffyIdentity.supportsBep55());

        PeerCapabilities rendezvous = PeerCapabilities.fromExtensionHandshake(Set.of("lf_rendezvous"));
        assertTrue(rendezvous.supportsLuffyRendezvous());
        assertFalse(rendezvous.supportsLuffyRoute());

        PeerCapabilities noHolePunch = PeerCapabilities.fromExtensionHandshake(Set.of("ut_metadata", "ut_pex"));
        assertFalse(noHolePunch.utHolePunch());
        assertFalse(noHolePunch.utp());
        assertFalse(noHolePunch.supportsBep55());
    }

    @Test void doesNotAssumeExtensionProtocolWithoutAnExtendedHandshake() {
        PeerCapabilities unavailable = PeerCapabilities.fromExtensionHandshake(null);
        assertFalse(unavailable.extensionProtocol());
        assertFalse(unavailable.supportsBep55());
    }
}
