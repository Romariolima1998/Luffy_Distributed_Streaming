package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;

/** Gera metainfo BitTorrent v1 de arquivo único, com peças SHA-1 de 1 MiB. */
public final class TorrentMetainfoGenerator {
    private static final int PIECE_LENGTH = 1_048_576;
    public record PublishedTorrent(Path video, Path torrentFile, MagnetLink magnet) { }

    public PublishedTorrent publish(Path video, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        byte[] pieces = pieceHashes(video);
        byte[] info = infoDictionary(video.getFileName().toString(), Files.size(video), pieces);
        String hash = sha1(info);
        Path torrent = outputDirectory.resolve(safeName(video.getFileName().toString()) + ".torrent");
        Files.write(torrent, torrentFile(info));
        String magnetText = "magnet:?xt=urn:btih:" + hash + "&dn=" + java.net.URLEncoder.encode(video.getFileName().toString(), StandardCharsets.UTF_8);
        return new PublishedTorrent(video, torrent, MagnetLink.parse(magnetText));
    }
    /** Gera um único torrent multifile para uma biblioteca inteira. */
    public PublishedTorrent publishDirectory(Path root, Path outputDirectory) throws IOException {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("A biblioteca precisa ser uma pasta.");
        Files.createDirectories(outputDirectory);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("A pasta não possui arquivos para publicar.");
        byte[] info = directoryInfoDictionary(root, files, pieceHashes(files));
        String hash = sha1(info);
        Path torrent = outputDirectory.resolve(safeName(root.getFileName().toString()) + ".torrent");
        Files.write(torrent, torrentFile(info));
        List<String> videos = files.stream().filter(this::isVideo).map(path -> root.relativize(path).toString().replace('\\', '/')).toList();
        String magnetText = "magnet:?xt=urn:btih:" + hash + "&dn=" + java.net.URLEncoder.encode(root.getFileName().toString(), StandardCharsets.UTF_8)
                + "&" + LuffyManifest.parameter() + "=" + LuffyManifest.encode(videos);
        return new PublishedTorrent(root, torrent, MagnetLink.parse(magnetText));
    }
    private byte[] pieceHashes(Path video) throws IOException {
        try (var in = Files.newInputStream(video); var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[PIECE_LENGTH]; int read;
            while ((read = readPiece(in, buffer)) > 0) out.write(MessageDigest.getInstance("SHA-1").digest(java.util.Arrays.copyOf(buffer, read)));
            return out.toByteArray();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private byte[] pieceHashes(List<Path> files) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1"); byte[] buffer = new byte[PIECE_LENGTH]; int filled = 0;
            for (Path file : files) try (var in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer, filled, buffer.length - filled)) != -1) {
                    filled += read;
                    if (filled == buffer.length) { out.write(digest.digest(buffer)); filled = 0; }
                }
            }
            if (filled > 0) out.write(digest.digest(java.util.Arrays.copyOf(buffer, filled)));
            return out.toByteArray();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private int readPiece(java.io.InputStream in, byte[] buffer) throws IOException {
        int total = 0, read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) total += read;
        return total;
    }
    private byte[] infoDictionary(String name, long length, byte[] pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write('d');
        string(out, "length"); integer(out, length); string(out, "name"); string(out, name);
        string(out, "piece length"); integer(out, PIECE_LENGTH); string(out, "pieces"); bytes(out, pieces); out.write('e'); return out.toByteArray();
    }
    private byte[] directoryInfoDictionary(Path root, List<Path> files, byte[] pieces) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write('d'); string(out, "files"); out.write('l');
        for (Path file : files) {
            out.write('d'); string(out, "length"); integer(out, Files.size(file)); string(out, "path"); out.write('l');
            for (Path element : root.relativize(file)) string(out, element.toString()); out.write('e'); out.write('e');
        }
        out.write('e'); string(out, "name"); string(out, root.getFileName().toString());
        string(out, "piece length"); integer(out, PIECE_LENGTH); string(out, "pieces"); bytes(out, pieces); out.write('e'); return out.toByteArray();
    }
    private byte[] torrentFile(byte[] info) { ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write('d'); string(out, "info"); out.writeBytes(info); out.write('e'); return out.toByteArray(); }
    private void string(ByteArrayOutputStream out, String value) { bytes(out, value.getBytes(StandardCharsets.UTF_8)); }
    private void bytes(ByteArrayOutputStream out, byte[] value) { out.writeBytes(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII)); out.write(':'); out.writeBytes(value); }
    private void integer(ByteArrayOutputStream out, long value) { out.write('i'); out.writeBytes(Long.toString(value).getBytes(StandardCharsets.US_ASCII)); out.write('e'); }
    private String sha1(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private String safeName(String value) { return value.replaceAll("[\\\\/:*?\"<>|]", "_"); }
    private boolean isVideo(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return List.of(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".mpeg", ".mpg", ".ts", ".m2ts", ".mts", ".wmv", ".flv", ".3gp", ".ogv", ".vob", ".asf").stream().anyMatch(name::endsWith);
    }
}
