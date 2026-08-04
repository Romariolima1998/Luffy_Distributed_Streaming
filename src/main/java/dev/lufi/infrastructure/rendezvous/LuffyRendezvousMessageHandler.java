package dev.lufi.infrastructure.rendezvous;

import bt.net.buffer.ByteBufferView;
import bt.protocol.DecodingContext;
import bt.protocol.EncodingContext;
import bt.protocol.handler.MessageHandler;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.function.IntSupplier;

/** Adaptador do codec para o pipeline de mensagens estendidas do bt-core. */
public final class LuffyRendezvousMessageHandler implements MessageHandler<LuffyRendezvousMessage> {
    private final LuffyRendezvousCodec codec = new LuffyRendezvousCodec();
    private final IntSupplier maxPayloadBytes;
    public LuffyRendezvousMessageHandler() { this(() -> LuffyRendezvousCodec.MAX_PAYLOAD_SIZE); }
    public LuffyRendezvousMessageHandler(IntSupplier maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    @Override public Collection<Class<? extends LuffyRendezvousMessage>> getSupportedTypes() {
        return Collections.singleton(LuffyRendezvousMessage.class);
    }
    @Override public Class<LuffyRendezvousMessage> readMessageType(ByteBufferView buffer) { return LuffyRendezvousMessage.class; }
    @Override public boolean encode(EncodingContext context, LuffyRendezvousMessage message, ByteBuffer buffer) {
        byte[] payload = codec.encode(message);
        if (buffer.remaining() < payload.length) return false;
        buffer.put(payload);
        return true;
    }
    @Override public int decode(DecodingContext context, ByteBufferView buffer) {
        if (buffer.remaining() > Math.min(LuffyRendezvousCodec.MAX_PAYLOAD_SIZE, maxPayloadBytes.getAsInt())) throw new IllegalArgumentException("payload lf_rendezvous excessivo");
        if (buffer.remaining() < LuffyRendezvousCodec.PAYLOAD_SIZE) return 0;
        if (buffer.remaining() != LuffyRendezvousCodec.PAYLOAD_SIZE) throw new IllegalArgumentException("payload lf_rendezvous invalido");
        byte[] payload = new byte[LuffyRendezvousCodec.PAYLOAD_SIZE];
        buffer.get(payload);
        context.setMessage(codec.decode(payload));
        return payload.length;
    }
}
