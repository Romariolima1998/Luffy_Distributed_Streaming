package dev.lufi.infrastructure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import dev.lufi.infrastructure.security.AbuseProtectionService;

/**
 * Transporte BEP 29 de byte-stream sobre UDP. O servico nao conhece torrents:
 * ele apenas cria sessoes uTP confiaveis para que o motor BitTorrent as use.
 */
final class UtpTransportService implements AutoCloseable {
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 20;
    private static final int MAX_PAYLOAD = 1_100;
    private static final int MAX_PENDING_PACKETS = 48;
    private static final int MAX_RETRANSMISSIONS = 4;
    private static final Duration INITIAL_RETRANSMIT = Duration.ofMillis(700);
    private static final SessionLimits DEFAULT_LIMITS = new SessionLimits(
            1_024, 128, 8, Duration.ofSeconds(15), Duration.ofMinutes(5));

    interface IncomingListener { void accept(UtpSession session); }

    private final DatagramSocket socket;
    private final P2pDiagnostics diagnostics;
    private final SessionLimits limits;
    private final AbuseProtectionService abuseProtection;
    private final Map<UtpSessionKey, UtpSession> sessionsByKey = new ConcurrentHashMap<>();
    private final Map<UtpSessionKey, UtpSession> pendingSyns = new ConcurrentHashMap<>();
    private final Object pendingSessionLock = new Object();
    private final ScheduledExecutorService scheduler = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SecureRandom random = new SecureRandom();
    private volatile IncomingListener incomingListener = ignored -> { };

    UtpTransportService(InetAddress bindAddress, int port, P2pDiagnostics diagnostics) throws SocketException {
        this(bindAddress, port, diagnostics, DEFAULT_LIMITS, new AbuseProtectionService());
    }

    UtpTransportService(InetAddress bindAddress, int port, P2pDiagnostics diagnostics, SessionLimits limits) throws SocketException {
        this(bindAddress, port, diagnostics, limits, new AbuseProtectionService());
    }

    UtpTransportService(InetAddress bindAddress, int port, P2pDiagnostics diagnostics, SessionLimits limits,
                        AbuseProtectionService abuseProtection) throws SocketException {
        this.socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.abuseProtection = Objects.requireNonNull(abuseProtection, "abuseProtection");
        Thread.startVirtualThread(this::receiveLoop);
        scheduler.scheduleAtFixedRate(this::retransmitAndExpire, 100, 100, TimeUnit.MILLISECONDS);
        this.diagnostics.log(P2pDiagnostics.Layer.UTP, "LISTENER ACTIVE: " + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort() + ".");
    }

    int localPort() { return socket.getLocalPort(); }
    InetAddress localAddress() { return socket.getLocalAddress(); }
    boolean isListening() { return !closed.get() && socket.isBound() && !socket.isClosed(); }
    void setIncomingListener(IncomingListener listener) { incomingListener = listener == null ? ignored -> { } : listener; }
    int activeSessionCount() { return sessionsByKey.size(); }
    int pendingSessionCount() { return pendingSyns.size(); }

