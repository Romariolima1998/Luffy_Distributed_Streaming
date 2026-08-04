package dev.lufi.infrastructure.overlay;

import bt.net.buffer.ByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import bt.protocol.handler.MessageHandler;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.function.IntSupplier;

/** Adaptador do codec {@code lf_route} para o pipeline de mensagens estendidas do bt-core. */
public final class LuffyRouteMessageHandler implements MessageHandler<LuffyRouteMessage> {
    private final LuffyRouteCodec codec;
    private final IntSupplier maxPayloadBytes;

    public LuffyRouteMessageHandler() { this(new LuffyRouteCodec(), () -> LuffyRouteCodec.MAX_PAYLOAD_SIZE); }
    public LuffyRouteMessageHandler(IntSupplier maxPayloadBytes) { this(new LuffyRouteCodec(), maxPayloadBytes); }
    LuffyRouteMessageHandler(LuffyRouteCodec codec) { this(codec, () -> LuffyRouteCodec.MAX_PAYLOAD_SIZE); }
    LuffyRouteMessageHandler(LuffyRouteCodec codec, IntSupplier maxPayloadBytes) {
        this.codec = codec;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override public Collection<Class<? extends LuffyRouteMessage>> getSupportedTypes() {
        return Collections.singleton(LuffyRouteMessage.class);
    }

    @Override public Class<LuffyRouteMessage> readMessageType(ByteBufferView buffer) { return LuffyRouteMessage.class; }

    @Override public boolean encode(EncodingContext context, LuffyRouteMessage message, ByteBuffer buffer) {
        byte[] payload = codec.encode(message);
        if (buffer.remaining() < payload.length) return false;
        buffer.put(payload);
        return true;
    }

    @Override public int decode(DecodingContext context, ByteBufferView buffer) {
        if (buffer.remaining() > Math.min(LuffyRouteCodec.MAX_PAYLOAD_SIZE, maxPayloadBytes.getAsInt())) throw new IllegalArgumentException("payload lf_route excessivo");
        int expected = codec.expectedPayloadSize(buffer);
        if (expected == 0 || buffer.remaining() < expected) return 0;
        if (buffer.remaining() != expected) throw new IllegalArgumentException("payload lf_route possui bytes desconhecidos");
        byte[] payload = new byte[expected];
        buffer.get(payload);
        context.setMessage(codec.decode(payload));
        return expected;
    }
}
