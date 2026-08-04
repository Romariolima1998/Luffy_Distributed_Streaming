package dev.lufi.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cria regras de firewall exclusivamente para o executável que iniciou o Luffy. */
public final class WindowsFirewallManager {
    public boolean isLuffyAllowed(Path executable) throws Exception {
        return isLuffyAllowed(executable, ConnectivityService.P2P_PORT, ConnectivityService.DHT_PORT);
    }
    public boolean isLuffyAllowed(Path executable, int torrentPort, int dhtPort) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return true;
        String program = executable.toAbsolutePath().toString().replace("'", "''");
        String torrentRule = "Luffy P2P TCP " + torrentPort;
        String utpRule = "Luffy P2P uTP UDP " + torrentPort;
        String dhtRule = "Luffy DHT UDP " + dhtPort;
        String command = """
                $program = '%s'
                function Test-LuffyRule($name) {
                    $rule = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
                    if ($null -eq $rule) { return $false }
                    return ($rule | Get-NetFirewallApplicationFilter).Program -contains $program
                }
                if ((Test-LuffyRule '%s') -and (Test-LuffyRule '%s') -and (Test-LuffyRule '%s')) { exit 0 }
                exit 1
                """.formatted(program, torrentRule, utpRule, dhtRule);
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command).start();
        return process.waitFor() == 0;
    }
    public boolean allowLuffy(Path executable) throws Exception {
        return allowLuffy(executable, ConnectivityService.P2P_PORT, ConnectivityService.DHT_PORT);
    }
    public boolean allowLuffy(Path executable, int torrentPort, int dhtPort) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        String program = executable.toAbsolutePath().toString().replace("'", "''");
        String torrentRule = "Luffy P2P TCP " + torrentPort;
        String utpRule = "Luffy P2P uTP UDP " + torrentPort;
        String dhtRule = "Luffy DHT UDP " + dhtPort;
        Path result = Files.createTempFile("luffy-firewall-result-", ".txt");
        String resultFile = result.toAbsolutePath().toString().replace("'", "''");
        String script = """
                $ErrorActionPreference = 'Stop'
                try {
                    Get-NetFirewallRule -DisplayName 'Luffy P2P TCP *' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
                    Get-NetFirewallRule -DisplayName 'Luffy P2P uTP UDP *' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
                    Get-NetFirewallRule -DisplayName 'Luffy DHT UDP *' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
                    New-NetFirewallRule -DisplayName '%s' -Direction Inbound -Action Allow -Program '%s' -Protocol TCP -LocalPort %d -Profile Any | Out-Null
                    New-NetFirewallRule -DisplayName '%s' -Direction Inbound -Action Allow -Program '%s' -Protocol UDP -LocalPort %d -Profile Any | Out-Null
                    New-NetFirewallRule -DisplayName '%s' -Direction Inbound -Action Allow -Program '%s' -Protocol UDP -LocalPort %d -Profile Any | Out-Null
                    Set-Content -LiteralPath '%s' -Value 'OK' -Encoding UTF8
                } catch {
                    Set-Content -LiteralPath '%s' -Value $_.Exception.Message -Encoding UTF8
                    exit 1
                }
                """.formatted(torrentRule, program, torrentPort, utpRule, program, torrentPort, dhtRule, program, dhtPort, resultFile, resultFile);
        Path file = Files.createTempFile("luffy-firewall-", ".ps1");
        try {
            Files.writeString(file, script, StandardCharsets.UTF_8);
            String scriptFile = file.toAbsolutePath().toString().replace("'", "''");
            String command = """
                    $ErrorActionPreference = 'Stop'
                    try {
                        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '%s')
                        $child = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Normal -Wait -PassThru -ArgumentList $arguments
                        exit $child.ExitCode
                    } catch { exit 1223 }
                    """.formatted(scriptFile);
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command).start();
            int exitCode = process.waitFor();
            String detail = Files.readString(result, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
            if (exitCode != 0 || !"OK".equals(detail)) throw new IllegalStateException(detail.isBlank() ? "A autorização do Windows foi cancelada ou não foi concedida." : detail);
            return true;
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(result); }
    }
}
