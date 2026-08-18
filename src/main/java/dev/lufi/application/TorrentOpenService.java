package dev.lufi.application;

import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import dev.lufi.application.port.TorrentOpenRequest;
import dev.lufi.application.port.TorrentMetadata;
import dev.lufi.domain.MagnetLink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;

/** Validates the two user-facing torrent inputs before either reaches the P2P gateway. */
public final class TorrentOpenService {
    private final MetadataService metadataService;

    public TorrentOpenService() {
        this(new MetadataService());
    }

    TorrentOpenService(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /** Accepts either a magnet URI or the path to a local .torrent file. */
    public TorrentOpenRequest open(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Informe um link magnet ou selecione um arquivo .torrent.");
        String input = value.trim();
        return input.regionMatches(true, 0, "magnet:?", 0, "magnet:?".length())
                ? openMagnet(input) : openTorrentFile(Path.of(input));
    }

    public TorrentOpenRequest openMagnet(String magnet) {
        return TorrentOpenRequest.magnet(MagnetLink.parse(magnet == null ? "" : magnet.trim()));
    }

    /**
     * Reads bencoded metadata now. The returned request retains the original file
     * so the BitTorrent engine can use the embedded metadata directly.
     */
    public TorrentOpenRequest openTorrentFile(Path torrentFile) {
        Path source = requireTorrentFile(torrentFile);
        Torrent torrent;
        try {
            torrent = metadataService.fromByteArray(Files.readAllBytes(source));
        } catch (IOException error) {
            throw new IllegalArgumentException("Não foi possível ler o arquivo .torrent: " + source.getFileName(), error);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("O arquivo selecionado não contém metadata BitTorrent válida.", error);
        }
        String infoHash = HexFormat.of().formatHex(torrent.getTorrentId().getBytes());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("xt", "urn:btih:" + infoHash);
        parameters.put("dn", torrent.getName());
        List<String> trackers = trackers(torrent);
        return TorrentOpenRequest.torrentFile(new MagnetLink(infoHash, Optional.ofNullable(torrent.getName()), parameters,
                trackers), source, new TorrentMetadata(torrent.getName(), torrent.getSize(), torrent.getFiles().size(), trackers));
    }

    private static Path requireTorrentFile(Path torrentFile) {
        if (torrentFile == null) throw new IllegalArgumentException("Selecione um arquivo .torrent.");
        Path source = torrentFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("O arquivo .torrent não existe ou não pode ser lido.");
        }
        String name = source.getFileName().toString();
        if (!name.regionMatches(true, Math.max(0, name.length() - 8), ".torrent", 0, 8)) {
            throw new IllegalArgumentException("Selecione um arquivo com extensão .torrent.");
        }
        return source;
    }

    private static List<String> trackers(Torrent torrent) {
        return torrent.getAnnounceKey().map(key -> key.isMultiKey()
                ? key.getTrackerUrls().stream().flatMap(List::stream).filter(value -> value != null && !value.isBlank()).distinct().toList()
                : List.of(key.getTrackerUrl())).orElse(List.of());
    }
}
