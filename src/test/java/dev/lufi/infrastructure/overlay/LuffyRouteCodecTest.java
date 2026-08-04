package dev.lufi.infrastructure.overlay;

import bt.net.InetPeer;
import bt.net.buffer.DelegatingByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuffyRouteCodecTest {
    private final LuffyRouteCodec codec = new LuffyRouteCodec();

    @Test void roundTripsFindNodeWithEveryRequiredField() {
        UUID requestId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-30T19:00:00Z");
        LuffyRouteMessage original = LuffyRouteMessage.findNode(requestId, node(1), node(2),
                "0123456789012345678901234567890123456789", 6, createdAt);

        byte[] payload = codec.encode(original);
        LuffyRouteMessage decoded = codec.decode(payload);

        assertEquals(LuffyRouteMessage.Type.FIND_NODE, decoded.type());
        assertEquals(requestId, decoded.requestId());
        assertEquals(node(1), decoded.requesterNodeId());
        assertEquals(node(2), decoded.targetNodeId());
        assertEquals("0123456789012345678901234567890123456789", decoded.contentInfoHash());
        assertEquals(6, decoded.ttl());
        assertEquals(createdAt, decoded.createdAt());
        assertEquals(java.util.List.of(node(1)), decoded.routeParticipants());
        assertEquals(payload.length, codec.expectedPayloadSize(ByteBuffer.wrap(payload)));
    }

    @Test void roundTripsFoundNotFoundAndErrorWithoutConnectionDisclosure() {
        UUID requestId = UUID.randomUUID();
        LuffyRouteMessage.TargetCapabilities capabilities = new LuffyRouteMessage.TargetCapabilities(true, true, true, true);
        LuffyRouteMessage found = codec.decode(codec.encode(LuffyRouteMessage.nodeFound(requestId, node(2), node(3), 4, capabilities)));
        LuffyRouteMessage missing = codec.decode(codec.encode(LuffyRouteMessage.nodeNotFound(requestId, node(2))));
        LuffyRouteMessage error = codec.decode(codec.encode(LuffyRouteMessage.routeError(requestId, node(2),
                LuffyRouteMessage.RouteErrorCode.NO_ROUTE)));

        assertEquals(node(3), found.rendezvousNodeId());
        assertEquals(4, found.distance());
        assertTrue(found.targetCapabilities().supportsRendezvous());
        assertEquals(LuffyRouteMessage.Type.NODE_NOT_FOUND, missing.type());
        assertEquals(LuffyRouteMessage.RouteErrorCode.NO_ROUTE, error.errorCode());
    }

    @Test void nodeFoundReplyCarriesOnlyCoordinationDataAndNoContentIdentifier() {
        LuffyRouteMessage reply = LuffyRouteMessage.nodeFound(UUID.randomUUID(), node(2), node(3), 4,
                new LuffyRouteMessage.TargetCapabilities(true, true, true, true));

        byte[] encoded = codec.encode(reply);

        assertEquals(1 + 1 + 16 + LuffyNodeId.BINARY_LENGTH * 2 + 1 + 1, encoded.length);
        assertEquals(LuffyRouteMessage.Type.NODE_FOUND, codec.decode(encoded).type());
    }

    @Test void messageHandlerRejectsTrailingOrExcessivePayload() {
        LuffyRouteMessage message = LuffyRouteMessage.findNode(UUID.randomUUID(), node(1), node(2),
                "0123456789012345678901234567890123456789", 3, Instant.parse("2026-07-30T19:00:00Z"));
        LuffyRouteMessageHandler handler = new LuffyRouteMessageHandler(codec);
        byte[] encoded = codec.encode(message);
        ByteBuffer output = ByteBuffer.allocate(encoded.length);
        assertTrue(handler.encode(new EncodingContext(InetPeer.build(address("127.0.0.1"), 6891)), message, output));

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        DecodingContext context = new DecodingContext(InetPeer.build(address("127.0.0.1"), 6891));
        assertThrows(IllegalArgumentException.class, () -> handler.decode(context,
                new DelegatingByteBufferView(ByteBuffer.wrap(trailing))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[LuffyRouteCodec.MAX_PAYLOAD_SIZE + 1]));
    }

    @Test void rejectsInvalidUuidTtlInfoHashAndCapabilityFlags() {
        assertThrows(IllegalArgumentException.class, () -> LuffyRouteMessage.findNode(new UUID(0, 0), node(1), node(2),
                "0123456789012345678901234567890123456789", 1, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> LuffyRouteMessage.findNode(UUID.randomUUID(), node(1), node(2),
                "not-a-hash", 1, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> LuffyRouteMessage.findNode(UUID.randomUUID(), node(1), node(2),
                "0123456789012345678901234567890123456789", LuffyRouteMessage.MAX_TTL + 1, Instant.now()));

        byte[] found = codec.encode(LuffyRouteMessage.nodeFound(UUID.randomUUID(), node(2), node(3), 1,
                new LuffyRouteMessage.TargetCapabilities(false, false, false, false)));
        found[found.length - 1] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(found));
    }

    private static LuffyNodeId node(int fill) {
        byte[] bytes = new byte[LuffyNodeId.BINARY_LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return LuffyNodeId.fromBinary(bytes);
    }

    private static InetAddress address(String value) {
        try { return InetAddress.getByName(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
