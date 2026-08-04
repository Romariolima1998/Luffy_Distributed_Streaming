package dev.lufi.infrastructure;

/** Indica incompatibilidade ou falha da fronteira reflexiva com o bt-core. */
public final class BtCoreIntegrationException extends IllegalStateException {
    BtCoreIntegrationException(String message) {
        super(message);
    }

    BtCoreIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
