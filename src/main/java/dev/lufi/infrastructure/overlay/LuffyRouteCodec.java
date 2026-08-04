package dev.lufi.infrastructure.overlay;

import bt.net.buffer.ByteBufferView;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Codec binario estrito e limitado para {@code lf_route} v2. */
public final class LuffyRouteCodec {
    private static final int COMMON_SIZE = 1 + 1 + 16;
    private static final int FIND_NODE_BASE_SIZE = COMMON_SIZE + LuffyNodeId.BINARY_LENGTH * 2 + 20 + 1 + Long.BYTES + 1;
    private static final int NODE_FOUND_SIZE = COMMON_SIZE + LuffyNodeId.BINARY_LENGTH * 2 + 1 + 1;
    private static final int NODE_NOT_FOUND_SIZE = COMMON_SIZE + LuffyNodeId.BINARY_LENGTH;
    private static final int ROUTE_ERROR_SIZE = NODE_NOT_FOUND_SIZE + 1;
    public static final int MAX_PAYLOAD_SIZE = FIND_NODE_BASE_SIZE
            + LuffyNodeId.BINARY_LENGTH * LuffyRouteMessage.MAX_ROUTE_PARTICIPANTS;

    private static final int ROUTE_FLAG = 1;
    private static final int RENDEZVOUS_FLAG = 1 << 1;
    private static final int UTP_FLAG = 1 << 2;
    private static final int HOLE_PUNCH_FLAG = 1 << 3;
    private static final int KNOWN_CAPABILITY_FLAGS = ROUTE_FLAG | RENDEZVOUS_FLAG | UTP_FLAG | HOLE_PUNCH_FLAG;

    public byte[] encode(LuffyRouteMessage message) {
        if (message == null) throw new IllegalArgumentException("mensagem lf_route obrigatoria");
        ByteBuffer buffer = ByteBuffer.allocate(expectedPayloadSize(message));
        buffer.put((byte) message.protocolVersion());
        buffer.put((byte) message.type().code());
        putUuid(buffer, message.requestId());
        switch (message.type()) {
            case FIND_NODE -> {
                buffer.put(message.requesterNodeId().asBinary());
                buffer.put(message.targetNodeId().asBinary());
                buffer.put(java.util.HexFormat.of().parseHex(message.contentInfoHash()));
                buffer.put((byte) message.ttl());
                buffer.putLong(message.createdAt().toEpochMilli());
                buffer.put((byte) message.routeParticipants().size());
                message.routeParticipants().forEach(nodeId -> buffer.put(nodeId.asBinary()));
            }
            case NODE_FOUND -> {
                buffer.put(message.targetNodeId().asBinary());
                buffer.put(message.rendezvousNodeId().asBinary());
                buffer.put((byte) message.distance());
                buffer.put((byte) capabilityFlags(message.targetCapabilities()));
            }
            case NODE_NOT_FOUND -> buffer.put(message.targetNodeId().asBinary());
            case ROUTE_ERROR -> {
                buffer.put(message.targetNodeId().asBinary());
                buffer.put((byte) message.errorCode().code());
            }
        }
        return buffer.array();
    }

    public LuffyRouteMessage decode(byte[] payload) {
        if (payload == null || payload.length < COMMON_SIZE) throw new IllegalArgumentException("payload lf_route truncado");
        if (payload.length > MAX_PAYLOAD_SIZE) throw new IllegalArgumentException("payload lf_route excessivo");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int expected = expectedPayloadSize(buffer.duplicate());
        if (expected == 0 || payload.length != expected) throw new IllegalArgumentException("tamanho de payload lf_route invalido");
        requireSupportedVersion(Byte.toUnsignedInt(buffer.get()));
        LuffyRouteMessage.Type type = LuffyRouteMessage.Type.fromCode(Byte.toUnsignedInt(buffer.get()));
        UUID requestId = readUuid(buffer);
        return switch (type) {
            case FIND_NODE -> {
                LuffyNodeId requester = readNodeId(buffer);
                LuffyNodeId target = readNodeId(buffer);
                String infoHash = java.util.HexFormat.of().formatHex(readBytes(buffer, 20));
                int ttl = Byte.toUnsignedInt(buffer.get());
                Instant createdAt = Instant.ofEpochMilli(buffer.getLong());
                int participantCount = Byte.toUnsignedInt(buffer.get());
                List<LuffyNodeId> participants = new ArrayList<>(participantCount);
                for (int index = 0; index < participantCount; index++) participants.add(readNodeId(buffer));
                yield LuffyRouteMessage.decodedFindNode(requestId, requester, target, infoHash, ttl, createdAt, participants);
            }
            case NODE_FOUND -> LuffyRouteMessage.nodeFound(requestId, readNodeId(buffer), readNodeId(buffer),
                    Byte.toUnsignedInt(buffer.get()), readCapabilities(Byte.toUnsignedInt(buffer.get())));
            case NODE_NOT_FOUND -> LuffyRouteMessage.nodeNotFound(requestId, readNodeId(buffer));
            case ROUTE_ERROR -> LuffyRouteMessage.routeError(requestId, readNodeId(buffer),
                    LuffyRouteMessage.RouteErrorCode.fromCode(Byte.toUnsignedInt(buffer.get())));
        };
    }

