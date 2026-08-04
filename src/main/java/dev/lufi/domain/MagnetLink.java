package dev.lufi.domain;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Link de conteúdo BitTorrent validado antes de chegar à camada de rede. */
public record MagnetLink(String infoHash, Optional<String> displayName, Map<String, String> parameters) {
    public MagnetLink {
        if (!infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("O magnet precisa de um BTIH hexadecimal válido.");
        displayName = displayName == null ? Optional.empty() : displayName;
        parameters = Map.copyOf(parameters);
    }
    public static MagnetLink parse(String value) {
        if (value == null || !value.startsWith("magnet:?")) throw new IllegalArgumentException("Link magnet inválido.");
        Map<String,String> values = new LinkedHashMap<>();
        for (String part : value.substring(8).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        String xt = values.getOrDefault("xt", "");
        String prefix = "urn:btih:";
        if (!xt.regionMatches(true, 0, prefix, 0, prefix.length())) throw new IllegalArgumentException("Magnet sem xt=urn:btih.");
        return new MagnetLink(xt.substring(prefix.length()).toLowerCase(), Optional.ofNullable(values.get("dn")), values);
    }
}

