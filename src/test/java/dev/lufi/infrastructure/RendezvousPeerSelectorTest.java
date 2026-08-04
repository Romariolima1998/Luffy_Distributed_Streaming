package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousPeerSelectorTest {
    private static final String INFO_HASH = "0123456789012345678901234567890123456789";

    @Test void choosesConnectedBep55PeerInSameSwarmAndNeverTheTargetItself() throws Exception {
        var target = InetAddress.getByName("203.0.113.20");
        var relay = new RendezvousPeerSelector.Candidate("c", INFO_HASH, InetAddress.getByName("203.0.113.30"), 6891, 0, true);
        var targetCandidate = new RendezvousPeerSelector.Candidate("a", INFO_HASH, target, 43127, 43817, true);
        var otherSwarm = new RendezvousPeerSelector.Candidate("b", "ffffffffffffffffffffffffffffffffffffffff",
                InetAddress.getByName("203.0.113.40"), 6891, 0, true);

        var selected = RendezvousPeerSelector.select(INFO_HASH, target, 43817, List.of(targetCandidate, otherSwarm, relay));

        assertEquals(relay, selected.orElseThrow());
    }

    @Test void reportsNoRendezvousWhenThereIsNoOtherCapableConnectedPeer() throws Exception {
        var target = InetAddress.getByName("203.0.113.20");
        var targetCandidate = new RendezvousPeerSelector.Candidate("a", INFO_HASH, target, 43127, 0, true);
        var unsupported = new RendezvousPeerSelector.Candidate("b", INFO_HASH, InetAddress.getByName("203.0.113.30"), 6891, 0, false);

        assertTrue(RendezvousPeerSelector.select(INFO_HASH, target, 43127, List.of(targetCandidate, unsupported)).isEmpty());
    }
}
