package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NatTraversalMappingResultTest {
    @Test void preservesActualExternalPortInsteadOfAssumingTheLocalPort() throws Exception {
        var tcp = new NatTraversalService.PortMapping("UPNP", "TCP", InetAddress.getByName("198.51.100.18"), 43127, 6891, 3600);
        var result = new NatTraversalService.MappingResult(Optional.of(tcp), Optional.empty(), Optional.empty());

        assertEquals(43127, result.tcp().orElseThrow().externalPort());
        assertEquals(6891, result.tcp().orElseThrow().localPort());
        assertEquals(1, result.all().size());
        assertFalse(NatTraversalService.MappingResult.none().tcp().isPresent());
    }
}
