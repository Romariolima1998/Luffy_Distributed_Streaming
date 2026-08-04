package dev.lufi.infrastructure;

import bt.metainfo.TorrentId;
import bt.net.ConnectionResult;
import bt.net.IPeerConnectionFactory;
import bt.net.Peer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Ponto unico de integracao reflexiva com a factory interna do bt-core 1.10.
 * A assinatura e validada uma vez, durante a inicializacao da ponte; assim uma
 * atualizacao incompativel falha cedo e com uma mensagem acionavel.
 */
public final class BtCoreConnectionFactoryAdapter implements EstablishedPeerConnectionPromoter {
    public static final String EXPECTED_BT_CORE_VERSION = "1.10";

    private static final String OUTGOING_METHOD_NAME = "createConnection";
    private static final Class<?>[] OUTGOING_SIGNATURE = {
            Peer.class, TorrentId.class, SocketChannel.class, boolean.class
    };

    private final IPeerConnectionFactory factory;
    private final Method createOutgoingThroughChannel;

    public BtCoreConnectionFactoryAdapter(IPeerConnectionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.createOutgoingThroughChannel = locateValidatedOutgoingMethod(factory.getClass());
    }

    public String expectedBtCoreVersion() {
        return EXPECTED_BT_CORE_VERSION;
    }

    @Override
    public CompletionStage<PromotionResult> promoteOutgoing(TorrentId torrentId, Peer remotePeer,
                                                              UtpTransportService.UtpSession session,
                                                              SocketChannel btCoreChannel) {
        Objects.requireNonNull(torrentId, "torrentId");
        Objects.requireNonNull(remotePeer, "remotePeer");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(btCoreChannel, "btCoreChannel");
        try {
            ConnectionResult result = (ConnectionResult) createOutgoingThroughChannel.invoke(
                    factory, remotePeer, torrentId, btCoreChannel, false);
            return CompletableFuture.completedFuture(new PromotionResult(result));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            closeAfterFailure(btCoreChannel);
            return CompletableFuture.failedFuture(integrationFailure("promover a conexao uTP de saida", error));
        }
    }

    @Override
    public CompletionStage<PromotionResult> promoteIncoming(Peer remotePeer, UtpTransportService.UtpSession session,
                                                              SocketChannel btCoreChannel) {
        Objects.requireNonNull(remotePeer, "remotePeer");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(btCoreChannel, "btCoreChannel");
        try {
            ConnectionResult result = factory.createIncomingConnection(remotePeer, btCoreChannel);
            return CompletableFuture.completedFuture(new PromotionResult(result));
        } catch (RuntimeException error) {
            closeAfterFailure(btCoreChannel);
            return CompletableFuture.failedFuture(integrationFailure("promover a conexao uTP de entrada", error));
        }
    }

    /** Visivel aos testes para verificar a assinatura sem instanciar o bt-core. */
    static Method locateValidatedOutgoingMethod(Class<?> factoryType) {
        Objects.requireNonNull(factoryType, "factoryType");
        boolean namedMethodFound = false;
        for (Class<?> current = factoryType; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!OUTGOING_METHOD_NAME.equals(method.getName())) continue;
                namedMethodFound = true;
                if (!Arrays.equals(method.getParameterTypes(), OUTGOING_SIGNATURE)
                        || !ConnectionResult.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    if (!method.trySetAccessible()) {
                        throw new BtCoreIntegrationException("bt-core " + EXPECTED_BT_CORE_VERSION
                                + " bloqueou o acesso a " + describeExpectedSignature());
                    }
                } catch (SecurityException error) {
                    throw new BtCoreIntegrationException("bt-core " + EXPECTED_BT_CORE_VERSION
                            + " bloqueou o acesso a " + describeExpectedSignature(), error);
                }
                return method;
            }
        }
        String problem = namedMethodFound ? "possui assinatura incompativel" : "nao expoe o metodo";
        throw new BtCoreIntegrationException("bt-core " + EXPECTED_BT_CORE_VERSION + " " + problem + " "
                + describeExpectedSignature() + "; a ponte uTP nao pode ser inicializada com seguranca.");
    }

    private static BtCoreIntegrationException integrationFailure(String operation, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : error;
        return new BtCoreIntegrationException("Integracao com bt-core " + EXPECTED_BT_CORE_VERSION + " falhou ao "
                + operation + " pela factory validada: " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()), cause);
    }

    private static void closeAfterFailure(SocketChannel channel) {
        try {
            channel.close();
        } catch (Exception ignored) {
            // A falha original de integracao e mais relevante para o chamador.
        }
    }

    private static String describeExpectedSignature() {
        return "createConnection(Peer, TorrentId, SocketChannel, boolean): ConnectionResult";
    }
}
