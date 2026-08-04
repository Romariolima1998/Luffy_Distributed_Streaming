package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

/**
 * Uma evidência temporal de endpoint. A confirmação significa que a fonte
 * confirmou esse mapeamento; alcance TCP entrante continua sendo decidido pela
 * política de conectividade separada.
 */
public record ObservedEndpoint(
        InetAddress address,
        int port,
        Transport transport,
        ObservationSource source,
        Instant observedAt,
        Instant expiresAt,
        boolean confirmed) {

    public ObservedEndpoint {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Porta externa inválida");
        if (expiresAt.isBefore(observedAt)) throw new IllegalArgumentException("A expiração não pode anteceder a observação");
    }

    /** Compatibilidade de leitura com diagnósticos anteriores. */
    public InetAddress publicIp() { return address; }
    /** Compatibilidade de leitura com diagnósticos anteriores. */
    public int publicPort() { return port; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(Objects.requireNonNull(now, "now")); }

    public String display() {
        String host = address instanceof java.net.Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        return host + ":" + port;
    }

    public static ObservationSource mappingSource(String mechanism) {
        if ("PCP".equalsIgnoreCase(mechanism)) return ObservationSource.PCP;
        if ("NAT-PMP".equalsIgnoreCase(mechanism) || "NATPMP".equalsIgnoreCase(mechanism)) return ObservationSource.NAT_PMP;
        return ObservationSource.UPNP;
    }
}