    public int expectedPayloadSize(ByteBuffer source) {
        if (source == null || source.remaining() < 2) return 0;
        ByteBuffer header = source.duplicate();
        requireSupportedVersion(Byte.toUnsignedInt(header.get()));
        LuffyRouteMessage.Type type = LuffyRouteMessage.Type.fromCode(Byte.toUnsignedInt(header.get()));
        if (type != LuffyRouteMessage.Type.FIND_NODE) return expectedPayloadSize(type);
        if (header.remaining() < FIND_NODE_BASE_SIZE - 2) return 0;
        header.position(header.position() + (FIND_NODE_BASE_SIZE - 2 - 1));
        int participantCount = Byte.toUnsignedInt(header.get());
        if (participantCount < 1 || participantCount > LuffyRouteMessage.MAX_ROUTE_PARTICIPANTS) {
            throw new IllegalArgumentException("quantidade de participantes lf_route invalida");
        }
        return FIND_NODE_BASE_SIZE + participantCount * LuffyNodeId.BINARY_LENGTH;
    }

    public int expectedPayloadSize(ByteBufferView source) {
        if (source == null || source.remaining() < 2) return 0;
        ByteBufferView header = source.duplicate();
        requireSupportedVersion(Byte.toUnsignedInt(header.get()));
        LuffyRouteMessage.Type type = LuffyRouteMessage.Type.fromCode(Byte.toUnsignedInt(header.get()));
        if (type != LuffyRouteMessage.Type.FIND_NODE) return expectedPayloadSize(type);
        if (header.remaining() < FIND_NODE_BASE_SIZE - 2) return 0;
        for (int skipped = 0; skipped < FIND_NODE_BASE_SIZE - 2 - 1; skipped++) header.get();
        int participantCount = Byte.toUnsignedInt(header.get());
        if (participantCount < 1 || participantCount > LuffyRouteMessage.MAX_ROUTE_PARTICIPANTS) {
            throw new IllegalArgumentException("quantidade de participantes lf_route invalida");
        }
        return FIND_NODE_BASE_SIZE + participantCount * LuffyNodeId.BINARY_LENGTH;
    }

    private static int expectedPayloadSize(LuffyRouteMessage message) {
        return switch (message.type()) {
            case FIND_NODE -> FIND_NODE_BASE_SIZE + message.routeParticipants().size() * LuffyNodeId.BINARY_LENGTH;
            default -> expectedPayloadSize(message.type());
        };
    }

    private static int expectedPayloadSize(LuffyRouteMessage.Type type) {
        return switch (type) {
            case FIND_NODE -> FIND_NODE_BASE_SIZE;
            case NODE_FOUND -> NODE_FOUND_SIZE;
            case NODE_NOT_FOUND -> NODE_NOT_FOUND_SIZE;
            case ROUTE_ERROR -> ROUTE_ERROR_SIZE;
        };
    }

    private static void requireSupportedVersion(int version) {
        if (version != LuffyRouteMessage.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("versao lf_route incompativel");
        }
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) { return new UUID(buffer.getLong(), buffer.getLong()); }

    private static LuffyNodeId readNodeId(ByteBuffer buffer) { return LuffyNodeId.fromBinary(readBytes(buffer, LuffyNodeId.BINARY_LENGTH)); }

    private static byte[] readBytes(ByteBuffer buffer, int length) {
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    private static int capabilityFlags(LuffyRouteMessage.TargetCapabilities capabilities) {
        int flags = 0;
        if (capabilities.supportsRoute()) flags |= ROUTE_FLAG;
        if (capabilities.supportsRendezvous()) flags |= RENDEZVOUS_FLAG;
        if (capabilities.supportsUtp()) flags |= UTP_FLAG;
        if (capabilities.supportsHolePunch()) flags |= HOLE_PUNCH_FLAG;
        return flags;
    }

    private static LuffyRouteMessage.TargetCapabilities readCapabilities(int flags) {
        if ((flags & ~KNOWN_CAPABILITY_FLAGS) != 0) throw new IllegalArgumentException("flags de capacidade lf_route desconhecidas");
        return new LuffyRouteMessage.TargetCapabilities((flags & ROUTE_FLAG) != 0, (flags & RENDEZVOUS_FLAG) != 0,
                (flags & UTP_FLAG) != 0, (flags & HOLE_PUNCH_FLAG) != 0);
    }
}
