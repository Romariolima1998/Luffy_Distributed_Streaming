package dev.lufi.infrastructure;

import bt.module.ProtocolModule;
import bt.module.ServiceModule;
import com.google.inject.Binder;
import com.google.inject.Module;

/** Registra ut_holepunch no BEP 10 e o agente que encaminha mensagens BEP 55. */
final class Bep55HolePunchModule implements Module {
    private final Bep55HolePunchAgent agent;
    Bep55HolePunchModule(Bep55HolePunchAgent agent) { this.agent = agent; }
    @Override public void configure(Binder binder) {
        ProtocolModule.extend(binder).addExtendedMessageHandler("ut_holepunch", new Bep55HolePunchMessageHandler());
        // Registro do agente será validado pela etapa de integração BEP 55.
        ServiceModule.extend(binder).addMessagingAgent(agent);
    }
}
