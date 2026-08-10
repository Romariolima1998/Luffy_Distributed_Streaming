package dev.lufi.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MagnetLinkTest {
    @Test void acceptsAndNormalizesAValidBtih() {
        var magnet = MagnetLink.parse("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Film");
        assertEquals("0123456789abcdef0123456789abcdef01234567", magnet.infoHash());
        assertEquals("Film", magnet.displayName().orElseThrow());
    }
    @Test void rejectsMissingOrInvalidHash() {
        assertThrows(IllegalArgumentException.class, () -> MagnetLink.parse("magnet:?dn=Film"));
        assertThrows(IllegalArgumentException.class, () -> MagnetLink.parse("magnet:?xt=urn:btih:abc"));
    }
    @Test void preservesEveryRepeatedTrackerWhenTheMagnetIsRebuilt() {
        String raw = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Film"
                + "&tr=udp%3A%2F%2Fone.example%3A80%2Fannounce"
                + "&tr=udp%3A%2F%2Ftwo.example%3A6969%2Fannounce"
                + "&tr=udp%3A%2F%2Fthree.example%3A1337%2Fannounce";

        var magnet = MagnetLink.parse(raw);

        assertEquals(List.of("udp://one.example:80/announce", "udp://two.example:6969/announce",
                "udp://three.example:1337/announce"), magnet.trackers());
        assertEquals(magnet.trackers(), MagnetLink.parse(magnet.toUri()).trackers());
    }
}
