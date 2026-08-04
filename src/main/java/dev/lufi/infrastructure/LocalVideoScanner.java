package dev.lufi.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

/** Scanner deliberadamente limitado a extensões de mídia conhecidas. */
public final class LocalVideoScanner {
    private static final List<String> EXTENSIONS = List.of(
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".mpeg", ".mpg",
            ".ts", ".m2ts", ".mts", ".wmv", ".flv", ".3gp", ".ogv", ".vob", ".asf");
    public List<Path> scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Escolha uma pasta que contenha vídeos.");
        try (Stream<Path> paths = Files.find(root, Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile() && isVideo(path))) {
            return paths.toList();
        }
    }
    public String fingerprint(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private boolean isVideo(Path p) { String n = p.getFileName().toString().toLowerCase(); return EXTENSIONS.stream().anyMatch(n::endsWith); }
}
