package dev.lufi.ui;

/** Iniciador neutro para permitir que o JAR único carregue o JavaFX empacotado. */
public final class LuffyLauncher {
    private LuffyLauncher() { }

    /**
     * libVLC/JNA pode manter threads nativas vivas mesmo depois de JavaFX ter
     * fechado todas as janelas. A aplicação primeiro libera seus recursos no
     * ciclo {@code Application.stop()} e, quando o JavaFX retorna, esta saída
     * final impede que o processo Luffy.exe fique residente no Windows.
     */
    public static void main(String[] args) {
        int exitCode = 0;
        LuffySingleInstance.Acquisition acquisition = null;
        try {
            acquisition = LuffySingleInstance.acquireOrForward(args);
            if (!acquisition.isPrimary()) return;
            LufiApplication.setSingleInstanceCoordinator(acquisition.primary());
            LufiApplication.main(args);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            // A segunda chamada só encaminha o pedido e sai. A principal libera
            // a porta local depois do ciclo JavaFX completo.
            if (acquisition != null && acquisition.isPrimary()) acquisition.primary().close();
            LufiApplication.clearSingleInstanceCoordinator();
        }
        System.exit(exitCode);
    }
}
