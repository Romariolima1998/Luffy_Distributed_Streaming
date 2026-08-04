package dev.lufi.infrastructure;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

/** Cliente STUN mínimo (RFC 5389) usado somente para diagnosticar o endereço público do peer. */
final class StunClient {
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final SecureRandom RANDOM = new SecureRandom();

    Optional<ObservedEndpoint> discover(InetAddress bindAddress, String host, int port) {
        return discover(bindAddress, host, port, ignored -> { });
    }

    Optional<ObservedEndpoint> discover(InetAddress bindAddress, String host, int port, Consumer<String> diagnostics) {
        Consumer<String> logger = diagnostics == null ? ignored -> { } : diagnostics;
        try {
            for (InetAddress server : InetAddress.getAllByName(host)) {
                if (!sameFamily(bindAddress, server)) continue;
                byte[] transaction = new byte[12]; RANDOM.nextBytes(transaction);
                byte[] request = bindingRequest(transaction);
                try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(bindAddress, 0))) {
                    socket.connect(server, port); socket.setSoTimeout(3_500);
                    socket.send(new DatagramPacket(request, request.length));
                    byte[] received = new byte[1_024];
                    DatagramPacket response = new DatagramPacket(received, received.length);
                    socket.receive(response);
                    Optional<ObservedEndpoint> endpoint = decodeBindingResponse(
                            Arrays.copyOf(response.getData(), response.getLength()), transaction);
                    if (endpoint.isPresent()) return endpoint;
                }
            }
        } catch (Exception error) {
            logger.accept("STUN-like observation failed: " + describe(error) + ".");
        }
        return Optional.empty();
    }

    static Optional<ObservedEndpoint> decodeBindingResponse(byte[] response, byte[] transaction) {
        if (response.length < 20 || transaction.length != 12 || unsignedShort(response, 0) != 0x0101
                || readInt(response, 4) != MAGIC_COOKIE || !Arrays.equals(Arrays.copyOfRange(response, 8, 20), transaction)) return Optional.empty();
        int messageEnd = Math.min(response.length, 20 + unsignedShort(response, 2));
        for (int offset = 20; offset + 4 <= messageEnd;) {
            int type = unsignedShort(response, offset); int length = unsignedShort(response, offset + 2);
            int value = offset + 4; int next = value + ((length + 3) & ~3);
            if (next > messageEnd) return Optional.empty();
            if ((type == 0x0020 || type == 0x0001) && length >= 8) {
                boolean xor = type == 0x0020; int family = response[value + 1] & 0xff;
                int addressLength = family == 0x01 ? 4 : family == 0x02 ? 16 : 0;
                if (addressLength > 0 && length >= 4 + addressLength) {
                    int encodedPort = unsignedShort(response, value + 2);
                    int mappedPort = xor ? encodedPort ^ (MAGIC_COOKIE >>> 16) : encodedPort;
                    byte[] address = Arrays.copyOfRange(response, value + 4, value + 4 + addressLength);
                    if (xor) xorAddress(address, transaction);
                    try {
                        Instant now = Instant.now();
                        return Optional.of(new ObservedEndpoint(InetAddress.getByAddress(address), mappedPort,
                                Transport.UTP, ObservationSource.EXTERNAL_PROBE, now,
                                now.plus(EndpointObservationService.DEFAULT_ESTIMATE_TTL), false));
                    }
                    catch (java.net.UnknownHostException ignored) { return Optional.empty(); }
                }
            }
            offset = next;
        }
        return Optional.empty();
    }

    private static byte[] bindingRequest(byte[] transaction) {
        byte[] request = new byte[20]; request[1] = 0x01;
        writeInt(request, 4, MAGIC_COOKIE); System.arraycopy(transaction, 0, request, 8, transaction.length); return request;
    }
    private static boolean sameFamily(InetAddress first, InetAddress second) {
        return (first instanceof Inet4Address && second instanceof Inet4Address) || (first instanceof Inet6Address && second instanceof Inet6Address);
    }
    private static String describe(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
    private static void xorAddress(byte[] address, byte[] transaction) {
        byte[] cookie = new byte[] {0x21, 0x12, (byte) 0xA4, 0x42};
        for (int index = 0; index < address.length; index++) address[index] ^= index < 4 ? cookie[index] : transaction[index - 4];
    }
    private static int unsignedShort(byte[] bytes, int offset) { return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff); }
    private static int readInt(byte[] bytes, int offset) { return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff; }
    private static void writeInt(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16); bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value; }
}
