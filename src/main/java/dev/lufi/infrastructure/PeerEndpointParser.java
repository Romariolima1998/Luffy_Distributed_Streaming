package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/** Parser estrito para a pista x.pe de um magnet, incluindo IPv6 entre colchetes. */
final class PeerEndpointParser {
    private PeerEndpointParser() { }

    static InetSocketAddress parse(String value) throws UnknownHostException {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("x.pe vazio");
        String host;
        String port;
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing < 1 || closing + 2 > value.length() || value.charAt(closing + 1) != ':') {
                throw new IllegalArgumentException("x.pe IPv6 inválido");
            }
            host = value.substring(1, closing);
            port = value.substring(closing + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator < 1 || value.indexOf(':') != separator) {
                throw new IllegalArgumentException("x.pe IPv4 deve usar IP:porta");
            }
            host = value.substring(0, separator);
            port = value.substring(separator + 1);
        }
        int number;
        try {
            number = Integer.parseInt(port);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("porta x.pe inválida", error);
        }
        if (number < 1 || number > 65_535) throw new IllegalArgumentException("porta x.pe inválida");
        return new InetSocketAddress(InetAddress.getByName(host), number);
    }
}