    CompletableFuture<UtpSession> connect(InetSocketAddress remote) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("servico uTP fechado"));
        return connect(remote, random.nextInt(65_536), random.nextInt(65_536));
    }

    CompletableFuture<UtpSession> connect(InetSocketAddress remote, int synConnectionId, int localSequence) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("servico uTP fechado"));
        InetSocketAddress validatedRemote = validatedRemote(remote);
        if (!abuseProtection.isAllowed(AbuseProtectionService.peerKey(validatedRemote.getAddress()), java.time.Instant.now())) {
            return CompletableFuture.failedFuture(new IOException("origem uTP temporariamente bloqueada por abuso"));
        }
        validateConnectionId(synConnectionId);
        validateConnectionId(localSequence);
        UtpSession reserved = null;
        int selectedConnectionId = -1;
        synchronized (pendingSessionLock) {
            if (pendingSyns.size() >= Math.min(limits.maxPendingSessions(), abuseProtection.config().maxPendingUtpSessions())) {
                return CompletableFuture.failedFuture(new IOException("limite de sessoes uTP pendentes atingido"));
            }
            if (pendingSessionCountForAddress(validatedRemote.getAddress()) >= abuseProtection.config().maxPendingUtpSessionsPerAddress()) {
                abuseProtection.recordViolation(AbuseProtectionService.peerKey(validatedRemote.getAddress()),
                        AbuseProtectionService.Violation.FLOOD, java.time.Instant.now());
                return CompletableFuture.failedFuture(new IOException("limite de sessoes uTP pendentes por endereco atingido"));
            }
            for (int attempt = 0; attempt < 64; attempt++) {
                int candidateId = attempt == 0 ? synConnectionId : random.nextInt(65_536);
                UtpSession session = UtpSession.outgoing(this, validatedRemote, candidateId, localSequence);
                UtpSessionKey key = key(validatedRemote, session.receiveConnectionId());
                if (sessionsByKey.containsKey(key)) continue;
                if (pendingSyns.putIfAbsent(key, session) == null) {
                    reserved = session;
                    selectedConnectionId = candidateId;
                    break;
                }
            }
        }
        if (reserved != null) {
            diagnostics.log(P2pDiagnostics.Layer.UTP, "OUTBOUND SYN: peer=" + display(validatedRemote)
                    + "; connectionId=" + selectedConnectionId + ".");
            reserved.sendSyn();
            return reserved.connected();
        }
        return CompletableFuture.failedFuture(new IOException("colisao de connection id uTP para o endpoint remoto"));
    }

    private void receiveLoop() {
        byte[] bytes = new byte[1_500];
        while (!closed.get()) {
            try {
                DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
                socket.receive(datagram);
                Optional<Packet> packet = Packet.decode(datagram.getData(), datagram.getLength());
                if (packet.isEmpty()) {
                    abuseProtection.recordViolation(AbuseProtectionService.peerKey(datagram.getAddress()),
                            AbuseProtectionService.Violation.INVALID_PAYLOAD, java.time.Instant.now());
                    diagnostics.log("uTP PACKET IGNORED: datagrama invalido de " + datagram.getSocketAddress() + ".");
                    continue;
                }
                InetSocketAddress remote = new InetSocketAddress(datagram.getAddress(), datagram.getPort());
                diagnostics.log(P2pDiagnostics.Layer.UTP, "INBOUND PACKET: peer=" + display(remote) + "; type=" + packet.get().type + ".");
                dispatch(packet.get(), remote);
            } catch (SocketException error) {
                if (!closed.get()) diagnostics.log("uTP UDP ERROR: " + message(error) + ".");
            } catch (IOException error) {
                if (!closed.get()) diagnostics.log("uTP UDP ERROR: " + message(error) + ".");
            } catch (RuntimeException error) {
                diagnostics.log("uTP PACKET ERROR: " + message(error) + ".");
            }
        }
    }

    private void dispatch(Packet packet, InetSocketAddress remote) {
        if (packet.type == PacketType.SYN) {
            acceptSyn(packet, remote);
            return;
        }
        UtpSessionKey key = key(remote, packet.connectionId);
        UtpSession session = sessionsByKey.get(key);
        if (session == null && packet.type == PacketType.STATE) session = pendingSyns.get(key);
        if (session == null) {
            if (packet.type != PacketType.RESET) send(Packet.reset(packet.connectionId, packet.sequence), remote);
            return;
        }
        session.onPacket(packet);
    }

    private void acceptSyn(Packet syn, InetSocketAddress remote) {
        UtpSessionKey key = key(remote, syn.connectionId);
        UtpSession existing = sessionsByKey.get(key);
        if (existing != null) {
            diagnostics.log(P2pDiagnostics.Layer.UTP, "INBOUND SYN DUPLICATE: peer=" + display(remote)
                    + "; connectionId=" + syn.connectionId + "; contexto existente preservado.");
            existing.sendState();
            return;
        }
        String peerKey = AbuseProtectionService.peerKey(remote.getAddress());
        if (!abuseProtection.isAllowed(peerKey, java.time.Instant.now())) {
            rejectSyn(syn, remote, "origem temporariamente bloqueada por abuso");
            return;
        }
        if (sessionsByKey.size() >= Math.min(limits.maxActiveSessions(), abuseProtection.config().maxPendingUtpSessions())) {
            rejectSyn(syn, remote, "limite global de sessoes ativas atingido");
            return;
        }
        int perAddressLimit = Math.min(limits.maxInboundSessionsPerEndpoint(), abuseProtection.config().maxPendingUtpSessionsPerAddress());
        if (sessionCountForAddress(remote.getAddress()) >= perAddressLimit) {
            abuseProtection.recordViolation(peerKey, AbuseProtectionService.Violation.FLOOD, java.time.Instant.now());
            rejectSyn(syn, remote, "limite de SYNs pendentes por endpoint atingido");
            return;
        }
        int sequence = random.nextInt(65_536);
        UtpSession session = UtpSession.incoming(this, remote, syn.connectionId, sequence, syn.sequence);
        UtpSession raced = sessionsByKey.putIfAbsent(key, session);
        if (raced != null) {
            raced.sendState();
            return;
        }
        diagnostics.log(P2pDiagnostics.Layer.UTP, "INBOUND SYN: peer=" + display(remote) + "; connectionId=" + syn.connectionId + ".");
        session.sendState();
        incomingListener.accept(session);
    }

    private void rejectSyn(Packet syn, InetSocketAddress remote, String reason) {
        diagnostics.log(P2pDiagnostics.Layer.UTP, "INBOUND SYN REJECTED: peer=" + display(remote)
                + "; connectionId=" + syn.connectionId + "; reason=" + reason + ".");
        send(Packet.reset(syn.connectionId, syn.sequence), remote);
    }

    private int sessionCountForAddress(InetAddress remoteAddress) {
        return (int) sessionsByKey.keySet().stream()
                .filter(key -> key.remoteAddress().equals(remoteAddress))
                .count();
    }

    private int pendingSessionCountForAddress(InetAddress remoteAddress) {
        return (int) pendingSyns.keySet().stream().filter(key -> key.remoteAddress().equals(remoteAddress)).count();
    }

    private void retransmitAndExpire() {
        if (closed.get()) return;
        long now = System.nanoTime();
        sessionsByKey.values().forEach(session -> {
            if (session.isExpired(now, limits.idleSessionTimeoutNanos())) session.close("sessao uTP expirada por inatividade");
            else session.retransmitExpired(now);
        });
        pendingSyns.values().forEach(session -> {
            if (session.isExpired(now, limits.pendingSessionTimeoutNanos())) session.close("SYN uTP expirado sem resposta");
            else session.retransmitExpired(now);
        });
    }

    boolean establishOutgoing(UtpSession session) {
        UtpSessionKey key = key(session.remote(), session.receiveConnectionId());
        pendingSyns.remove(key, session);
        UtpSession existing = sessionsByKey.putIfAbsent(key, session);
        if (existing != null && existing != session) return false;
        diagnostics.log(P2pDiagnostics.Layer.UTP, "RESPONSE RECEIVED / CONNECT SUCCESS: peer=" + display(session.remote())
                + "; connectionId=" + session.receiveConnectionId() + ".");
        diagnostics.event(P2pDiagnostics.Category.LF_UTP, "UTP_SESSION_CONNECTED", "transport", "UTP");
        return true;
    }

    void remove(UtpSession session) {
        UtpSessionKey key = key(session.remote(), session.receiveConnectionId());
        sessionsByKey.remove(key, session);
        pendingSyns.remove(key, session);
    }

    void send(Packet packet, InetSocketAddress remote) {
        if (closed.get()) return;
        byte[] bytes = packet.encode();
        try {
            synchronized (socket) { socket.send(new DatagramPacket(bytes, bytes.length, remote)); }
            if (packet.type == PacketType.SYN || packet.type == PacketType.STATE || packet.type == PacketType.RESET) {
                diagnostics.log(P2pDiagnostics.Layer.UTP, "OUTBOUND PACKET: peer=" + display(remote) + "; type=" + packet.type
                        + "; bytes=" + bytes.length + ".");
            }
        } catch (IOException error) {
            diagnostics.log(P2pDiagnostics.Layer.UTP, "OUTBOUND PACKET FAILED: peer=" + display(remote) + "; " + message(error) + ".");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        socket.close();
        sessionsByKey.values().forEach(session -> session.close("servico uTP encerrado"));
        pendingSyns.values().forEach(session -> session.close("servico uTP encerrado"));
        sessionsByKey.clear();
        pendingSyns.clear();
    }

    private static InetSocketAddress validatedRemote(InetSocketAddress remote) {
        Objects.requireNonNull(remote, "remote");
        if (remote.isUnresolved() || remote.getAddress() == null) {
            throw new IllegalArgumentException("endpoint uTP remoto deve possuir endereco IP resolvido");
        }
        key(remote, 0);
        return remote;
    }

    private static UtpSessionKey key(InetSocketAddress remote, int receiveConnectionId) {
        Objects.requireNonNull(remote, "remote");
        return new UtpSessionKey(remote.getAddress(), remote.getPort(), receiveConnectionId);
    }

    private static void validateConnectionId(int connectionId) {
        if (connectionId < 0 || connectionId > 0xffff) {
            throw new IllegalArgumentException("connection id uTP invalido");
        }
    }

    static String display(InetSocketAddress endpoint) {
        String host = endpoint.getAddress() instanceof java.net.Inet6Address ? "[" + endpoint.getAddress().getHostAddress() + "]" : endpoint.getAddress().getHostAddress();
        return host + ":" + endpoint.getPort();
    }
    private static String message(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    enum PacketType {
        DATA(0), FIN(1), STATE(2), RESET(3), SYN(4);
        private final int id;
        PacketType(int id) { this.id = id; }
        static PacketType fromId(int id) {
            for (PacketType type : values()) if (type.id == id) return type;
            throw new IllegalArgumentException("tipo uTP invalido");
        }
    }

    static record SessionLimits(int maxActiveSessions, int maxPendingSessions, int maxInboundSessionsPerEndpoint,
                                Duration pendingSessionTimeout, Duration idleSessionTimeout) {
        SessionLimits {
            if (maxActiveSessions < 1 || maxPendingSessions < 1 || maxInboundSessionsPerEndpoint < 1) {
                throw new IllegalArgumentException("limites de sessao uTP devem ser positivos");
            }
            Objects.requireNonNull(pendingSessionTimeout, "pendingSessionTimeout");
            Objects.requireNonNull(idleSessionTimeout, "idleSessionTimeout");
            if (pendingSessionTimeout.isZero() || pendingSessionTimeout.isNegative()
                    || idleSessionTimeout.isZero() || idleSessionTimeout.isNegative()) {
                throw new IllegalArgumentException("timeouts de sessao uTP devem ser positivos");
            }
        }

        long pendingSessionTimeoutNanos() { return pendingSessionTimeout.toNanos(); }
        long idleSessionTimeoutNanos() { return idleSessionTimeout.toNanos(); }
    }

    static final class UtpSession implements AutoCloseable {
        private final UtpTransportService transport;
        private final InetSocketAddress remote;
        private final int sendConnectionId;
        private final int receiveConnectionId;
        private final boolean outgoing;
        private final CompletableFuture<UtpSession> connected = new CompletableFuture<>();
        private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private final Map<Integer, PendingPacket> unacknowledged = new ConcurrentHashMap<>();
        private final Map<Integer, byte[]> outOfOrder = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger localSequence;
        private final long createdAtNanos = System.nanoTime();
        private volatile int lastRemoteSequence;
        private volatile int nextExpectedRemoteSequence;
        private volatile byte[] currentRead;
        private volatile int currentReadOffset;
        private volatile boolean connectedState;
        private volatile boolean remoteFinished;
        private volatile int congestionWindow = 8;
        private volatile long lastActivityNanos = createdAtNanos;

        private UtpSession(UtpTransportService transport, InetSocketAddress remote, int sendConnectionId,
                           int receiveConnectionId, int localSequence, int remoteSequence, boolean outgoing) {
            this.transport = transport;
            this.remote = remote;
            this.sendConnectionId = sendConnectionId & 0xffff;
            this.receiveConnectionId = receiveConnectionId & 0xffff;
            this.localSequence = new AtomicInteger(localSequence & 0xffff);
            this.lastRemoteSequence = remoteSequence & 0xffff;
            this.nextExpectedRemoteSequence = (remoteSequence + 1) & 0xffff;
            this.outgoing = outgoing;
            this.connectedState = !outgoing;
            if (!outgoing) connected.complete(this);
        }

        static UtpSession outgoing(UtpTransportService transport, InetSocketAddress remote, int connectionId, int sequence) {
            return new UtpSession(transport, remote, connectionId, connectionId + 1, sequence, 0, true);
        }
        static UtpSession incoming(UtpTransportService transport, InetSocketAddress remote, int connectionId, int sequence, int remoteSequence) {
            // O STATE inicial usa "sequence". O primeiro DATA local deve usar o
            // numero seguinte, como determina o fluxo de numeros do BEP 29.
            return new UtpSession(transport, remote, connectionId + 1, connectionId, sequence + 1, remoteSequence, false);
        }

        CompletableFuture<UtpSession> connected() { return connected; }
        InetSocketAddress remote() { return remote; }
        int receiveConnectionId() { return receiveConnectionId; }
        boolean isConnected() { return connectedState && !closed.get(); }

        void sendSyn() {
            Packet syn = new Packet(PacketType.SYN, sendConnectionId, nextLocalSequence(), 0, new byte[0]);
            sendReliable(syn);
        }

        void onPacket(Packet packet) {
            touch();
            acknowledge(packet.acknowledgement);
            if (packet.type == PacketType.RESET) {
                close("peer enviou RESET", false);
                return;
            }
            if (outgoing && !connectedState && packet.type == PacketType.STATE) {
                lastRemoteSequence = packet.sequence;
                nextExpectedRemoteSequence = (packet.sequence + 1) & 0xffff;
                if (!transport.establishOutgoing(this)) {
                    close("colisao de sessao uTP ativa para o mesmo endpoint");
                    return;
                }
                connectedState = true;
                connected.complete(this);
                return;
            }
            if (!connectedState) return;
            switch (packet.type) {
                case DATA -> receiveData(packet);
                case FIN -> {
                    remoteFinished = true;
                    lastRemoteSequence = packet.sequence;
                    sendState();
                    incoming.offer(new byte[0]);
                }
                case STATE -> { /* acknowledgement already handled */ }
                case SYN, RESET -> { /* handled above */ }
            }
        }

        private void receiveData(Packet packet) {
            int sequence = packet.sequence;
            if (sequence == nextExpectedRemoteSequence) {
                deliver(packet.payload);
                lastRemoteSequence = sequence;
                nextExpectedRemoteSequence = (sequence + 1) & 0xffff;
                while (true) {
                    byte[] next = outOfOrder.remove(nextExpectedRemoteSequence);
                    if (next == null) break;
                    deliver(next);
                    lastRemoteSequence = nextExpectedRemoteSequence;
                    nextExpectedRemoteSequence = (nextExpectedRemoteSequence + 1) & 0xffff;
                }
            } else if (isAhead(sequence, nextExpectedRemoteSequence)) {
                outOfOrder.putIfAbsent(sequence, packet.payload);
            }
            sendState();
        }

        private void deliver(byte[] payload) { if (payload.length > 0) incoming.offer(payload); }

        void sendState() {
            if (closed.get()) return;
            touch();
            // STATE apenas confirma o ultimo pacote remoto. Ele nao ocupa uma nova
            // posicao no fluxo: avancar o numero aqui criaria uma lacuna para o
            // proximo DATA e faria o outro lado aguardar um pacote inexistente.
            int lastLocalSequence = (localSequence.get() - 1) & 0xffff;
            transport.send(new Packet(PacketType.STATE, sendConnectionId, lastLocalSequence, lastRemoteSequence, new byte[0]), remote);
        }

        void write(byte[] bytes, int offset, int length) throws IOException {
            if (!isConnected()) throw new IOException("sessao uTP nao esta conectada");
            int end = offset + length;
            for (int index = offset; index < end;) {
                waitForWindow();
                int count = Math.min(MAX_PAYLOAD, end - index);
                byte[] payload = Arrays.copyOfRange(bytes, index, index + count);
                Packet packet = new Packet(PacketType.DATA, sendConnectionId, nextLocalSequence(), lastRemoteSequence, payload);
                sendReliable(packet);
                index += count;
            }
        }

        int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) return 0;
            try {
                while (currentRead == null || currentReadOffset >= currentRead.length) {
                    currentRead = incoming.poll(30, TimeUnit.SECONDS);
                    currentReadOffset = 0;
                    if (currentRead == null) {
                        if (closed.get()) return -1;
                        continue;
                    }
                    if (currentRead.length == 0 && (remoteFinished || closed.get())) return -1;
                }
                int count = Math.min(length, currentRead.length - currentReadOffset);
                System.arraycopy(currentRead, currentReadOffset, destination, offset, count);
                currentReadOffset += count;
                return count;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("leitura uTP interrompida", error);
            }
        }

        private void waitForWindow() throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (unacknowledged.size() >= congestionWindow) {
                if (closed.get()) throw new IOException("sessao uTP fechada");
                if (System.nanoTime() > deadline) throw new IOException("janela uTP sem ACK");
                try { Thread.sleep(5); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("escrita uTP interrompida", error); }
            }
        }

        private void sendReliable(Packet packet) {
            touch();
            unacknowledged.put(packet.sequence, new PendingPacket(packet));
            transport.send(packet, remote);
        }

        private void acknowledge(int acknowledgement) {
            int removed = 0;
            for (Map.Entry<Integer, PendingPacket> entry : unacknowledged.entrySet()) {
                if (isAcknowledged(entry.getKey(), acknowledgement) && unacknowledged.remove(entry.getKey(), entry.getValue())) removed++;
            }
            if (removed > 0) congestionWindow = Math.min(MAX_PENDING_PACKETS, congestionWindow + 1);
        }

        boolean isExpired(long now, long timeoutNanos) {
            return now - lastActivityNanos >= timeoutNanos;
        }

        void retransmitExpired(long now) {
            for (PendingPacket pending : unacknowledged.values()) {
                if (now - pending.sentAtNanos < pending.timeoutNanos) continue;
                if (pending.retries >= MAX_RETRANSMISSIONS) {
                    close("timeout aguardando ACK");
                    return;
                }
                pending.retries++;
                pending.sentAtNanos = now;
                pending.timeoutNanos = Math.min(TimeUnit.SECONDS.toNanos(8), pending.timeoutNanos * 2);
                congestionWindow = Math.max(2, congestionWindow / 2);
                transport.send(pending.packet, remote);
            }
        }

        private int nextLocalSequence() { return localSequence.getAndUpdate(value -> (value + 1) & 0xffff); }
        private void touch() { lastActivityNanos = System.nanoTime(); }
        private static boolean isAhead(int candidate, int reference) { return candidate != reference && ((candidate - reference) & 0xffff) < 0x8000; }
        private static boolean isAcknowledged(int sequence, int acknowledgement) { return sequence == acknowledgement || !isAhead(sequence, acknowledgement); }

        void close(String reason) {
            close(reason, true);
        }

        private void close(String reason, boolean notifyRemote) {
            if (!closed.compareAndSet(false, true)) return;
            transport.remove(this);
            if (notifyRemote) transport.send(Packet.reset(sendConnectionId, lastRemoteSequence), remote);
            if (!connected.isDone()) connected.completeExceptionally(new IOException(reason));
            incoming.offer(new byte[0]);
            transport.diagnostics.log("uTP CLOSED: peer=" + display(remote) + "; reason=" + reason + ".");
        }
        @Override public void close() { close("encerrada localmente"); }
    }

    private static final class PendingPacket {
        private final Packet packet;
        private volatile long sentAtNanos = System.nanoTime();
        private volatile long timeoutNanos = INITIAL_RETRANSMIT.toNanos();
        private volatile int retries;
        private PendingPacket(Packet packet) { this.packet = packet; }
    }

    private record Packet(PacketType type, int connectionId, int sequence, int acknowledgement, byte[] payload) {
        private Packet {
            connectionId &= 0xffff; sequence &= 0xffff; acknowledgement &= 0xffff;
            payload = payload == null ? new byte[0] : payload;
        }
        static Packet reset(int connectionId, int acknowledgement) { return new Packet(PacketType.RESET, connectionId, 0, acknowledgement, new byte[0]); }
        byte[] encode() {
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
            buffer.put((byte) ((type.id << 4) | VERSION));
            buffer.put((byte) 0); // sem extensao; ACK seletivo e opcional no BEP 29
            buffer.putShort((short) connectionId);
            buffer.putInt((int) (System.nanoTime() / 1_000));
            buffer.putInt(0);
            buffer.putInt(64 * 1024);
            buffer.putShort((short) sequence);
            buffer.putShort((short) acknowledgement);
            buffer.put(payload);
            return buffer.array();
        }
        static Optional<Packet> decode(byte[] source, int length) {
            if (length < HEADER_SIZE) return Optional.empty();
            ByteBuffer buffer = ByteBuffer.wrap(source, 0, length);
            int first = buffer.get() & 0xff;
            int version = first & 0x0f;
            if (version != VERSION) return Optional.empty();
            PacketType type;
            try { type = PacketType.fromId(first >>> 4); }
            catch (IllegalArgumentException error) { return Optional.empty(); }
            int extension = buffer.get() & 0xff;
            if (extension != 0) return Optional.empty(); // nao aceitar extensoes desconhecidas como dados
            int connectionId = Short.toUnsignedInt(buffer.getShort());
            buffer.getInt(); buffer.getInt(); buffer.getInt();
            int sequence = Short.toUnsignedInt(buffer.getShort());
            int acknowledgement = Short.toUnsignedInt(buffer.getShort());
            byte[] payload = new byte[buffer.remaining()]; buffer.get(payload);
            return Optional.of(new Packet(type, connectionId, sequence, acknowledgement, payload));
        }
    }
}
