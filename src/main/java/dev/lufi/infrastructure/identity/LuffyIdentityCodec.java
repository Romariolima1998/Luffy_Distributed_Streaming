package dev.lufi.infrastructure.identity;

import bt.net.buffer.ByteBufferView;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Codec binario estrito e limitado da mensagem {@code lf_identity} versao 1. */
public final class LuffyIdentityCodec {
    static final int HEADER_SIZE = 1 + LuffyNodeId.BINARY_LENGTH + 1;
    static final int MIN_PAYLOAD_SIZE = HEADER_SIZE + 1;
    public static final int MAX_PAYLOAD_SIZE = HEADER_SIZE + LuffyIdentityMessage.MAX_CLIENT_VERSION_BYTES + 1;

    private static final int ROUTE_FLAG = 1;
    private static final int RENDEZVOUS_FLAG = 1 << 1;
    private static final int UTP_FLAG = 1 << 2;
    private static final int HOLE_PUNCH_FLAG = 1 << 3;
    private static final int KNOWN_FLAGS = ROUTE_FLAG | RENDEZVOUS_FLAG | UTP_FLAG | HOLE_PUNCH_FLAG;

    public byte[] encode(LuffyIdentityMessage message) {
        if (message == null) throw new IllegalArgumentException("mensagem lf_identity obrigatoria");
        byte[] clientVersion = message.clientVersion().getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(HEADER_SIZE + clientVersion.length + 1);
        payload.put((byte) message.protocolVersion());
        payload.put(message.nodeId().asBinary());
        payload.put((byte) clientVersion.length);
        payload.put(clientVersion);
        payload.put((byte) flags(message));
        return payload.array();
    }

    public LuffyIdentityMessage decode(byte[] payload) {
        if (payload == null || payload.length < MIN_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload lf_identity truncado");
        }
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload lf_identity excede " + MAX_PAYLOAD_SIZE + " bytes");
        }
        ByteBuffer source = ByteBuffer.wrap(payload);
        int protocolVersion = Byte.toUnsignedInt(source.get());
        if (protocolVersion != LuffyIdentityMessage.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("versao lf_identity incompativel: " + protocolVersion);
        }
        byte[] nodeId = new byte[LuffyNodeId.BINARY_LENGTH];
        source.get(nodeId);
        int clientVersionLength = Byte.toUnsignedInt(source.get());
        int expectedLength = HEADER_SIZE + clientVersionLength + 1;
        if (clientVersionLength > LuffyIdentityMessage.MAX_CLIENT_VERSION_BYTES || payload.length != expectedLength) {
            throw new IllegalArgumentException("tamanho de payload lf_identity invalido");
        }
        byte[] clientVersion = new byte[clientVersionLength];
        source.get(clientVersion);
        int flags = Byte.toUnsignedInt(source.get());
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("flags lf_identity desconhecidas");
        }
        return new LuffyIdentityMessage(protocolVersion, LuffyNodeId.fromBinary(nodeId), decodeClientVersion(clientVersion),
                (flags & ROUTE_FLAG) != 0, (flags & RENDEZVOUS_FLAG) != 0,
                (flags & UTP_FLAG) != 0, (flags & HOLE_PUNCH_FLAG) != 0);
    }

    /** Retorna zero se ainda nao ha bytes suficientes para saber o tamanho do payload. */
    public int expectedPayloadSize(ByteBuffer source) {
        if (source == null || source.remaining() < HEADER_SIZE) return 0;
        ByteBuffer header = source.duplicate();
        header.get();
        header.position(header.position() + LuffyNodeId.BINARY_LENGTH);
        int clientVersionLength = Byte.toUnsignedInt(header.get());
        if (clientVersionLength > LuffyIdentityMessage.MAX_CLIENT_VERSION_BYTES) {
            throw new IllegalArgumentException("clientVersion lf_identity excede o limite");
        }
        return HEADER_SIZE + clientVersionLength + 1;
    }

    /** Variante sem copia para o decoder de mensagens do bt-core. */
    public int expectedPayloadSize(ByteBufferView source) {
        if (source == null || source.remaining() < HEADER_SIZE) return 0;
        ByteBufferView header = source.duplicate();
        header.get();
        header.position(header.position() + LuffyNodeId.BINARY_LENGTH);
        int clientVersionLength = Byte.toUnsignedInt(header.get());
        if (clientVersionLength > LuffyIdentityMessage.MAX_CLIENT_VERSION_BYTES) {
            throw new IllegalArgumentException("clientVersion lf_identity excede o limite");
        }
        return HEADER_SIZE + clientVersionLength + 1;
    }

    private int flags(LuffyIdentityMessage message) {
        int flags = 0;
        if (message.supportsRoute()) flags |= ROUTE_FLAG;
        if (message.supportsRendezvous()) flags |= RENDEZVOUS_FLAG;
        if (message.supportsUtp()) flags |= UTP_FLAG;
        if (message.supportsHolePunch()) flags |= HOLE_PUNCH_FLAG;
        return flags;
    }

    private String decodeClientVersion(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("clientVersion lf_identity nao esta em UTF-8 valido", error);
        }
    }
}
