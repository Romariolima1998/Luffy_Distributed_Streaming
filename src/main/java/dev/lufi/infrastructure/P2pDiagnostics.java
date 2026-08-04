package dev.lufi.infrastructure;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registro temporário e isolado para investigar a descoberta P2P.
 * Remover esta classe e a aba de diagnóstico não altera o fluxo do torrent.
 */
public final class P2pDiagnostics {
    /** Camadas independentes do núcleo P2P. O prefixo facilita leitura e cópia do log. */
    public enum Layer { DISCOVERY, CONNECTIVITY, NAT, HOLEPUNCH, UTP, BITTORRENT, DOWNLOAD, RESULT }
    /** Categorias estaveis para o terminal copiavel e para metricas locais. */
    public enum Category {
        LF_IDENTITY("LF-IDENTITY"),
        LF_OVERLAY("LF-OVERLAY"),
        LF_ROUTE("LF-ROUTE"),
        LF_RENDEZVOUS("LF-RENDEZVOUS"),
        LF_UTP("LF-UTP"),
        LF_BEP55("LF-BEP55"),
        LF_BT_BRIDGE("LF-BT-BRIDGE"),
        LF_SWARM_ASSIST("LF-SWARM-ASSIST");

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }
    private static final int MAX_LINES = 600;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final List<String> lines = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, LongAdder> eventMetrics = new ConcurrentHashMap<>();

    public void log(String message) {
        String line = "[" + TIME.format(LocalDateTime.now()) + "] " + message;
        synchronized (lines) {
            lines.add(line);
            if (lines.size() > MAX_LINES) lines.remove(0);
        }
        listeners.forEach(listener -> listener.accept(line));
    }

    public void log(Layer layer, String message) {
        log("[" + (layer == null ? Layer.RESULT : layer).name() + "] " + message);
    }

    /**
     * Registra evento de controle com campos pequenos e copiaveis. Esta API nao
     * recebe nomes de arquivos, caminhos nem payloads; os chamadores usam IDs,
     * contagens e endpoints publicos quando necessarios.
     */
    public void event(Category category, String event, Object... fields) {
        Category safeCategory = category == null ? Category.LF_OVERLAY : category;
        String safeEvent = requireEvent(event);
        if (fields == null || fields.length % 2 != 0) {
            throw new IllegalArgumentException("campos de evento devem ser pares chave/valor");
        }
        StringBuilder line = new StringBuilder("[").append(safeCategory.label()).append("] event=").append(safeEvent);
        for (int index = 0; index < fields.length; index += 2) {
            String key = requireKey(fields[index]);
            line.append(' ').append(key).append('=').append(safeValue(fields[index + 1]));
        }
        eventMetrics.computeIfAbsent(safeCategory.label() + "." + safeEvent, ignored -> new LongAdder()).increment();
        log(line.toString());
    }

    /** Snapshot de metricas locais; nao e transmitido para nenhum peer. */
    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        eventMetrics.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().sum()));
        return Map.copyOf(result);
    }

    public String snapshot() {
        synchronized (lines) { return String.join(System.lineSeparator(), lines); }
    }

    public void clear() {
        synchronized (lines) { lines.clear(); }
        eventMetrics.clear();
    }

    public void subscribe(Consumer<String> listener) {
        if (listener != null) listeners.add(listener);
    }

    private static String requireEvent(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("nome de evento invalido");
        }
        return value;
    }
    private static String requireKey(Object value) {
        String key = String.valueOf(value);
        if (!key.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
            throw new IllegalArgumentException("chave de campo invalida");
        }
        return key;
    }
    private static String safeValue(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value).replaceAll("[\\r\\n\\t]", "_");
        return text.length() <= 128 ? text : text.substring(0, 128) + "...";
    }
}
