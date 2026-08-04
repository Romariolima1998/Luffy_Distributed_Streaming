package dev.lufi.infrastructure;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressClassifierTest {
    @Test void acceptsOnlyRoutableGlobalIpv6Addresses() throws Exception {
        assertFalse(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("::1")));
        assertFalse(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("fe80::1")));
        assertFalse(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("fd12:3456::1")));
        assertFalse(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("fec0::1")));
        assertFalse(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("2001:db8::1")));
        assertTrue(IpAddressClassifier.isGlobalUnicastIpv6(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
