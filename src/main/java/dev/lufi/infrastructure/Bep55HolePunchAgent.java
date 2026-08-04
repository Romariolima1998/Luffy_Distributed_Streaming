package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.torrent.annotation.Consumes;
import bt.torrent.annotation.Produces;
import bt.torrent.messaging.MessageContext;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Coordenação BEP 55. O relay encaminha somente mensagens oficiais
 * RENDEZVOUS/CONNECT/ERROR: nunca metadata, pieces ou dados de torrent.
 */
/**
 * Agente de mensagens registrado no pipeline do bt-core. A classe e seus
 * handlers precisam ser públicos: o compilador de agentes do bt-core usa
 * {@code MethodHandles.publicLookup()} para ligar @Consumes e @Produces.
 */
public final class Bep55HolePunchAgent {
    private static final Duration RETRY_COOLDOWN = Duration.ofMinutes(2);

    private final P2pDiagnostics diagnostics;
    private final UtpBitTorrentBridge bridge;
    /** Todas as conexões BitTorrent que concluíram o extension handshake. */
    private final Map<ConnectionKey, ConnectedPeer> knownPeers = new ConcurrentHashMap<>();
    /** Subconjunto de conexões que realmente negociou BEP 10 + uTP + ut_holepunch. */
    private final Map<ConnectionKey, ConnectedPeer> connectedPeers = new ConcurrentHashMap<>();
    private final Map<ConnectionKey, PeerCapabilities> capabilitiesByPeer = new ConcurrentHashMap<>();
    /** Endpoints UDP obtidos por descoberta independente, nunca derivados da porta TCP. */
    private final Map<UtpEndpointKey, PeerConnectivityManager.PeerEndpoint> observedUtpEndpoints = new ConcurrentHashMap<>();
    private final Map<ConnectionKey, ConcurrentLinkedQueue<Message>> outbound = new ConcurrentHashMap<>();
    private final Map<String, HolePunchAttempt> attempts = new ConcurrentHashMap<>();
    private volatile Consumer<String> statusListener = ignored -> { };
    private volatile Consumer<String> capabilityListener = ignored -> { };
    private volatile Consumer<String> usefulRendezvousListener = ignored -> { };
    private volatile Consumer<String> rendezvousRelayedListener = ignored -> { };
    private volatile BiConsumer<ConnectionKey, PeerCapabilities> extensionHandshakeListener = (key, capabilities) -> { };

