package dev.lufi.application.port;

import java.nio.file.Path;
import java.util.List;

/** Metadados recebidos do swarm antes de o conteúdo inteiro estar disponível. */
public record TorrentContent(Path folder, List<Path> files, TorrentMetadata metadata) {
    public TorrentContent(Path folder, List<Path> files) {
        this(folder, files, TorrentMetadata.unavailable("Torrent sem título"));
    }
}
