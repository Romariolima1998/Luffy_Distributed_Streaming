package dev.lufi.ui;

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

import java.io.File;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Descoberta isolada do runtime nativo do libVLC para o backend vlcj.
 *
 * <p>O mecanismo padrao do vlcj continua sendo a autoridade para carregar a
 * biblioteca. Esta classe apenas valida a plataforma suportada pelo MVP e
 * torna o processo observavel no diagnostico do Luffy.</p>
 */
final class LibVlcRuntimeDiscovery {
    private static final Object DISCOVERY_LOCK = new Object();
    private static volatile String discoveredPath;
    private static volatile String discoveredStrategy;

    private LibVlcRuntimeDiscovery() { }

    static Result discover(Consumer<String> diagnostics) {
        Consumer<String> log = diagnostics == null ? ignored -> { } : diagnostics;
        PlatformInfo platform = PlatformInfo.current();
        log.accept("PLAYER BACKEND: libvlc-discovery; phase=platform-check; " + platform.describe() + ".");
        if (!platform.supported()) {
            String message = "LibVLC requer Windows ou Linux x64 com Java 21 x64; detectado " + platform.describe() + ".";
            log.accept("PLAYER BACKEND: libvlc-discovery; result=unsupported-platform; message=" + message);
            return Result.unavailable(message);
        }

        synchronized (DISCOVERY_LOCK) {
            if (discoveredPath != null) {
                log.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; result=already-loaded; "
                        + "path=" + discoveredPath + "; strategy=" + discoveredStrategy + ".");
                return Result.available(discoveredPath, discoveredStrategy);
            }

            logCandidateDirectories(log);
            TracingNativeDiscovery nativeDiscovery = new TracingNativeDiscovery(log);
            try {
                if (!nativeDiscovery.discover()) {
                    return Result.unavailable(notFoundMessage());
                }
                String path = nativeDiscovery.discoveredPath();
                String strategy = nativeDiscovery.successfulStrategy() == null
                        ? "already-loaded"
                        : nativeDiscovery.successfulStrategy().getClass().getSimpleName();
                // NativeDiscovery pode retornar true por uma descoberta anterior
                // no mesmo processo. Nessa situacao o libVLC ja esta carregado.
                discoveredPath = path == null || path.isBlank() ? "already-loaded" : path;
                discoveredStrategy = strategy;
                log.accept("PLAYER BACKEND: libvlc-discovery; result=available; path=" + discoveredPath
                        + "; strategy=" + discoveredStrategy + ".");
                return Result.available(discoveredPath, discoveredStrategy);
            } catch (RuntimeException | LinkageError error) {
                String message = notFoundMessage();
                log.accept("PLAYER BACKEND: libvlc-discovery; result=failed; error="
                        + error.getClass().getSimpleName() + "; detail=" + safeMessage(error) + ".");
                return Result.unavailable(message);
            }
        }
    }

    static PlatformInfo platformInfo(String osName, String osArch, String dataModel, String javaSpecificationVersion) {
        return new PlatformInfo(osName, osArch, dataModel, javaSpecificationVersion);
    }

    private static void logCandidateDirectories(Consumer<String> log) {
        logConfiguredPaths(log, "jna.library.path", System.getProperty("jna.library.path"));
        logConfiguredPaths(log, "java.library.path", System.getProperty("java.library.path"));
        logConfiguredPaths(log, "VLC_PLUGIN_PATH", System.getenv("VLC_PLUGIN_PATH"));
        for (DiscoveryDirectoryProvider provider : ServiceLoader.load(DiscoveryDirectoryProvider.class)) {
            try {
                if (!provider.supported()) continue;
                String providerName = provider.getClass().getSimpleName();
                String[] directories = provider.directories();
                if (directories == null) continue;
                for (String directory : directories) {
                    if (directory == null || directory.isBlank()) continue;
                    log.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; candidate=" + directory
                            + "; source=" + providerName + ".");
                }
            } catch (RuntimeException error) {
                log.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; provider="
                        + provider.getClass().getSimpleName() + "; result=skipped; error="
                        + error.getClass().getSimpleName() + ".");
            }
        }
    }

    private static void logConfiguredPaths(Consumer<String> log, String source, String paths) {
        if (paths == null || paths.isBlank()) return;
        for (String path : paths.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!path.isBlank()) {
                log.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; candidate=" + path.trim()
                        + "; source=" + source + ".");
            }
        }
    }

    private static String notFoundMessage() {
        return "LibVLC nao encontrado. Instale VLC/libVLC 3.x x64 compativel ou configure "
                + "-Djna.library.path=<diretorio-do-VLC>. Consulte os caminhos tentados no diagnostico.";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "sem detalhe" : message.replace(';', ',');
    }

    static final class Result {
        private final boolean available;
        private final String path;
        private final String strategy;
        private final String failureMessage;

        private Result(boolean available, String path, String strategy, String failureMessage) {
            this.available = available;
            this.path = path;
            this.strategy = strategy;
            this.failureMessage = failureMessage;
        }

        static Result available(String path, String strategy) {
            return new Result(true, path, strategy, "");
        }

        static Result unavailable(String failureMessage) {
            return new Result(false, "", "", failureMessage);
        }

        boolean available() { return available; }
        String path() { return path; }
        String strategy() { return strategy; }
        String failureMessage() { return failureMessage; }
    }

    static final class PlatformInfo {
        private final String osName;
        private final String osArch;
        private final String dataModel;
        private final String javaSpecificationVersion;

        private PlatformInfo(String osName, String osArch, String dataModel, String javaSpecificationVersion) {
            this.osName = normalized(osName);
            this.osArch = normalized(osArch);
            this.dataModel = normalized(dataModel);
            this.javaSpecificationVersion = normalized(javaSpecificationVersion);
        }

        static PlatformInfo current() {
            return new PlatformInfo(System.getProperty("os.name"), System.getProperty("os.arch"),
                    System.getProperty("sun.arch.data.model"), System.getProperty("java.specification.version"));
        }

        boolean supported() {
            boolean supportedSystem = osName.contains("windows") || osName.contains("linux");
            boolean x64 = osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64");
            boolean jvm64 = dataModel.isEmpty() || dataModel.equals("64");
            return supportedSystem && x64 && jvm64 && javaSpecificationVersion.equals("21");
        }

        String describe() {
            return "os=" + value(osName) + "; arch=" + value(osArch) + "; jvmBits=" + value(dataModel)
                    + "; java=" + value(javaSpecificationVersion);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }

        private static String value(String value) {
            return value.isEmpty() ? "unknown" : value;
        }
    }

    private static final class TracingNativeDiscovery extends NativeDiscovery {
        private final Consumer<String> diagnostics;

        private TracingNativeDiscovery(Consumer<String> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        protected void onFound(String path, NativeDiscoveryStrategy strategy) {
            diagnostics.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; result=found; path=" + path
                    + "; strategy=" + strategy.getClass().getSimpleName() + ".");
        }

        @Override
        protected void onFailed(String path, NativeDiscoveryStrategy strategy) {
            diagnostics.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; result=load-failed; path=" + path
                    + "; strategy=" + strategy.getClass().getSimpleName() + ".");
        }

        @Override
        protected void onNotFound() {
            diagnostics.accept("PLAYER BACKEND: libvlc-discovery; phase=automatic; result=not-found.");
        }
    }
}
