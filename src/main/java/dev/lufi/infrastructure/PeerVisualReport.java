package dev.lufi.infrastructure;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Resumo copiável por peer, criado exclusivamente a partir do estado já observado. */
final class PeerVisualReport {
    private PeerVisualReport() { }

    record Bep55Status(String availability, String rendezvous, String holePunch, String detail) {
        static Bep55Status unknown() { return new Bep55Status("não anunciado", "não disponível", "não solicitado", ""); }
    }

    static String render(List<PeerConnectivityManager.PeerState> states,
                         BiFunction<String, InetAddress, Bep55Status> bep55Lookup) {
        if (states == null || states.isEmpty()) return "PEERS DO SWARM\n\nNenhum peer foi descoberto nesta execução.";
        Map<PeerKey, List<PeerConnectivityManager.PeerState>> byPeer = new LinkedHashMap<>();
        states.stream().sorted(Comparator.comparing(PeerConnectivityManager.PeerState::infoHash)
                        .thenComparing(state -> state.endpoint().address().getHostAddress()))
                .forEach(state -> byPeer.computeIfAbsent(new PeerKey(state.infoHash(), state.endpoint().address()), ignored -> new ArrayList<>()).add(state));

        List<String> blocks = new ArrayList<>();
        blocks.add("PEERS DO SWARM");
        for (Map.Entry<PeerKey, List<PeerConnectivityManager.PeerState>> entry : byPeer.entrySet()) {
            PeerKey key = entry.getKey();
            List<PeerConnectivityManager.PeerState> endpoints = entry.getValue();
            Bep55Status bep55 = bep55Lookup == null ? Bep55Status.unknown() : bep55Lookup.apply(key.infoHash(), key.address());
            PeerConnectivityManager.PeerState tcp = endpoint(endpoints, PeerConnectivityManager.Transport.TCP);
            PeerConnectivityManager.PeerState utp = endpoint(endpoints, PeerConnectivityManager.Transport.UTP);
            EnumSet<PeerConnectivityManager.DiscoveryOrigin> origins = EnumSet.noneOf(PeerConnectivityManager.DiscoveryOrigin.class);
            endpoints.forEach(state -> origins.addAll(state.origins()));

            blocks.add("");
            blocks.add("Peer " + display(key.address(), endpoints));
            blocks.add("infoHash: " + key.infoHash());
            blocks.add("origem: " + (origins.isEmpty() ? "desconhecida" : origins.toString().replace("[", "").replace("]", "")));
            blocks.add("TCP: " + transportStatus(tcp));
            blocks.add("uTP: " + transportStatus(utp));
            blocks.add("BEP 55: " + bep55.availability());
            blocks.add("rendezvous: " + bep55.rendezvous());
            blocks.add("hole punching: " + holePunchStatus(utp, bep55));
            blocks.add("resultado: " + result(endpoints, tcp, utp));
            if (bep55.detail() != null && !bep55.detail().isBlank()) blocks.add("detalhe: " + bep55.detail());
        }
        return String.join(System.lineSeparator(), blocks);
    }

    private static PeerConnectivityManager.PeerState endpoint(List<PeerConnectivityManager.PeerState> states,
                                                               PeerConnectivityManager.Transport transport) {
        return states.stream().filter(state -> state.endpoint().transport() == transport)
                .max(Comparator.comparing(PeerConnectivityManager.PeerState::lastSeen)).orElse(null);
    }

    private static String display(InetAddress address, List<PeerConnectivityManager.PeerState> endpoints) {
        String host = address instanceof java.net.Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        return host + endpoints.stream().map(state -> Integer.toString(state.endpoint().port())).distinct()
                .reduce((first, second) -> first + "/" + second).map(ports -> ":" + ports).orElse("");
    }

    private static String transportStatus(PeerConnectivityManager.PeerState state) {
        if (state == null) return "não descoberto";
        PeerConnectivityManager.SocketAttempt attempt = state.lastSocketAttempt();
        if (attempt != null && attempt.failure() != PeerConnectivityManager.SocketFailure.NONE) return failure(attempt.failure());
        return switch (state.connection()) {
            case CONNECTED -> "conectado";
            case DIRECT_CONNECTING -> "conectando";
            case DIRECT_CONNECT_PENDING -> "aguardando conexão";
            case HOLE_PUNCH_PENDING -> "aguardando rendezvous";
            case HOLE_PUNCHING -> "tentando";
            case DIRECT_CONNECT_FAILED -> state.failureReason().isBlank() ? "falhou" : "falhou — " + state.failureReason();
            case UNREACHABLE -> state.failureReason().isBlank() ? "inalcançável" : "inalcançável — " + state.failureReason();
            case PORT_MAPPING_PENDING -> "aguardando port mapping";
            case DISCOVERED -> "descoberto";
        };
    }

    private static String holePunchStatus(PeerConnectivityManager.PeerState utp, Bep55Status status) {
        if (utp != null && utp.connection() == PeerConnectivityManager.ConnectionState.CONNECTED
                && utp.strategy() == PeerConnectivityManager.Strategy.HOLE_PUNCHING) return "concluído";
        if (utp != null && (utp.connection() == PeerConnectivityManager.ConnectionState.HOLE_PUNCHING
                || utp.connection() == PeerConnectivityManager.ConnectionState.HOLE_PUNCH_PENDING)) return "em andamento";
        return status.holePunch();
    }

    private static String result(List<PeerConnectivityManager.PeerState> all, PeerConnectivityManager.PeerState tcp,
                                 PeerConnectivityManager.PeerState utp) {
        if (utp != null && utp.connection() == PeerConnectivityManager.ConnectionState.CONNECTED
                && utp.strategy() == PeerConnectivityManager.Strategy.HOLE_PUNCHING) return "CONNECTED VIA UTP HOLE PUNCH";
        if (tcp != null && tcp.connection() == PeerConnectivityManager.ConnectionState.CONNECTED) return "CONNECTED VIA TCP";
        if (utp != null && utp.connection() == PeerConnectivityManager.ConnectionState.CONNECTED) return "CONNECTED VIA UTP";
        return all.stream().map(PeerConnectivityManager.PeerState::connection).anyMatch(state -> state == PeerConnectivityManager.ConnectionState.HOLE_PUNCHING)
                ? "HOLE PUNCH EM ANDAMENTO" : "PENDENTE";
    }

    private static String failure(PeerConnectivityManager.SocketFailure failure) {
        return switch (failure) {
            case TIMEOUT -> "timeout";
            case CONNECTION_REFUSED -> "conexão recusada";
            case NO_ROUTE -> "sem rota";
            case CONNECTION_RESET -> "conexão reiniciada";
            case HANDSHAKE_REJECTED -> "handshake rejeitado";
            case IO_EXCEPTION -> "erro de I/O";
            case SOCKET_EXCEPTION -> "erro de socket";
            case UNKNOWN -> "falha desconhecida";
            case NONE -> "sem tentativa";
        };
    }

    private record PeerKey(String infoHash, InetAddress address) { }
}
