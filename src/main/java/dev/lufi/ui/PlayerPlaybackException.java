package dev.lufi.ui;

import java.util.Objects;

/** Falha do backend descrita sem expor exceções internas do libVLC à UI. */
final class PlayerPlaybackException extends RuntimeException {
    private final PlayerErrorCode code;

    PlayerPlaybackException(PlayerErrorCode code, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
    }

    PlayerPlaybackException(PlayerErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    PlayerErrorCode code() {
        return code;
    }

    static PlayerPlaybackException from(Throwable error, String playerSource) {
        if (error instanceof PlayerPlaybackException known) return known;
        PlayerErrorCode code = "TORRENT_HTTP".equals(playerSource)
                ? PlayerErrorCode.HTTP_STREAM_FAILED
                : PlayerErrorCode.UNKNOWN;
        return new PlayerPlaybackException(code, detail(error), error);
    }

    static String detail(Throwable error) {
        if (error == null) return "sem detalhe";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