    Bep55HolePunchAgent(P2pDiagnostics diagnostics, UtpBitTorrentBridge bridge) {
        this.diagnostics = diagnostics == null ? new P2pDiagnostics() : diagnostics;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /** "Abrir magnet" novamente é uma decisão explícita, não retry automático infinito. */
    void allowExplicitRetry(String infoHash) {
        attempts.keySet().removeIf(key -> key.startsWith(infoHash.toLowerCase() + "|"));
        diagnostics.log("BEP55: nova tentativa explicita liberada para infoHash=" + infoHash + ".");
    }

    void setStatusListener(Consumer<String> listener) { statusListener = listener == null ? ignored -> { } : listener; }
    void setCapabilityListener(Consumer<String> listener) { capabilityListener = listener == null ? ignored -> { } : listener; }
    void setUsefulRendezvousListener(Consumer<String> listener) { usefulRendezvousListener = listener == null ? ignored -> { } : listener; }
    void setRendezvousRelayedListener(Consumer<String> listener) { rendezvousRelayedListener = listener == null ? ignored -> { } : listener; }
    void setExtensionHandshakeListener(BiConsumer<ConnectionKey, PeerCapabilities> listener) {
        extensionHandshakeListener = listener == null ? (key, capabilities) -> { } : listener;
    }

    /**
     * Recebe somente endpoint uTP que uma camada de descoberta já forneceu.
     * Não converte porta TCP para UDP e não publica qualquer endpoint novo.
     */
    void observePeerUtpEndpoint(String infoHash, PeerConnectivityManager.PeerEndpoint endpoint) {
        if (endpoint == null || endpoint.transport() != PeerConnectivityManager.Transport.UTP) return;
        String normalizedHash = requireInfoHash(infoHash);
        UtpEndpointKey endpointKey = new UtpEndpointKey(normalizedHash, endpoint.address(), endpoint.port());
        observedUtpEndpoints.put(endpointKey, endpoint);

        List<ConnectedPeer> matches = peersAt(normalizedHash, endpoint.address());
        if (matches.size() == 1) {
            matches.getFirst().setUtpEndpoint(endpoint);
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 UDP ENDPOINT ASSOCIATED: infoHash=" + normalizedHash
                    + "; peer=" + display(endpoint.address(), endpoint.port()) + "; source=discovery independente.");
        } else if (matches.size() > 1) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 UDP ENDPOINT AMBIGUOUS: infoHash=" + normalizedHash
                    + "; endpoint=" + display(endpoint.address(), endpoint.port()) + "; conexões TCP no mesmo IP=" + matches.size()
                    + "; endpoint mantido, mas não associado automaticamente.");
        }
    }

    /**
     * Associa uma observação UDP a uma conexão TCP já identificada. É usado
     * apenas quando quem observou o endpoint também conhece a identidade TCP;
     * nunca cai para "mesmo IP" caso existam vários peers naquele endereço.
     */
    void associatePeerUtpEndpoint(String infoHash, InetAddress peerAddress, int peerTcpPort,
                                  PeerConnectivityManager.PeerEndpoint endpoint) {
        if (endpoint == null || endpoint.transport() != PeerConnectivityManager.Transport.UTP) return;
        String normalizedHash = requireInfoHash(infoHash);
        observedUtpEndpoints.put(new UtpEndpointKey(normalizedHash, endpoint.address(), endpoint.port()), endpoint);
        List<ConnectedPeer> matches = knownPeers.values().stream()
                .filter(peer -> hex(peer.key().getTorrentId()).equalsIgnoreCase(normalizedHash))
                .filter(peer -> peer.address().equals(peerAddress))
                .filter(peer -> peer.matchesTcpPort(peerTcpPort)).toList();
        if (matches.size() == 1) {
            matches.getFirst().setUtpEndpoint(endpoint);
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 UDP ENDPOINT ASSOCIATED: infoHash=" + normalizedHash
                    + "; TCP peer=" + display(peerAddress, peerTcpPort) + "; UDP peer=" + endpoint.display()
                    + "; source=identidade TCP verificada.");
        } else {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 UDP ENDPOINT NOT ASSOCIATED: infoHash=" + normalizedHash
                    + "; TCP peer=" + display(peerAddress, peerTcpPort) + "; matches=" + matches.size()
                    + "; motivo=conexão BitTorrent não identificada de forma única.");
        }
    }

    /** Peers úteis como rendezvous possuem conexão real e negociaram as extensões necessárias. */
    int usefulRendezvousPeerCount(String infoHash) {
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(infoHash));
        return (int) connectedPeers.values().stream()
                .filter(peer -> peer.key().getTorrentId().equals(torrentId))
                .map(peer -> peer.address().getHostAddress() + ":" + peer.tcpPort())
                .distinct().count();
    }

    /** Mantido como alias para diagnósticos já existentes. */
    int holePunchCapablePeerCount(String infoHash) { return usefulRendezvousPeerCount(infoHash); }

    /** Remove o peer da lista de conexões ativas quando o motor BitTorrent a encerra. */
    void onPeerDisconnected(String infoHash, bt.net.Peer peer, int remotePort) {
        if (peer == null) return;
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(infoHash));
        List<ConnectionKey> removed = new ArrayList<>();
        knownPeers.entrySet().removeIf(entry -> {
            ConnectedPeer candidate = entry.getValue();
            boolean samePeer = candidate.key().getTorrentId().equals(torrentId)
                    && candidate.address().equals(peer.getInetAddress())
                    && candidate.matchesTcpPort(remotePort);
            if (samePeer) removed.add(entry.getKey());
            return samePeer;
        });
        removed.forEach(key -> {
            connectedPeers.remove(key);
            capabilitiesByPeer.remove(key);
            outbound.remove(key);
        });
        capabilityListener.accept(infoHash);
        logSwarmPeers(infoHash);
    }

    Optional<String> terminalStatus(String infoHash) {
        return attempts.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(infoHash.toLowerCase() + "|"))
                .map(Map.Entry::getValue)
                .filter(attempt -> attempt.status() == HolePunchStatus.NO_RENDEZVOUS)
                .findFirst()
                .map(ignored -> "Hole punch indisponível: nenhum peer rendezvous conectado ao target.");
    }

    /** Estado por endereço para o painel visual; não cria socket nem nova tentativa. */
    PeerVisualReport.Bep55Status peerDebugStatus(String infoHash, InetAddress address) {
        TorrentId torrentId = TorrentId.fromBytes(hexBytes(infoHash));
        List<PeerCapabilities> capabilities = capabilitiesByPeer.entrySet().stream()
                .filter(entry -> entry.getKey().getTorrentId().equals(torrentId))
                .filter(entry -> entry.getKey().getPeer().getInetAddress().equals(address))
                .map(Map.Entry::getValue).toList();
        String availability = capabilities.isEmpty() ? "não anunciado"
                : capabilities.stream().anyMatch(PeerCapabilities::supportsBep55) ? "disponível" : "indisponível";
        String prefix = infoHash.toLowerCase() + "|" + address.getHostAddress() + ":";
        Optional<HolePunchAttempt> attempt = attempts.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).max(Comparator.comparing(HolePunchAttempt::at));
        if (attempt.isEmpty()) return new PeerVisualReport.Bep55Status(availability, "não disponível", "não solicitado", "");
        HolePunchAttempt current = attempt.get();
        String rendezvous = current.rendezvous() == null ? "não disponível" : current.rendezvous();
        return new PeerVisualReport.Bep55Status(availability, rendezvous, switch (current.status()) {
            case REQUESTED -> "solicitado";
            case CONNECTING -> "em andamento";
            case UNSUPPORTED -> "não suportado";
            case NO_RENDEZVOUS -> "indisponível";
        }, current.reason());
    }

    /** Ponto unico do runtime com uTP para ler o extension handshake e encaminhar lf_identity. */
    @Consumes public void consume(ExtendedHandshake handshake, MessageContext context) {
        ConnectionKey key = context.getConnectionKey();
        PeerCapabilities capabilities = PeerCapabilities.fromExtensionHandshake(handshake.getSupportedMessageTypes());
        int declaredPort = handshake.getPort() == null ? key.getRemotePort() : (Integer) handshake.getPort().getValue();
        ConnectedPeer peer = new ConnectedPeer(key, key.getPeer().getInetAddress(), declaredPort);
        lookupKnownUtpEndpoint(hex(key.getTorrentId()), peer.address()).ifPresent(peer::setUtpEndpoint);
        knownPeers.put(key, peer);
        capabilitiesByPeer.put(key, capabilities);
        extensionHandshakeListener.accept(key, capabilities);
        capabilityListener.accept(hex(key.getTorrentId()));
        diagnostics.log(P2pDiagnostics.Layer.CONNECTIVITY, "PEER CAPABILITIES: infoHash=" + hex(key.getTorrentId()) + "; peer="
                + display(key.getPeer().getInetAddress(), key.getRemotePort()) + "; extension protocol=" + capabilities.extensionProtocol()
                + "; ut_holepunch=" + capabilities.utHolePunch() + "; ut_metadata=" + capabilities.utMetadata()
                + "; ut_pex=" + capabilities.utPex() + "; utp=" + capabilities.utp() + ".");
        if (!capabilities.supportsBep55()) {
            connectedPeers.remove(key);
            diagnostics.log("HOLE PUNCH UNSUPPORTED: infoHash=" + hex(key.getTorrentId()) + "; peer="
                    + display(key.getPeer().getInetAddress(), key.getRemotePort()) + "; motivo=peer não anunciou extension protocol + ut_holepunch + uTP.");
            return;
        }
        connectedPeers.put(key, peer);
        attempts.entrySet().removeIf(entry -> entry.getKey().startsWith(hex(key.getTorrentId()).toLowerCase() + "|")
                && entry.getValue().status() == HolePunchStatus.UNSUPPORTED);
        String udp = peer.utpEndpoint() == null ? "não observado independentemente" : peer.utpEndpoint().display();
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 CAPABILITY: peer=" + display(peer.address(), peer.tcpPort()) + "; infoHash="
                + hex(key.getTorrentId()) + "; ut_holepunch=suportado; endpoint uTP=" + udp + ".");
        usefulRendezvousListener.accept(hex(key.getTorrentId()));
        logSwarmPeers(hex(key.getTorrentId()));
    }

    @Consumes public void consume(Bep55HolePunchMessage message, MessageContext context) {
        ConnectionKey source = context.getConnectionKey();
        if (!connectedPeers.containsKey(source)) return; // BEP 55 exige anúncio no extension handshake
        switch (message.type()) {
            case RENDEZVOUS -> relayRendezvous(message, source);
            case CONNECT -> connect(message, source);
            case ERROR -> handleRelayError(message, source);
        }
    }

    /** Solicita a um peer já conectado que apresente este peer ao endpoint alvo. */
    void requestRendezvous(String infoHash, InetAddress targetAddress, int targetPort) {
        Bep55HolePunchMessage.rendezvous(targetAddress, targetPort); // valida família/endereço/porta antes de enfileirar
        String normalizedHash = requireInfoHash(infoHash);
        String targetKey = targetKey(normalizedHash, targetAddress, targetPort);
        if (!bridge.supports(targetAddress)) {
            unsupported(targetKey, normalizedHash, targetAddress, targetPort, "uTP local indisponível para esta família de endereço");
            return;
        }
        List<ConnectionKey> candidates = RendezvousPeerSelector.selectAll(normalizedHash, targetAddress, targetPort,
                        connectedPeers.values().stream().map(peer -> new RendezvousPeerSelector.Candidate(
                                peer.key().toString(), hex(peer.key().getTorrentId()), peer.address(), peer.tcpPort(), 0, true)).toList())
                .stream().map(RendezvousPeerSelector.Candidate::connectionId)
                .flatMap(connectionId -> connectedPeers.values().stream().filter(peer -> peer.key().toString().equals(connectionId)).map(ConnectedPeer::key))
                .toList();
        if (candidates.isEmpty()) {
            noRendezvous(targetKey, normalizedHash, targetAddress, targetPort, "nenhum peer BEP55 elegível no swarm");
            return;
        }
        HolePunchAttempt attempt = new HolePunchAttempt(Instant.now(), HolePunchStatus.REQUESTED, "rendezvous aguardando candidato", null, candidates);
        if (!admitAttempt(targetKey, normalizedHash, targetAddress, targetPort, attempt)) return;
        requestNextCandidate(targetKey, normalizedHash, targetAddress, targetPort, attempt, "início da tentativa");
    }

    /**
     * Entrada do overlay lf_rendezvous depois que Z confirmou as capacidades
     * e os endpoints. Reusa o mesmo controle de duplicata, instrumentacao e a
     * ponte BEP55/uTP; nao abre um transporte paralelo.
     */
    public CompletionStage<Void> startDistributedHolePunch(TorrentId contentTorrentId, InetAddress targetAddress, int targetPort) {
        Objects.requireNonNull(contentTorrentId, "contentTorrentId");
        Objects.requireNonNull(targetAddress, "targetAddress");
        Bep55HolePunchMessage.connect(targetAddress, targetPort);
        String infoHash = hex(contentTorrentId);
        if (!bridge.supports(targetAddress)) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("uTP local indisponivel para a familia do endpoint rendezvous"));
        }
        if (bridge.hasActiveOrConnecting(infoHash, targetAddress, targetPort)) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("tentativa uTP duplicada para o mesmo endpoint"));
        }
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 OVERLAY CONNECT: infoHash=" + infoHash + "; target="
                + display(targetAddress, targetPort) + "; aguardando aceite do bt-core.");
        return bridge.connectViaHolePunch(infoHash, targetAddress, targetPort);
    }

    /** Serializa tentativas concorrentes para o mesmo infoHash/endpoint sem bloquear outros swarms. */
    private boolean admitAttempt(String targetKey, String infoHash, InetAddress targetAddress, int targetPort,
                                 HolePunchAttempt attempt) {
        for (;;) {
            HolePunchAttempt previous = attempts.putIfAbsent(targetKey, attempt);
            if (previous == null) return true;
            if (previous.isWithinRetryCooldown()) {
                diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "HOLE PUNCH RETRY SUPPRESSED: infoHash=" + infoHash + "; target="
                        + display(targetAddress, targetPort) + "; status=" + previous.status() + "; motivo=" + previous.reason() + ".");
                return false;
            }
            if (attempts.replace(targetKey, previous, attempt)) return true;
        }
    }

    private void requestNextCandidate(String targetKey, String infoHash, InetAddress targetAddress, int targetPort,
                                      HolePunchAttempt attempt, String trigger) {
        ConnectedPeer relay;
        synchronized (attempt) {
            relay = nextConnectedRelay(attempt);
            if (relay == null) {
                attempt.update(HolePunchStatus.NO_RENDEZVOUS, "todos os candidatos retornaram erro ou desconectaram", null);
            } else {
                attempt.update(HolePunchStatus.REQUESTED, "rendezvous enviado", "peer C " + display(relay.address(), relay.tcpPort()));
            }
        }
        if (relay == null) {
            noRendezvous(targetKey, infoHash, targetAddress, targetPort, attempt.reason());
            return;
        }
        enqueue(relay.key(), Bep55HolePunchMessage.rendezvous(targetAddress, targetPort));
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "RENDEZVOUS FOUND: infoHash=" + infoHash + "; peer C="
                + display(relay.address(), relay.tcpPort()) + "; target=" + display(targetAddress, targetPort)
                + "; candidato=" + attempt.currentCandidateNumber() + "/" + attempt.candidateCount() + "; trigger=" + trigger + ".");
    }

    private ConnectedPeer nextConnectedRelay(HolePunchAttempt attempt) {
        while (attempt.hasNextCandidate()) {
            ConnectionKey candidateKey = attempt.nextCandidate();
            ConnectedPeer candidate = connectedPeers.get(candidateKey);
            if (candidate != null) {
                attempt.setActiveRelay(candidateKey);
                return candidate;
            }
        }
        return null;
    }

    private void handleRelayError(Bep55HolePunchMessage message, ConnectionKey source) {
        String infoHash = hex(source.getTorrentId());
        String key = targetKey(infoHash, message.address(), message.port());
        HolePunchAttempt attempt = attempts.get(key);
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 ERROR: infoHash=" + infoHash + "; relay="
                + display(source.getPeer().getInetAddress(), source.getRemotePort()) + "; target=" + display(message.address(), message.port())
                + "; code=" + message.errorCode() + ".");
        if (attempt == null || !attempt.isActiveRelay(source)) return;
        if (message.errorCode() == Bep55HolePunchMessage.ErrorCode.NO_SUPPORT) {
            unsupported(key, infoHash, message.address(), message.port(), "target não suporta ut_holepunch");
            return;
        }
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "RENDEZVOUS CANDIDATE FAILED: infoHash=" + infoHash + "; peer C="
                + display(source.getPeer().getInetAddress(), source.getRemotePort()) + "; code=" + message.errorCode()
                + "; próximo=outro candidato elegível.");
        requestNextCandidate(key, infoHash, message.address(), message.port(), attempt, "erro " + message.errorCode());
    }

    private void relayRendezvous(Bep55HolePunchMessage request, ConnectionKey initiator) {
        ConnectedPeer requester = connectedPeers.get(initiator);
        if (requester == null) return;
        if (requester.matchesUtpEndpoint(request.address(), request.port())) {
            error(initiator, request, Bep55HolePunchMessage.ErrorCode.NO_SELF);
            return;
        }
        ConnectedPeer target = peerByUtpEndpoint(hex(initiator.getTorrentId()), request.address(), request.port()).orElse(null);
        if (target == null) {
            error(initiator, request, Bep55HolePunchMessage.ErrorCode.NOT_CONNECTED);
            return;
        }
        if (!connectedPeers.containsKey(target.key())) {
            error(initiator, request, Bep55HolePunchMessage.ErrorCode.NO_SUPPORT);
            return;
        }
        PeerConnectivityManager.PeerEndpoint requesterUtp = requester.utpEndpoint();
        if (requesterUtp == null) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 RENDEZVOUS REJECTED: infoHash=" + hex(initiator.getTorrentId())
                    + "; motivo=endpoint UDP/uTP do iniciador não foi observado independentemente.");
            error(initiator, request, Bep55HolePunchMessage.ErrorCode.NO_SUCH_PEER);
            return;
        }
        PeerConnectivityManager.PeerEndpoint targetUtp = target.utpEndpoint();
        if (targetUtp == null) {
            diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 RENDEZVOUS REJECTED: infoHash=" + hex(initiator.getTorrentId())
                    + "; motivo=endpoint UDP/uTP do target não foi observado independentemente.");
            error(initiator, request, Bep55HolePunchMessage.ErrorCode.NO_SUCH_PEER);
            return;
        }
        enqueue(initiator, Bep55HolePunchMessage.connect(targetUtp.address(), targetUtp.port()));
        enqueue(target.key(), Bep55HolePunchMessage.connect(requesterUtp.address(), requesterUtp.port()));
        rendezvousRelayedListener.accept(hex(initiator.getTorrentId()));
        diagnostics.event(P2pDiagnostics.Category.LF_BEP55, "CONNECT_DISPATCHED",
                "torrentId", abbreviated(hex(initiator.getTorrentId())), "transport", "UTP");
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "CONNECT SENT: infoHash=" + hex(initiator.getTorrentId()) + "; initiator="
                + requesterUtp.display() + "; target=" + targetUtp.display() + "; relay somente sinalizou CONNECT, sem dados de arquivo.");
    }

    private void connect(Bep55HolePunchMessage message, ConnectionKey context) {
        String infoHash = hex(context.getTorrentId());
        ConnectedPeer relay = connectedPeers.get(context);
        if (relay == null) return;
        if (relay.matchesUtpEndpoint(message.address(), message.port())) {
            diagnostics.log("BEP55 CONNECT IGNORED: infoHash=" + infoHash + "; target=" + display(message.address(), message.port())
                    + "; o relay indicou o próprio peer.");
            return;
        }
        if (!bridge.supports(message.address())) {
            unsupported(targetKey(infoHash, message.address(), message.port()), infoHash, message.address(), message.port(),
                    "listener uTP local indisponível para a família de endereço");
            return;
        }
        if (bridge.hasActiveOrConnecting(infoHash, message.address(), message.port())) {
            diagnostics.log("BEP55 CONNECT IGNORED: infoHash=" + infoHash + "; target=" + display(message.address(), message.port())
                    + "; já existe sessão uTP para este peer.");
            return;
        }
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "CONNECT RECEIVED: infoHash=" + infoHash + "; target="
                + display(message.address(), message.port()) + "; iniciando uTP simultâneo.");
        diagnostics.event(P2pDiagnostics.Category.LF_BEP55, "CONNECT_RECEIVED",
                "torrentId", abbreviated(infoHash), "transport", "UTP");
        HolePunchAttempt attempt = new HolePunchAttempt(Instant.now(), HolePunchStatus.CONNECTING, "CONNECT recebido",
                "peer C " + display(relay.address(), relay.tcpPort()), List.of());
        attempts.put(targetKey(infoHash, message.address(), message.port()), attempt);
        bridge.connectViaHolePunch(infoHash, message.address(), message.port());
    }

    private void error(ConnectionKey destination, Bep55HolePunchMessage request, Bep55HolePunchMessage.ErrorCode error) {
        enqueue(destination, Bep55HolePunchMessage.error(request.address(), request.port(), error));
        diagnostics.log(P2pDiagnostics.Layer.HOLEPUNCH, "BEP55 ERROR SENT: infoHash=" + hex(destination.getTorrentId()) + "; target="
                + display(request.address(), request.port()) + "; code=" + error + ".");
    }

    private void unsupported(String targetKey, String infoHash, InetAddress address, int port, String reason) {
        attempts.put(targetKey, new HolePunchAttempt(Instant.now(), HolePunchStatus.UNSUPPORTED, reason, null, List.of()));
        diagnostics.log("HOLE PUNCH UNSUPPORTED: infoHash=" + infoHash + "; target=" + display(address, port) + "; motivo=" + reason + ".");
    }

    /** Caso A--B sem C, ou quando todos os C retornaram erro. */
    private void noRendezvous(String targetKey, String infoHash, InetAddress address, int port, String reason) {
        attempts.put(targetKey, new HolePunchAttempt(Instant.now(), HolePunchStatus.NO_RENDEZVOUS, reason, null, List.of()));
        bridge.markHolePunchUnavailable(infoHash, address, port, reason);
        diagnostics.log(P2pDiagnostics.Layer.RESULT, "PEER UNREACHABLE: infoHash=" + infoHash + "; endpoint="
                + display(address, port) + "; TCP=FAILED; uTP DIRECT=FAILED; BEP55=FAILED " + reason + ".");
        statusListener.accept("Hole punch indisponível: nenhum peer rendezvous conectado ao target.");
    }

    private static String abbreviated(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length())) + (value.length() > 12 ? "..." : "");
    }

    private Optional<ConnectedPeer> peerByUtpEndpoint(String infoHash, InetAddress address, int port) {
        return knownPeers.values().stream()
                .filter(peer -> hex(peer.key().getTorrentId()).equalsIgnoreCase(infoHash))
                .filter(peer -> peer.matchesUtpEndpoint(address, port))
                .findFirst();
    }

    private List<ConnectedPeer> peersAt(String infoHash, InetAddress address) {
        return knownPeers.values().stream()
                .filter(peer -> hex(peer.key().getTorrentId()).equalsIgnoreCase(infoHash))
                .filter(peer -> peer.address().equals(address)).toList();
    }

    private Optional<PeerConnectivityManager.PeerEndpoint> lookupKnownUtpEndpoint(String infoHash, InetAddress address) {
        List<PeerConnectivityManager.PeerEndpoint> endpoints = observedUtpEndpoints.entrySet().stream()
                .filter(entry -> entry.getKey().infoHash().equalsIgnoreCase(infoHash))
                .filter(entry -> entry.getKey().address().equals(address))
                .map(Map.Entry::getValue).toList();
        return endpoints.size() == 1 ? Optional.of(endpoints.getFirst()) : Optional.empty();
    }

    private void enqueue(ConnectionKey key, Message message) {
        outbound.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>()).add(message);
    }

    @Produces public void produce(Consumer<Message> consumer, MessageContext context) {
        ConcurrentLinkedQueue<Message> queue = outbound.get(context.getConnectionKey());
        if (queue == null) return;
        Message next = queue.poll();
        if (next != null) consumer.accept(next);
    }

    private static String display(InetAddress address, int port) {
        return (address instanceof java.net.Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress()) + ":" + port;
    }
    private static String targetKey(String infoHash, InetAddress address, int port) {
        return infoHash.toLowerCase() + "|" + address.getHostAddress() + ":" + port;
    }
    private static String requireInfoHash(String infoHash) {
        if (infoHash == null || !infoHash.matches("(?i)[a-f0-9]{40}")) throw new IllegalArgumentException("infoHash inválido");
        return infoHash.toLowerCase();
    }
    private void logSwarmPeers(String infoHash) {
        diagnostics.log("SWARM CONNECTED PEERS: infoHash=" + infoHash + "; peers com BEP55/uTP=" + swarmPeerCount(infoHash) + ".");
    }
    private long swarmPeerCount(String infoHash) {
        return connectedPeers.values().stream().filter(peer -> hex(peer.key().getTorrentId()).equalsIgnoreCase(infoHash)).count();
    }
    private static String hex(TorrentId id) {
        StringBuilder result = new StringBuilder(40);
        for (byte value : id.getBytes()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
    private static byte[] hexBytes(String infoHash) {
        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(infoHash.substring(index * 2, index * 2 + 2), 16);
        return bytes;
    }

    private static final class ConnectedPeer {
        private final ConnectionKey key;
        private final InetAddress address;
        private final int declaredTcpPort;
        private volatile PeerConnectivityManager.PeerEndpoint utpEndpoint;

        private ConnectedPeer(ConnectionKey key, InetAddress address, int declaredTcpPort) {
            this.key = key;
            this.address = address;
            this.declaredTcpPort = declaredTcpPort;
        }
        private ConnectionKey key() { return key; }
        private InetAddress address() { return address; }
        private int tcpPort() { return declaredTcpPort; }
        private PeerConnectivityManager.PeerEndpoint utpEndpoint() { return utpEndpoint; }
        private void setUtpEndpoint(PeerConnectivityManager.PeerEndpoint endpoint) { this.utpEndpoint = endpoint; }
        private boolean matchesTcpPort(int port) { return key.getRemotePort() == port || declaredTcpPort == port; }
        private boolean matchesUtpEndpoint(InetAddress endpointAddress, int endpointPort) {
            PeerConnectivityManager.PeerEndpoint current = utpEndpoint;
            return current != null && current.address().equals(endpointAddress) && current.port() == endpointPort;
        }
    }

    private record UtpEndpointKey(String infoHash, InetAddress address, int port) { }
    private enum HolePunchStatus { REQUESTED, CONNECTING, UNSUPPORTED, NO_RENDEZVOUS }

    private static final class HolePunchAttempt {
        private final Instant at;
        private final List<ConnectionKey> candidates;
        private HolePunchStatus status;
        private String reason;
        private String rendezvous;
        private int nextCandidate;
        private ConnectionKey activeRelay;

        private HolePunchAttempt(Instant at, HolePunchStatus status, String reason, String rendezvous, List<ConnectionKey> candidates) {
            this.at = at;
            this.status = status;
            this.reason = reason;
            this.rendezvous = rendezvous;
            this.candidates = List.copyOf(candidates);
        }
        private Instant at() { return at; }
        private synchronized HolePunchStatus status() { return status; }
        private synchronized String reason() { return reason; }
        private synchronized String rendezvous() { return rendezvous; }
        private synchronized boolean hasNextCandidate() { return nextCandidate < candidates.size(); }
        private synchronized ConnectionKey nextCandidate() { return candidates.get(nextCandidate++); }
        private synchronized void setActiveRelay(ConnectionKey key) { activeRelay = key; }
        private synchronized boolean isActiveRelay(ConnectionKey key) { return Objects.equals(activeRelay, key); }
        private synchronized int currentCandidateNumber() { return nextCandidate; }
        private int candidateCount() { return candidates.size(); }
        private synchronized void update(HolePunchStatus status, String reason, String rendezvous) {
            this.status = status;
            this.reason = reason;
            this.rendezvous = rendezvous;
        }
        private boolean isWithinRetryCooldown() { return Duration.between(at, Instant.now()).compareTo(RETRY_COOLDOWN) < 0; }
    }
}
