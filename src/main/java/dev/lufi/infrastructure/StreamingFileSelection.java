package dev.lufi.infrastructure;

import java.util.List;

/** Normaliza a identidade do arquivo escolhida pela UI antes de aplicá-la ao torrent. */
final class StreamingFileSelection {
    private StreamingFileSelection() {
    }

    static boolean matches(String requestedRelativePath, List<String> torrentPathElements) {
        if (requestedRelativePath == null) return false;
        String requested = normalize(requestedRelativePath);
        String candidate = normalize(String.join("/", torrentPathElements == null ? List.of() : torrentPathElements));
        if (requested.equals(candidate)) return true;

        // Alguns torrents de um só arquivo representam o caminho como vazio no
        // seletor, enquanto os metadados posteriormente expõem o nome do arquivo.
        // Nesse caso não há outro arquivo para selecionar: o caminho vazio é o
        // próprio conteúdo solicitado. Não aplicar esta exceção a caminhos de
        // pastas, pois eles precisam continuar com correspondência exata.
        return candidate.isEmpty() && !requested.isEmpty() && !requested.contains("/");
    }

    static String normalize(String path) {
        if (path == null || path.isBlank() || ".".equals(path.trim())) return "";
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }
}
