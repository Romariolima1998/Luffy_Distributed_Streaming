package dev.lufi.infrastructure;

/** Origem de uma evidência de endpoint. A confiabilidade não substitui confirmação externa. */
public enum ObservationSource {
    LOCAL("local", 0),
    CONFIGURED("configurado", 10),
    UPNP("UPnP", 60),
    NAT_PMP("NAT-PMP", 70),
    PCP("PCP", 80),
    PEER_OBSERVED("observação de peer", 90),
    EXTERNAL_PROBE("sonda externa", 100),
    UNKNOWN("desconhecida", 0);

    private final String label;
    private final int reliability;

    ObservationSource(String label, int reliability) {
        this.label = label;
        this.reliability = reliability;
    }

    public String label() { return label; }
    int reliability() { return reliability; }
}
