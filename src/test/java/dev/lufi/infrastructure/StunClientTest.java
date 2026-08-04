package dev.lufi.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StunClientTest {
    @Test void decodesXorMappedIpv4AsUdpObservation() throws Exception {
        byte[] transaction = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] response = new byte[32];
        response[0] = 0x01; response[1] = 0x01; response[3] = 0x0c;
        writeInt(response, 4, 0x2112A442); System.arraycopy(transaction, 0, response, 8, transaction.length);
        response[21] = 0x20; response[23] = 0x08; response[25] = 0x01;
        int port = 54_321 ^ 0x2112; response[26] = (byte) (port >>> 8); response[27] = (byte) port;
        byte[] address = InetAddress.getByName("203.0.113.7").getAddress(); byte[] cookie = new byte[] {0x21, 0x12, (byte) 0xa4, 0x42};
        for (int index = 0; index < 4; index++) response[28 + index] = (byte) (address[index] ^ cookie[index]);

        ObservedEndpoint endpoint = StunClient.decodeBindingResponse(response, transaction).orElseThrow();

        assertEquals("203.0.113.7", endpoint.address().getHostAddress());
        assertEquals(54_321, endpoint.port());
        assertEquals(Transport.UTP, endpoint.transport());
        assertEquals(ObservationSource.EXTERNAL_PROBE, endpoint.source());
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }
}
