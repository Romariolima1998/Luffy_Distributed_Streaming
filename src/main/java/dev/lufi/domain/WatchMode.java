package dev.lufi.domain;

/** Defines whether a magnet is temporary playback, selected-file sharing, or a full persistent download. */
public enum WatchMode {
    TEMPORARY,
    SHARE,
    DOWNLOAD;

    public boolean isTemporary() {
        return this == TEMPORARY;
    }

    /** Content that remains in the user's Luffy folder and may continue in the background. */
    public boolean isPersistentDownload() {
        return this == SHARE || this == DOWNLOAD;
    }

    /** Modes that retain interrupted files in the ordered download queue. */
    public boolean keepsFileQueue() {
        return this == SHARE || this == DOWNLOAD;
    }
}
