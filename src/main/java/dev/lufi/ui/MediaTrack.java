package dev.lufi.ui;

import java.util.Objects;

/**
 * Descricao independente de um stream de audio ou legenda disponivel na midia.
 *
 * <p>Ela mantem a UI e os demais backends isolados dos tipos internos do
 * libVLC. Consultar esta descricao nao muda a reproducao.</p>
 */
record MediaTrack(int id, String label, boolean selected) {
    MediaTrack {
        label = Objects.requireNonNullElse(label, "").trim();
        if (label.isEmpty()) label = "Faixa " + id;
    }
}
