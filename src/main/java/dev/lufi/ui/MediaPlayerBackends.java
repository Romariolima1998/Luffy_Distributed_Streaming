package dev.lufi.ui;

import java.util.Objects;

/** Ponto unico de selecao do backend: toda reproducao usa libVLC. */
final class MediaPlayerBackends {
    private MediaPlayerBackends() {
    }

    /**
     * O libVLC e o caminho unico de reprodução do Luffy. A UI antiga JavaFX
     * permanece apenas como estrutura de tela, sem ser selecionada.
     */
    static boolean requiresMediaBackend(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return true;
    }

    static MediaPlayerBackend createDefaultBackend() {
        return new LibVlcPlayerBackend();
    }
}
