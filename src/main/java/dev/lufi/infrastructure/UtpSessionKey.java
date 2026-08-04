package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Identifica uma sessão uTP no espaço de nomes do endpoint remoto, e não apenas
 * no espaço de 16 bits do connection id.
 */
public record UtpSessionKey(InetAddress remoteAddress, int remotePort, int receiveConnectionId) {
    public UtpSessionKey {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        if (remotePort < 1 || remotePort > 65_535) {
            throw new IllegalArgumentException("Invalid remote port");
        }
        if (receiveConnectionId < 0 || receiveConnectionId > 0xFFFF) {
            throw new IllegalArgumentException("Invalid uTP connection ID");
        }
    }
}
