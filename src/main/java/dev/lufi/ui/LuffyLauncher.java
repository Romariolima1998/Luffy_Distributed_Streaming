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
        try {
            LufiApplication.main(args);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
