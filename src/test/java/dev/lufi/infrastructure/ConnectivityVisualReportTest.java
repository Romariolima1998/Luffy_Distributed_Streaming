package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityVisualReportTest {
    @Test void displaysObservedMappingWithoutCallingItConfirmed() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        ObservedEndpoint observed = new ObservedEndpoint(InetAddress.getByName("168.181.241.221"), 54321,
                Transport.UTP, ObservationSource.EXTERNAL_PROBE, now, now.plusSeconds(60), false);
        ConnectivityProfile profile = new ConnectivityProfile(true, 6891, 49001, Optional.of(observed), Optional.empty(),
                Optional.of(new ConnectivityProfile.PortMapping("PCP", "TCP", InetAddress.getByName("168.181.241.221"), 43127, 6891, 3600)),
                Optional.empty(), Optional.empty(), false, List.of(ConnectivityProfile.observationFromMapping(
                        new ConnectivityProfile.PortMapping("PCP", "TCP", InetAddress.getByName("168.181.241.221"), 43127, 6891, 3600))), false);

        String report = ConnectivityVisualReport.render(profile,
                List.of((Inet4Address) InetAddress.getByName("192.168.11.194")), true, true, true);

        assertTrue(report.contains("IPv4 local: 192.168.11.194"));
        assertTrue(report.contains("TCP: 6891 LISTEN"));
        assertTrue(report.contains("PCP: disponível — TCP local 6891 → público 43127"));
        assertTrue(report.contains("Porta pública confirmada: não (observada: 43127)"));
        assertTrue(report.contains("Estado: FIREWALLED / OUTBOUND ONLY"));
    }
}
