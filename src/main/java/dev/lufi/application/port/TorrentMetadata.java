package dev.lufi.application.port;

import java.util.List;

/** Small, UI-safe summary of torrent metadata. It contains no piece data. */
public record TorrentMetadata(String name, long sizeBytes, int fileCount, List<String> trackers) {
    public TorrentMetadata {
        name = name == null || name.isBlank() ? "Torrent sem título" : name;
        sizeBytes = Math.max(0, sizeBytes);
        fileCount = Math.max(0, fileCount);
        trackers = trackers == null ? List.of() : List.copyOf(trackers);
    }

    public static TorrentMetadata unavailable(String fallbackName) {
        return new TorrentMetadata(fallbackName, 0, 0, List.of());
    }

    public boolean complete() {
        return fileCount > 0 || sizeBytes > 0;
    }
}
