package dev.lufi.infrastructure.identity;

import bt.net.buffer.ByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import bt.protocol.handler.MessageHandler;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;

/** Adaptador do codec {@code lf_identity} para o pipeline de mensagens BEP 10 do bt-core. */
public final class LuffyIdentityMessageHandler implements MessageHandler<LuffyIdentityMessage> {
    private final LuffyIdentityCodec codec;

    public LuffyIdentityMessageHandler() { this(new LuffyIdentityCodec()); }
    LuffyIdentityMessageHandler(LuffyIdentityCodec codec) { this.codec = codec; }

    @Override public Collection<Class<? extends LuffyIdentityMessage>> getSupportedTypes() {
        return Collections.singleton(LuffyIdentityMessage.class);
    }

    @Override public Class<LuffyIdentityMessage> readMessageType(ByteBufferView buffer) {
        return LuffyIdentityMessage.class;
    }

    @Override public boolean encode(EncodingContext context, LuffyIdentityMessage message, ByteBuffer buffer) {
        byte[] payload = codec.encode(message);
        if (buffer.remaining() < payload.length) return false;
        buffer.put(payload);
        return true;
    }

    @Override public int decode(DecodingContext context, ByteBufferView buffer) {
        if (buffer.remaining() > LuffyIdentityCodec.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload lf_identity excessivo");
        }
        int available = buffer.remaining();
        int expected = codec.expectedPayloadSize(buffer);
        if (expected == 0 || available < expected) return 0;
        if (available != expected) {
            throw new IllegalArgumentException("payload lf_identity possui bytes desconhecidos: esperado=" + expected
                    + "; recebido=" + available);
        }
        byte[] payload = new byte[expected];
        buffer.get(payload);
        context.setMessage(codec.decode(payload));
        return expected;
    }
}
