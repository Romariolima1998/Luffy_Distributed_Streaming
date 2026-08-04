package dev.lufi.infrastructure;

/** Cenários reproduzíveis do teste mínimo com teste.txt; não altera o mecanismo BitTorrent. */
public enum P2pDiagnosticScenario {
    LAN_DIRECT("A — Mesma LAN", "LAN DIRECT", true,
            "Máquinas na mesma rede local. O magnet inclui somente um peer LAN explícito (x.pe)."),
    DIRECT_IPV4("B — Redes diferentes com port forwarding", "DIRECT IPV4", false,
            "A precisa ter porta pública TCP confirmada e anúncio DHT permitido."),
    UPNP_MAPPED("C — NAT com UPnP", "UPNP MAPPED → DIRECT IPV4", false,
            "O painel de rede de A deve mostrar UPnP disponível e porta pública observada."),
    NAT_PMP_MAPPED("D — NAT-PMP", "NAT-PMP MAPPED → DIRECT IPV4", false,
            "O painel de rede de A deve mostrar NAT-PMP disponível e porta pública observada."),
    PCP_MAPPED("E — PCP", "PCP MAPPED → DIRECT IPV4", false,
            "O painel de rede de A deve mostrar PCP disponível e porta pública observada."),
    BEP55_HOLE_PUNCH("F — Dois NATs com peer C", "BEP55 → UDP/uTP HOLE PUNCH → DIRECT P2P", false,
            "São necessários A, B e um terceiro peer C já conectado ao mesmo swarm com BEP 55."),
    UNREACHABLE("G — NAT/CGNAT incompatível", "HOLE PUNCH FAILED → UNREACHABLE", false,
            "O teste deve encerrar no prazo e informar a ausência de rota, sem buscas infinitas.");

    private final String label;
    private final String expected;
    private final boolean lanPeerHint;
    private final String guidance;

    P2pDiagnosticScenario(String label, String expected, boolean lanPeerHint, String guidance) {
        this.label = label; this.expected = expected; this.lanPeerHint = lanPeerHint; this.guidance = guidance;
    }
    public String label() { return label; }
    public String expected() { return expected; }
    public boolean lanPeerHint() { return lanPeerHint; }
    public String guidance() { return guidance; }
    @Override public String toString() { return label; }
}
