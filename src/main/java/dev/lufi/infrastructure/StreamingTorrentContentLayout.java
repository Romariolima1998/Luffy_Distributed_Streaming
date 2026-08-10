package dev.lufi.infrastructure;

import dev.lufi.application.port.TorrentContent;
import java.nio.file.Path;
import java.util.List;

/** Traduz os caminhos do metainfo para o layout criado por {@code FileSystemStorage}. */
final class StreamingTorrentContentLayout {
    private StreamingTorrentContentLayout() {
    }

    static TorrentContent resolve(Path storageRoot, String torrentName, List<List<String>> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return new TorrentContent(storageRoot, List.of());

        // Em torrents de arquivo único o FileSystemStorage grava diretamente em
        // storageRoot/nome-do-torrent. Não existe uma pasta intermediária com o
        // mesmo nome; criá-la aqui produzia nome.mkv/nome.mkv na UI.
        if (filePaths.size() == 1) {
            return new TorrentContent(storageRoot, List.of(storageRoot.resolve(torrentName)));
        }

        Path folder = storageRoot.resolve(torrentName);
        List<Path> files = filePaths.stream()
                .map(path -> path.stream().reduce(folder, Path::resolve, (left, right) -> right))
                .toList();
        return new TorrentContent(folder, files);
    }
}
