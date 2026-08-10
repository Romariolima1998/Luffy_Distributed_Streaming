package dev.lufi.domain;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Link de conteúdo BitTorrent validado antes de chegar à camada de rede. */
public record MagnetLink(String infoHash, Optional<String> displayName, Map<String, String> parameters, List<String> trackers) {
    public MagnetLink {
        if (!infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("O magnet precisa de um BTIH hexadecimal válido.");
        displayName = displayName == null ? Optional.empty() : displayName;
        parameters = Map.copyOf(parameters);
        trackers = trackers == null ? List.of() : trackers.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
    /** Mantem compatibilidade com os magnets internos criados antes de suportar tr repetido. */
    public MagnetLink(String infoHash, Optional<String> displayName, Map<String, String> parameters) {
        this(infoHash, displayName, parameters, trackerFrom(parameters));
    }
    public static MagnetLink parse(String value) {
        if (value == null || !value.startsWith("magnet:?")) throw new IllegalArgumentException("Link magnet inválido.");
        Map<String,String> values = new LinkedHashMap<>();
        List<String> trackers = new ArrayList<>();
        for (String part : value.substring(8).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) continue;
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            if (key.equalsIgnoreCase("tr")) trackers.add(decoded);
            values.put(key, decoded);
        }
        String xt = values.getOrDefault("xt", "");
        String prefix = "urn:btih:";
        if (!xt.regionMatches(true, 0, prefix, 0, prefix.length())) throw new IllegalArgumentException("Magnet sem xt=urn:btih.");
        return new MagnetLink(xt.substring(prefix.length()).toLowerCase(), Optional.ofNullable(values.get("dn")), values, trackers);
    }

    /** Reconstroi o magnet preservando cada tracker, inclusive os parâmetros tr repetidos. */
    public String toUri() {
        StringBuilder uri = new StringBuilder("magnet:?xt=urn:btih:").append(infoHash);
        displayName.ifPresent(name -> uri.append("&dn=").append(URLEncoder.encode(name, StandardCharsets.UTF_8)));
        parameters.forEach((key, value) -> {
            if (!key.equalsIgnoreCase("xt") && !key.equalsIgnoreCase("dn") && !key.equalsIgnoreCase("tr")
                    && value != null && !value.isBlank()) {
                uri.append('&').append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        trackers.forEach(tracker -> uri.append("&tr=").append(URLEncoder.encode(tracker, StandardCharsets.UTF_8)));
        return uri.toString();
    }

    private static List<String> trackerFrom(Map<String, String> parameters) {
        if (parameters == null) return List.of();
        String tracker = parameters.get("tr");
        return tracker == null || tracker.isBlank() ? List.of() : List.of(tracker);
    }
}
