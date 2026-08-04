package dev.lufi.infrastructure.rendezvous;

import bt.metainfo.TorrentId;
import dev.lufi.infrastructure.identity.LuffyNodeId;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Codec binario fixo, estrito e pequeno do protocolo {@code lf_rendezvous}. */
public final class LuffyRendezvousCodec {
    private static final int ENDPOINT_SIZE = 1 + 1 + Short.BYTES + 16;
    public static final int PAYLOAD_SIZE = 1 + 1 + 16 + 16 + LuffyNodeId.BINARY_LENGTH * 3 + 20 + 1 + 1 + ENDPOINT_SIZE;
    public static final int MAX_PAYLOAD_SIZE = PAYLOAD_SIZE;

    public byte[] encode(LuffyRendezvousMessage message) {
        if (message == null) throw new IllegalArgumentException("mensagem lf_rendezvous obrigatoria");
        ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_SIZE);
        buffer.put((byte) message.protocolVersion());
        buffer.put((byte) message.type().code());
        putUuid(buffer, message.sessionId());
        putUuid(buffer, message.routeRequestId());
        buffer.put(message.requesterNodeId().asBinary());
        buffer.put(message.targetNodeId().asBinary());
        buffer.put(message.rendezvousNodeId().asBinary());
        buffer.put(message.contentTorrentId().getBytes());
        buffer.put((byte) message.direction().code());
        buffer.put((byte) message.code().code());
        putEndpoint(buffer, message.endpoint());
        return buffer.array();
    }

    public LuffyRendezvousMessage decode(byte[] payload) {
        if (payload == null || payload.length != PAYLOAD_SIZE) throw new IllegalArgumentException("tamanho lf_rendezvous invalido");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int version = Byte.toUnsignedInt(buffer.get());
        if (version != LuffyRendezvousMessage.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("versao lf_rendezvous nao suportada: " + version);
        }
        return LuffyRendezvousMessage.decoded(LuffyRendezvousMessage.Type.fromCode(Byte.toUnsignedInt(buffer.get())),
                readUuid(buffer), readUuid(buffer), readNodeId(buffer), readNodeId(buffer), readNodeId(buffer),
                TorrentId.fromBytes(readBytes(buffer, 20)),
                LuffyRendezvousMessage.Direction.fromCode(Byte.toUnsignedInt(buffer.get())),
                LuffyRendezvousMessage.Code.fromCode(Byte.toUnsignedInt(buffer.get())), readEndpoint(buffer));
    }

    private static void putUuid(ByteBuffer buffer, UUID value) { buffer.putLong(value.getMostSignificantBits()); buffer.putLong(value.getLeastSignificantBits()); }
    private static UUID readUuid(ByteBuffer buffer) { return new UUID(buffer.getLong(), buffer.getLong()); }
    private static LuffyNodeId readNodeId(ByteBuffer buffer) { return LuffyNodeId.fromBinary(readBytes(buffer, LuffyNodeId.BINARY_LENGTH)); }
    private static byte[] readBytes(ByteBuffer buffer, int length) { byte[] value = new byte[length]; buffer.get(value); return value; }
    private static void putEndpoint(ByteBuffer buffer, java.util.Optional<LuffyRendezvousMessage.RendezvousEndpoint> endpoint) {
        if (endpoint.isEmpty()) { buffer.put((byte) 0).put((byte) 0).putShort((short) 0).put(new byte[16]); return; }
        byte[] address = endpoint.get().address().getAddress();
        buffer.put((byte) 1).put((byte) (address.length == 4 ? 0 : 1)).putShort((short) endpoint.get().port());
        buffer.put(address);
        if (address.length == 4) buffer.put(new byte[12]);
    }
    private static LuffyRendezvousMessage.RendezvousEndpoint readEndpoint(ByteBuffer buffer) {
        boolean present = Byte.toUnsignedInt(buffer.get()) == 1;
        int family = Byte.toUnsignedInt(buffer.get());
        int port = Short.toUnsignedInt(buffer.getShort());
        byte[] padded = readBytes(buffer, 16);
        if (!present) {
            if (family != 0 || port != 0 || !allZero(padded, 0)) {
                throw new IllegalArgumentException("endpoint ausente lf_rendezvous invalido");
            }
            return null;
        }
        int length = family == 0 ? 4 : family == 1 ? 16 : -1;
        if (length < 0) throw new IllegalArgumentException("familia endpoint lf_rendezvous invalida");
        if (length == 4 && !allZero(padded, 4)) {
            throw new IllegalArgumentException("padding IPv4 lf_rendezvous invalido");
        }
        try {
            return new LuffyRendezvousMessage.RendezvousEndpoint(java.net.InetAddress.getByAddress(java.util.Arrays.copyOf(padded, length)), port);
        } catch (java.net.UnknownHostException error) { throw new IllegalArgumentException("endpoint lf_rendezvous invalido", error); }
    }
    private static boolean allZero(byte[] bytes, int offset) {
        for (int index = offset; index < bytes.length; index++) if (bytes[index] != 0) return false;
        return true;
    }
}
