package dev.lufi.infrastructure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantém evidências locais e externas sem misturar transportes, famílias IP ou
 * uma estimativa com uma confirmação mais forte.
 */
public final class ExternalEndpointRegistry {
    private final Map<Key, ObservedEndpoint> external = new ConcurrentHashMap<>();
    private final Map<Key, ObservedEndpoint> local = new ConcurrentHashMap<>();

    public ObservedEndpoint recordExternal(ObservedEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        removeExpired();
        if (!isPublicAddress(endpoint.address())) {
            throw new IllegalArgumentException("endereço local, privado ou reservado não pode ser registrado como público");
        }
        if (!endpoint.isExpired(Instant.now())) external.merge(Key.of(endpoint), endpoint, ExternalEndpointRegistry::prefer);
        return endpoint;
    }

    public ObservedEndpoint recordLocal(ObservedEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        removeExpired();
        if (!endpoint.isExpired(Instant.now())) local.merge(Key.of(endpoint), endpoint, ExternalEndpointRegistry::prefer);
        return endpoint;
    }

    public Optional<ObservedEndpoint> bestExternal(Transport transport) {
        return bestExternal(transport, InetAddress.class);
    }

    public <T extends InetAddress> Optional<ObservedEndpoint> bestExternal(Transport transport, Class<T> family) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(family, "family");
        removeExpired();
        return external.values().stream().filter(endpoint -> endpoint.transport() == transport)
                .filter(endpoint -> family.isInstance(endpoint.address()))
                .max(ExternalEndpointRegistry::comparePreference);
    }

    public List<ObservedEndpoint> externalSnapshot() {
        removeExpired();
        return external.values().stream().sorted((first, second) -> comparePreference(second, first)).toList();
    }

    public List<ObservedEndpoint> localSnapshot() {
        removeExpired();
        return local.values().stream().sorted((first, second) -> comparePreference(second, first)).toList();
    }

    public void removeExpired() {
        Instant now = Instant.now();
        external.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        local.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    static int comparePreference(ObservedEndpoint first, ObservedEndpoint second) {
        return Comparator.comparing(ObservedEndpoint::confirmed)
                .thenComparingInt(endpoint -> endpoint.source().reliability())
                .thenComparing(ObservedEndpoint::observedAt)
                .compare(first, second);
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address instanceof Inet6Address) return IpAddressClassifier.isGlobalUnicastIpv6(address);
        if (!(address instanceof Inet4Address ipv4)) return false;
        byte[] bytes = ipv4.getAddress();
        int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
        return first != 0 && first != 10 && first != 127 && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31) && !(first == 192 && second == 168)
                && !(first == 100 && second >= 64 && second <= 127);
    }

    private static ObservedEndpoint prefer(ObservedEndpoint current, ObservedEndpoint candidate) {
        return comparePreference(candidate, current) > 0 ? candidate : current;
    }

    private record Key(String address, int port, Transport transport) {
        private static Key of(ObservedEndpoint endpoint) {
            return new Key(endpoint.address().getHostAddress(), endpoint.port(), endpoint.transport());
        }
    }
}
