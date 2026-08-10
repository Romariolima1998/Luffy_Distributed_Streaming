package dev.lufi.infrastructure;

import bt.dht.DHTConfig;
import bt.net.InetPeerAddress;
import java.util.List;
import java.util.Objects;

/**
 * Bootstrap adicional para a DHT IPv4. O bt-dht continua usando os tres
 * routers publicos que ja possui; este no e um fallback independente quando
 * aqueles routers estiverem indisponiveis na rede do usuario.
 */
final class DhtBootstrapNodes {
    private static final List<InetPeerAddress> IPV4_FALLBACKS = List.of(
            new InetPeerAddress("dht.libtorrent.org", 25_401));

    private DhtBootstrapNodes() { }

    static void configure(DHTConfig config, boolean ipv6) {
        Objects.requireNonNull(config, "config");
        // Mantem os routers publicos padrao do bt-dht e acrescenta o fallback.
        config.setShouldUseRouterBootstrap(true);
        config.setBootstrapNodes(ipv6 ? List.of() : IPV4_FALLBACKS);
    }

    static List<InetPeerAddress> ipv4Fallbacks() { return IPV4_FALLBACKS; }
}
