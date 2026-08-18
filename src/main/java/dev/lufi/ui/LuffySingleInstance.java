package dev.lufi.ui;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Coordena uma única instância do Luffy no computador local. O socket fica
 * preso a 127.0.0.1, portanto nunca aceita pedidos vindos da rede externa.
 */
final class LuffySingleInstance implements AutoCloseable {
    private static final int DEFAULT_PORT = 42_571;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final String ACK = "LUFFY_OK";

    enum RequestKind { MAGNET, TORRENT_FILE, ACTIVATE }

    record Request(RequestKind kind, String value) {
        Request {
            kind = Objects.requireNonNull(kind, "kind");
            value = value == null ? "" : value;
        }

        static Request fromArguments(String[] arguments) {
            if (arguments != null) {
                for (String argument : arguments) {
                    if (argument == null || argument.isBlank()) continue;
                    String value = argument.trim();
                    if (value.regionMatches(true, 0, "magnet:?", 0, "magnet:?".length())) {
                        return new Request(RequestKind.MAGNET, value);
                    }
                    if (value.toLowerCase(Locale.ROOT).endsWith(".torrent")) {
                        return new Request(RequestKind.TORRENT_FILE, value);
                    }
                }
            }
            return new Request(RequestKind.ACTIVATE, "");
        }
    }

    record Acquisition(LuffySingleInstance primary, boolean forwarded) {
        boolean isPrimary() { return primary != null; }
    }

    private final ServerSocket server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();
    private final Object requestLock = new Object();
    private volatile Consumer<Request> requestHandler;

    private LuffySingleInstance(ServerSocket server) {
        this.server = server;
        Thread.ofVirtual().name("luffy-single-instance-listener").start(this::acceptLoop);
    }

    static Acquisition acquireOrForward(String[] arguments) {
        return acquireOrForward(arguments, DEFAULT_PORT);
    }

    /** Visível para testes para não competir pela porta fixa do aplicativo. */
    static Acquisition acquireOrForward(String[] arguments, int port) {
        Request request = Request.fromArguments(arguments);
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return new Acquisition(new LuffySingleInstance(server), false);
        } catch (BindException alreadyRunning) {
            if (forward(port, request)) return new Acquisition(null, true);
            throw new IllegalStateException("O Luffy já está em execução, mas não respondeu ao pedido local.", alreadyRunning);
        } catch (IOException error) {
            throw new IllegalStateException("Não foi possível iniciar a comunicação local do Luffy.", error);
        }
    }

    int port() { return server.getLocalPort(); }

    void setRequestHandler(Consumer<Request> handler) {
        Consumer<Request> active = Objects.requireNonNull(handler, "handler");
        synchronized (requestLock) {
            requestHandler = active;
            Request request;
            while ((request = pending.poll()) != null) active.accept(request);
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = server.accept();
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    socket.close();
                    continue;
                }
                Thread.ofVirtual().name("luffy-single-instance-request").start(() -> readRequest(socket));
            } catch (IOException error) {
                if (running.get()) System.err.println("Falha na comunicação local do Luffy: " + error.getMessage());
            }
        }
    }

    private void readRequest(Socket socket) {
        try (socket; DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            RequestKind kind = RequestKind.valueOf(input.readUTF());
            String value = input.readUTF();
            dispatch(new Request(kind, value));
            output.writeUTF(ACK);
            output.flush();
        } catch (IOException | RuntimeException ignored) {
            // Entrada inválida de uma aplicação local não altera o estado do player.
        }
    }

    private void dispatch(Request request) {
        synchronized (requestLock) {
            Consumer<Request> handler = requestHandler;
            if (handler == null) pending.add(request);
            else handler.accept(request);
        }
    }

    private static boolean forward(int port, Request request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                 DataInputStream input = new DataInputStream(socket.getInputStream())) {
                output.writeUTF(request.kind().name());
                output.writeUTF(request.value());
                output.flush();
                return ACK.equals(input.readUTF());
            }
        } catch (IOException error) {
            return false;
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { server.close(); }
        catch (IOException ignored) { }
        synchronized (requestLock) {
            pending.clear();
            requestHandler = null;
        }
    }
}
