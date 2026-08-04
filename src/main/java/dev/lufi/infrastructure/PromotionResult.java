package dev.lufi.infrastructure;

import bt.net.ConnectionResult;
import java.util.Objects;

/** Resultado tipado da entrega de um canal ja estabelecido ao bt-core. */
public record PromotionResult(ConnectionResult connectionResult) {
    public PromotionResult {
        Objects.requireNonNull(connectionResult, "connectionResult");
    }

    public boolean isSuccess() {
        return connectionResult.isSuccess();
    }
}
