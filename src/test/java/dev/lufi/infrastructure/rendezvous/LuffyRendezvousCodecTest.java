package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuffyRendezvousCodecTest {
    private static final TorrentId CONTENT = TorrentId.fromBytes(HexFormat.of().parseHex("0123456789012345678901234567890123456789"));

    @Test void roundTripsEveryControlMessageWithoutPayloadExpansion() {
        RendezvousSession session = session();
        LuffyRendezvousCodec codec = new LuffyRendezvousCodec();
        for (LuffyRendezvousMessage message : java.util.List.of(
                LuffyRendezvousMessage.request(session, endpoint(10)),
                LuffyRendezvousMessage.prepare(session, LuffyRendezvousMessage.Direction.TO_REQUESTER, endpoint(11)),
                LuffyRendezvousMessage.prepare(session, LuffyRendezvousMessage.Direction.TO_TARGET, endpoint(12)),
                LuffyRendezvousMessage.accepted(session, endpoint(13)),
                LuffyRendezvousMessage.rejected(session, LuffyRendezvousMessage.Code.TARGET_UNAVAILABLE),
                LuffyRendezvousMessage.result(session, LuffyRendezvousMessage.Code.PREPARED),
                LuffyRendezvousMessage.error(session, LuffyRendezvousMessage.Direction.TO_REQUESTER,
                        LuffyRendezvousMessage.Code.ROUTE_UNAVAILABLE))) {
            byte[] encoded = codec.encode(message);
            assertEquals(LuffyRendezvousCodec.PAYLOAD_SIZE, encoded.length);
            LuffyRendezvousMessage decoded = codec.decode(encoded);
            assertEquals(message.type(), decoded.type());
            assertEquals(message.sessionId(), decoded.sessionId());
            assertEquals(message.routeRequestId(), decoded.routeRequestId());
            assertEquals(message.requesterNodeId(), decoded.requesterNodeId());
            assertEquals(message.targetNodeId(), decoded.targetNodeId());
            assertEquals(message.rendezvousNodeId(), decoded.rendezvousNodeId());
            assertEquals(message.contentTorrentId(), decoded.contentTorrentId());
            assertEquals(message.direction(), decoded.direction());
            assertEquals(message.code(), decoded.code());
            assertEquals(message.endpoint(), decoded.endpoint());
        }
    }

    @Test void rejectsVersionSizeAndSemanticViolations() {
        LuffyRendezvousCodec codec = new LuffyRendezvousCodec();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[4]));
        byte[] unsupported = codec.encode(LuffyRendezvousMessage.request(session(), endpoint(10)));
        unsupported[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupported));
        assertThrows(IllegalArgumentException.class, () -> LuffyRendezvousMessage.result(session(), LuffyRendezvousMessage.Code.NONE));
    }

    private static RendezvousSession session() {
        Instant now = Instant.parse("2026-08-04T18:00:00Z");
        return new RendezvousSession(UUID.randomUUID(), UUID.randomUUID(), node(1), node(2), node(3), CONTENT,
                now, now.plusSeconds(30), RendezvousState.CREATED);
    }
    private static LuffyNodeId node(int fill) {
        byte[] value = new byte[LuffyNodeId.BINARY_LENGTH]; Arrays.fill(value, (byte) fill); return LuffyNodeId.fromBinary(value);
    }
    private static LuffyRendezvousMessage.RendezvousEndpoint endpoint(int octet) {
        try { return new LuffyRendezvousMessage.RendezvousEndpoint(java.net.InetAddress.getByName("203.0.113." + octet), 43_000 + octet); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
