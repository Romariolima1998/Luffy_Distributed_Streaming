package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerEndpointParserTest {
    @Test void parsesIpv4EndpointWithPort() throws Exception {
        var endpoint = PeerEndpointParser.parse("203.0.113.9:43127");

        assertInstanceOf(Inet4Address.class, endpoint.getAddress());
        assertEquals("203.0.113.9", endpoint.getAddress().getHostAddress());
        assertEquals(43127, endpoint.getPort());
    }

    @Test void parsesBracketedIpv6EndpointWithPort() throws Exception {
        var endpoint = PeerEndpointParser.parse("[2001:db8::42]:6891");

        assertInstanceOf(Inet6Address.class, endpoint.getAddress());
        assertEquals(6891, endpoint.getPort());
    }

    @Test void rejectsAmbiguousIpv6AndInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> PeerEndpointParser.parse("2001:db8::42:6891"));
        assertThrows(IllegalArgumentException.class, () -> PeerEndpointParser.parse("203.0.113.9:0"));
        assertThrows(IllegalArgumentException.class, () -> PeerEndpointParser.parse("203.0.113.9:65536"));
    }
}
