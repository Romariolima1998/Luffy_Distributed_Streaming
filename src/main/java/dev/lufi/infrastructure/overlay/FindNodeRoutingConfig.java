package dev.lufi.infrastructure.overlay;

import java.time.Duration;
import java.util.Objects;

/**
 * Limites locais da busca {@code lf_route}. Eles controlam somente mensagens
 * pequenas de overlay; nao alteram DHT, conexoes BitTorrent ou transferencia.
 */
public record FindNodeRoutingConfig(
        int defaultTtl,
        int maximumTtl,
        int maximumForwardPeers,
        Duration searchTimeout,
        Duration routeCacheTtl,
        Duration peerBackoff,
        Duration peerMessageWindow,
        int maximumMessagesPerPeerWindow) {

    public static final int INITIAL_DEFAULT_TTL = 4;
    public static final int INITIAL_MAXIMUM_TTL = 6;
    public static final int INITIAL_MAXIMUM_FORWARD_PEERS = 3;
    public static final Duration INITIAL_SEARCH_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration INITIAL_ROUTE_CACHE_TTL = Duration.ofMinutes(2);
    public static final Duration INITIAL_PEER_BACKOFF = Duration.ofSeconds(5);
    public static final Duration INITIAL_PEER_MESSAGE_WINDOW = Duration.ofMinutes(1);
    public static final int INITIAL_MAXIMUM_MESSAGES_PER_PEER_WINDOW = 24;

    public FindNodeRoutingConfig {
        if (defaultTtl < 1 || maximumTtl < defaultTtl || maximumTtl > LuffyRouteMessage.MAX_TTL) {
            throw new IllegalArgumentException("TTL de roteamento lf_route invalido");
        }
        if (maximumForwardPeers < 1) throw new IllegalArgumentException("fan-out lf_route invalido");
        searchTimeout = positive(searchTimeout, "searchTimeout");
        routeCacheTtl = positive(routeCacheTtl, "routeCacheTtl");
        peerBackoff = positive(peerBackoff, "peerBackoff");
        peerMessageWindow = positive(peerMessageWindow, "peerMessageWindow");
        if (maximumMessagesPerPeerWindow < 1) {
            throw new IllegalArgumentException("limite de mensagens lf_route invalido");
        }
    }

    public static FindNodeRoutingConfig defaults() {
        return new FindNodeRoutingConfig(INITIAL_DEFAULT_TTL, INITIAL_MAXIMUM_TTL,
                INITIAL_MAXIMUM_FORWARD_PEERS, INITIAL_SEARCH_TIMEOUT,
                INITIAL_ROUTE_CACHE_TTL, INITIAL_PEER_BACKOFF,
                INITIAL_PEER_MESSAGE_WINDOW, INITIAL_MAXIMUM_MESSAGES_PER_PEER_WINDOW);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " deve ser positivo");
        return value;
    }
}
