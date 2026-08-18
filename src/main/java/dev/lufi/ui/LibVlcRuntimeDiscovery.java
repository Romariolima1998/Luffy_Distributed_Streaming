package dev.lufi.ui;

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;
import uk.co.caprica.vlcj.binding.lib.LibC;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Descoberta isolada do runtime nativo do libVLC para o backend vlcj.
 *
 * <p>O Luffy procura primeiro o runtime que vem dentro do próprio pacote da
 * aplicação. A busca automática do vlcj fica como alternativa de
 * desenvolvimento/compatibilidade para instalações antigas.</p>
 */
final class LibVlcRuntimeDiscovery {
    private static final Object DISCOVERY_LOCK = new Object();
    private static final String BUNDLED_RUNTIME_PROPERTY = "luffy.libvlc.path";
    private static final String BUNDLED_RUNTIME_ENVIRONMENT = "LUFFY_LIBVLC_HOME";
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
                log.accept("PLAYER BACKEND: libvlc-discovery; phase=runtime; result=already-loaded; "
                        + "path=" + discoveredPath + "; strategy=" + discoveredStrategy + ".");
                return Result.available(discoveredPath, discoveredStrategy);
            }

            Optional<RuntimeCandidate> bundledRuntime = bundledRuntime(platform, log);
            if (bundledRuntime.isPresent()) {
                RuntimeCandidate candidate = bundledRuntime.orElseThrow();
                BundledNativeDiscoveryStrategy strategy = new BundledNativeDiscoveryStrategy(candidate, platform, log);
                TracingNativeDiscovery nativeDiscovery = new TracingNativeDiscovery(log, strategy);
                try {
                    if (nativeDiscovery.discover()) {
                        return rememberDiscovery(nativeDiscovery, candidate.pluginsDirectory().toString(), log);
                    }
                    log.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; result=load-failed; path="
                            + candidate.root() + "; fallback=automatic.");
                } catch (RuntimeException | LinkageError error) {
                    log.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; result=failed; error="
                            + error.getClass().getSimpleName() + "; detail=" + safeMessage(error)
                            + "; fallback=automatic.");
                }
            } else {
                log.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; result=not-found.");
            }

            logCandidateDirectories(log);
            TracingNativeDiscovery nativeDiscovery = new TracingNativeDiscovery(log);
            try {
                if (!nativeDiscovery.discover()) {
                    return Result.unavailable(notFoundMessage());
                }
                return rememberDiscovery(nativeDiscovery, "", log);
            } catch (RuntimeException | LinkageError error) {
                String message = notFoundMessage();
                log.accept("PLAYER BACKEND: libvlc-discovery; result=failed; error="
                        + error.getClass().getSimpleName() + "; detail=" + safeMessage(error) + ".");
                return Result.unavailable(message);
            }
        }
    }

    private static Result rememberDiscovery(TracingNativeDiscovery discovery, String pluginPath, Consumer<String> log) {
        String path = discovery.discoveredPath();
        String strategy = discovery.successfulStrategy() == null
                ? "already-loaded"
                : discovery.successfulStrategy().getClass().getSimpleName();
        // NativeDiscovery pode retornar true por uma descoberta anterior no
        // mesmo processo. Nessa situação a biblioteca já está carregada.
        discoveredPath = path == null || path.isBlank() ? "already-loaded" : path;
        discoveredStrategy = strategy;
        log.accept("PLAYER BACKEND: libvlc-discovery; result=available; path=" + discoveredPath
                + "; strategy=" + discoveredStrategy
                + (pluginPath.isBlank() ? "" : "; plugins=" + pluginPath) + ".");
        return Result.available(discoveredPath, discoveredStrategy, pluginPath);
    }

    /** Localiza somente cópias completas: biblioteca, core e plugins do VLC. */
    private static Optional<RuntimeCandidate> bundledRuntime(PlatformInfo platform, Consumer<String> log) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addPath(candidates, System.getProperty(BUNDLED_RUNTIME_PROPERTY));
        addPath(candidates, System.getenv(BUNDLED_RUNTIME_ENVIRONMENT));
        addLauncherCandidates(candidates);
        addCodeSourceCandidate(candidates);

        for (Path candidate : candidates) {
            log.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; candidate=" + candidate + ".");
            if (isBundledRuntime(candidate, platform)) {
                return Optional.of(new RuntimeCandidate(candidate, candidate.resolve("plugins")));
            }
        }
        return Optional.empty();
    }

    private static void addLauncherCandidates(LinkedHashSet<Path> candidates) {
        String rawLauncher = System.getProperty("jpackage.app-path");
        if (rawLauncher == null || rawLauncher.isBlank()) return;
        try {
            Path launcher = Path.of(rawLauncher).toAbsolutePath().normalize();
            Path launcherDirectory = launcher.getParent();
            if (launcherDirectory == null) return;
            // O jpackage preserva o nome do diretório passado em --app-content.
            // Na imagem Windows ele fica ao lado de app/ e runtime/.
            candidates.add(launcherDirectory.resolve("vlc"));
            candidates.add(launcherDirectory.resolve("app").resolve("vlc"));
            Path imageDirectory = launcherDirectory.getParent();
            if (imageDirectory != null) {
                candidates.add(imageDirectory.resolve("lib").resolve("app").resolve("vlc"));
            }
        } catch (RuntimeException ignored) {
            // A inicialização continua com os demais caminhos válidos.
        }
    }

    private static void addCodeSourceCandidate(LinkedHashSet<Path> candidates) {
        try {
            URI location = LibVlcRuntimeDiscovery.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codeSource = Path.of(location).toAbsolutePath().normalize();
            Path applicationDirectory = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (applicationDirectory != null) {
                candidates.add(applicationDirectory.resolve("vlc"));
                Path imageDirectory = applicationDirectory.getParent();
                if (imageDirectory != null) candidates.add(imageDirectory.resolve("vlc"));
            }
        } catch (Exception ignored) {
            // Em ambientes restritos o CodeSource pode não estar disponível.
        }
    }

    private static void addPath(LinkedHashSet<Path> candidates, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return;
        try {
            candidates.add(Path.of(rawPath.trim()).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            // Uma configuração malformada não impede a descoberta automática.
        }
    }

    private static boolean isBundledRuntime(Path root, PlatformInfo platform) {
        if (root == null || !Files.isDirectory(root) || !Files.isDirectory(root.resolve("plugins"))) return false;
        if (platform.windows()) {
            return Files.isRegularFile(root.resolve("libvlc.dll"))
                    && Files.isRegularFile(root.resolve("libvlccore.dll"));
        }
        try (var files = Files.list(root)) {
            boolean library = files.anyMatch(file -> file.getFileName().toString().matches("libvlc\\.so(?:\\..*)?"));
            if (!library) return false;
        } catch (IOException error) {
            return false;
        }
        try (var files = Files.list(root)) {
            return files.anyMatch(file -> file.getFileName().toString().matches("libvlccore\\.so(?:\\..*)?"));
        } catch (IOException error) {
            return false;
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
        return "O runtime integrado do libVLC não foi encontrado. Reinstale o Luffy ou configure "
                + "-D" + BUNDLED_RUNTIME_PROPERTY + "=<diretório-do-libVLC>. Consulte os caminhos tentados no diagnóstico.";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "sem detalhe" : message.replace(';', ',');
    }

    static final class Result {
        private final boolean available;
        private final String path;
        private final String strategy;
        private final String pluginPath;
        private final String failureMessage;

        private Result(boolean available, String path, String strategy, String pluginPath, String failureMessage) {
            this.available = available;
            this.path = path;
            this.strategy = strategy;
            this.pluginPath = pluginPath;
            this.failureMessage = failureMessage;
        }

        static Result available(String path, String strategy) {
            return available(path, strategy, "");
        }

        static Result available(String path, String strategy, String pluginPath) {
            return new Result(true, path, strategy, pluginPath == null ? "" : pluginPath, "");
        }

        static Result unavailable(String failureMessage) {
            return new Result(false, "", "", "", failureMessage);
        }

        boolean available() { return available; }
        String path() { return path; }
        String strategy() { return strategy; }
        String pluginPath() { return pluginPath; }
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
            boolean supportedSystem = windows() || linux();
            boolean x64 = osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64");
            boolean jvm64 = dataModel.isEmpty() || dataModel.equals("64");
            return supportedSystem && x64 && jvm64 && javaSpecificationVersion.equals("21");
        }

        boolean windows() { return osName.contains("windows"); }
        boolean linux() { return osName.contains("linux"); }

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

    private record RuntimeCandidate(Path root, Path pluginsDirectory) { }

    /** Estratégia que dá precedência ao runtime que é distribuído com o Luffy. */
    private static final class BundledNativeDiscoveryStrategy implements NativeDiscoveryStrategy {
        private final RuntimeCandidate candidate;
        private final PlatformInfo platform;
        private final Consumer<String> diagnostics;

        private BundledNativeDiscoveryStrategy(RuntimeCandidate candidate, PlatformInfo platform, Consumer<String> diagnostics) {
            this.candidate = candidate;
            this.platform = platform;
            this.diagnostics = diagnostics;
        }

        @Override public boolean supported() { return true; }

        @Override public String discover() {
            String plugins = candidate.pluginsDirectory().toString();
            try {
                int result = platform.windows()
                        ? LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=" + plugins)
                        : LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", plugins, 1);
                diagnostics.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; pluginPath=" + plugins
                        + "; configured=" + (result == 0) + ".");
            } catch (RuntimeException | LinkageError error) {
                diagnostics.accept("PLAYER BACKEND: libvlc-discovery; phase=bundled; pluginPath=" + plugins
                        + "; configured=false; error=" + error.getClass().getSimpleName() + ".");
            }
            return candidate.root().toString();
        }

        @Override public boolean onFound(String path) { return true; }

        @Override public boolean onSetPluginPath(String ignoredRoot) { return true; }
    }

    private static final class TracingNativeDiscovery extends NativeDiscovery {
        private final Consumer<String> diagnostics;

        private TracingNativeDiscovery(Consumer<String> diagnostics, NativeDiscoveryStrategy... strategies) {
            super(strategies);
            this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
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
