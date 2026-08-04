package dev.lufi.ui;

/** Iniciador neutro para permitir que o JAR único carregue o JavaFX empacotado. */
public final class LuffyLauncher {
    private LuffyLauncher() { }
    public static void main(String[] args) { LufiApplication.main(args); }
}
