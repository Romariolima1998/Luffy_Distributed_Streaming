package dev.lufi.infrastructure.bootstrap;

import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficialBootstrapSwarmTest {

    @Test void loadsTheVersionedOfficialArtifactsWithTheExpectedInfoHash() {
        OfficialBootstrapSwarm swarm = OfficialBootstrapSwarm.loadAndValidate();

        assertEquals(OfficialBootstrapSwarm.INFO_HASH, HexFormat.of().formatHex(swarm.torrentId().getBytes()));
        assertEquals(OfficialBootstrapSwarm.INFO_HASH, swarm.magnet().infoHash());
        assertEquals("Olá Luffy", swarm.magnet().displayName().orElseThrow());
        assertArrayEquals("Olá Luffy".getBytes(StandardCharsets.UTF_8), resource(OfficialBootstrapSwarm.TEXT_RESOURCE));
        assertEquals(OfficialBootstrapSwarm.MAGNET_URI, new String(resource(OfficialBootstrapSwarm.MAGNET_RESOURCE), StandardCharsets.US_ASCII));
    }

    @Test void theOfficialMetainfoIsAcceptedByBtCore() {
        Torrent torrent = new MetadataService().fromByteArray(resource(OfficialBootstrapSwarm.TORRENT_RESOURCE));

        assertEquals(OfficialBootstrapSwarm.INFO_HASH, HexFormat.of().formatHex(torrent.getTorrentId().getBytes()));
        assertEquals("ola-luffy.txt", torrent.getName());
        assertEquals(10, torrent.getSize());
        assertEquals(1_048_576, torrent.getChunkSize());
    }

    @Test void rejectsAnyModificationOfTheOfficialText() {
        byte[] content = resource(OfficialBootstrapSwarm.TEXT_RESOURCE).clone();
        content[0] = 'X';

        assertThrows(IllegalStateException.class, () -> OfficialBootstrapSwarm.validate(content,
                resource(OfficialBootstrapSwarm.TORRENT_RESOURCE), OfficialBootstrapSwarm.MAGNET_URI));
    }

    @Test void rejectsATorrentWhoseInfoDictionaryDoesNotMatchTheOfficialHash() {
        byte[] torrent = resource(OfficialBootstrapSwarm.TORRENT_RESOURCE).clone();
        torrent[torrent.length - 2] = 'x';

        assertThrows(IllegalStateException.class, () -> OfficialBootstrapSwarm.validate(resource(OfficialBootstrapSwarm.TEXT_RESOURCE),
                torrent, OfficialBootstrapSwarm.MAGNET_URI));
    }

    @Test void rejectsAMagnetDifferentFromTheVersionedOfficialMagnet() {
        String modifiedMagnet = OfficialBootstrapSwarm.MAGNET_URI.replace("Ol%C3%A1+Luffy", "Outro");

        assertThrows(IllegalStateException.class, () -> OfficialBootstrapSwarm.validate(resource(OfficialBootstrapSwarm.TEXT_RESOURCE),
                resource(OfficialBootstrapSwarm.TORRENT_RESOURCE), modifiedMagnet));
    }

    private static byte[] resource(String name) {
        try (InputStream input = OfficialBootstrapSwarmTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new AssertionError("Recurso de teste ausente: " + name);
            return input.readAllBytes();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }
}
