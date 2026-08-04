package dev.lufi.infrastructure;

/**
 * Papel de uma conexão no orçamento local. A ordem declarada aqui é também a
 * ordem de proteção de recursos: conteúdo do usuário vem antes da malha de
 * controle, e a malha vem antes apenas da participação Assist.
 */
public enum ConnectionRole {
    STREAM(0),
    DOWNLOAD(1),
    SEED(2),
    RENDEZVOUS(3),
    OVERLAY(4),
    ASSIST(5);

    private final int priority;

    ConnectionRole(int priority) { this.priority = priority; }

    public int priority() { return priority; }

    /** Stream e download usam o mesmo orçamento de aquisição de conteúdo do usuário. */
    public boolean isUserTransfer() { return this == STREAM || this == DOWNLOAD; }
    /** Rendezvous e overlay compartilham o orçamento de controle persistente. */
    public boolean isOverlayControl() { return this == RENDEZVOUS || this == OVERLAY; }
    public boolean isControlPlane() { return isOverlayControl() || this == ASSIST; }
}
