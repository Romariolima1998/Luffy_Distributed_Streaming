package dev.lufi.ui;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Agrupa picos de diagnósticos em poucas atualizações visuais. */
final class DiagnosticTextAreaAppender implements Consumer<String> {
    private static final int MAX_LINES_PER_UPDATE = 80;
    private static final int MAX_VISIBLE_CHARACTERS = 160_000;

    private final TextArea output;
    private final ArrayDeque<String> pendingLines = new ArrayDeque<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    DiagnosticTextAreaAppender(TextArea output) {
        this.output = output;
    }

    @Override public void accept(String line) {
        synchronized (pendingLines) {
            pendingLines.addLast(line);
        }
        if (flushQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flush);
        }
    }

    private void flush() {
        StringBuilder batch = new StringBuilder();
        synchronized (pendingLines) {
            for (int count = 0; count < MAX_LINES_PER_UPDATE && !pendingLines.isEmpty(); count++) {
                if (!batch.isEmpty()) batch.append(System.lineSeparator());
                batch.append(pendingLines.removeFirst());
            }
        }
        if (!batch.isEmpty()) {
            if (!output.getText().isEmpty()) output.appendText(System.lineSeparator());
            output.appendText(batch.toString());
            trimVisibleOutput();
            output.positionCaret(output.getLength());
        }
        synchronized (pendingLines) {
            if (pendingLines.isEmpty()) {
                flushQueued.set(false);
                return;
            }
        }
        Platform.runLater(this::flush);
    }

    private void trimVisibleOutput() {
        int overflow = output.getLength() - MAX_VISIBLE_CHARACTERS;
        if (overflow <= 0) return;
        String text = output.getText();
        int lineEnd = text.indexOf(System.lineSeparator(), overflow);
        output.deleteText(0, lineEnd < 0 ? overflow : lineEnd + System.lineSeparator().length());
    }
}
