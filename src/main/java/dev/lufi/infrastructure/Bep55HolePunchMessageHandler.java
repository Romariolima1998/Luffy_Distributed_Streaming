package dev.lufi.infrastructure;

import bt.net.buffer.ByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import bt.protocol.handler.MessageHandler;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;

/** Codec estrito do payload BEP 55: tipo, familia, endereco, porta e erro. */
public final class Bep55HolePunchMessageHandler implements MessageHandler<Bep55HolePunchMessage> {
    @Override public Collection<Class<? extends Bep55HolePunchMessage>> getSupportedTypes() { return Collections.singleton(Bep55HolePunchMessage.class); }
    @Override public Class<Bep55HolePunchMessage> readMessageType(ByteBufferView buffer) { return Bep55HolePunchMessage.class; }

    @Override public boolean encode(EncodingContext context, Bep55HolePunchMessage message, ByteBuffer buffer) {
        byte[] address = message.address().getAddress();
        if (address.length != 4 && address.length != 16) throw new IllegalArgumentException("familia de endereco BEP 55 invalida");
        int required = 1 + 1 + address.length + 2 + 4;
        if (buffer.remaining() < required) return false;
        buffer.put((byte) message.type().id());
        buffer.put((byte) (address.length == 4 ? 0 : 1));
        buffer.put(address);
        buffer.putShort((short) message.port());
        buffer.putInt(message.errorCode().id());
        return true;
    }

    @Override public int decode(DecodingContext context, ByteBufferView buffer) {
        if (buffer.remaining() < 8) return 0;
        int type = buffer.get() & 0xff;
        int family = buffer.get() & 0xff;
        int length = family == 0 ? 4 : family == 1 ? 16 : -1;
        if (length < 0 || buffer.remaining() < length + 6) throw new IllegalArgumentException("payload BEP 55 invalido");
        byte[] address = new byte[length]; buffer.get(address);
        int port = Short.toUnsignedInt(buffer.getShort());
        int error = buffer.getInt();
        try {
            InetAddress endpoint = InetAddress.getByAddress(address);
            Bep55HolePunchMessage.Type messageType = Bep55HolePunchMessage.Type.fromId(type);
            Bep55HolePunchMessage.ErrorCode errorCode = Bep55HolePunchMessage.ErrorCode.fromId(error);
            Bep55HolePunchMessage message = switch (messageType) {
                case RENDEZVOUS -> Bep55HolePunchMessage.rendezvous(endpoint, port);
                case CONNECT -> Bep55HolePunchMessage.connect(endpoint, port);
                case ERROR -> Bep55HolePunchMessage.error(endpoint, port, errorCode);
            };
            context.setMessage(message);
            return 1 + 1 + length + 2 + 4;
        } catch (UnknownHostException errorValue) {
            throw new IllegalArgumentException("endereco BEP 55 invalido", errorValue);
        }
    }
}
