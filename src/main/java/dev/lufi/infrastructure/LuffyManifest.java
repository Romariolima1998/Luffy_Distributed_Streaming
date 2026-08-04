package dev.lufi.infrastructure;

import dev.lufi.domain.MagnetLink;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Extensão do magnet Luffy que permite listar vídeos antes do download de conteúdo. */
public final class LuffyManifest {
    private static final String PARAMETER = "x.luffy.files";
    private LuffyManifest() { }
    public static String encode(List<String> relativeVideoPaths) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("\n", relativeVideoPaths).getBytes(StandardCharsets.UTF_8));
    }
    public static List<String> decode(MagnetLink magnet) {
        String encoded = magnet.parameters().get(PARAMETER);
        if (encoded == null || encoded.isBlank()) return List.of();
        try { return List.of(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\n")).stream().filter(value -> !value.isBlank()).toList(); }
        catch (IllegalArgumentException invalid) { return List.of(); }
    }
    public static String parameter() { return PARAMETER; }
}
