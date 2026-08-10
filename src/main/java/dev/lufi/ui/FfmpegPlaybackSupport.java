package dev.lufi.ui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Decide quando o player integrado deve usar os codecs FFmpeg em vez do JavaFX Media. */
final class FfmpegPlaybackSupport {
    private static final Set<String> FFMPEG_CONTAINERS = Set.of(
            "mkv", "webm", "avi", "flv", "vob", "asf", "wmv", "ts", "m2ts", "mts", "ogv"
    );

    private FfmpegPlaybackSupport() {
    }

    static boolean isRequiredFor(Path video) {
        if (video == null || video.getFileName() == null) {
            return false;
        }
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return FFMPEG_CONTAINERS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
