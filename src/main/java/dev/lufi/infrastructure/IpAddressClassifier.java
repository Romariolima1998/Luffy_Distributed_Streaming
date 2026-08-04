package dev.lufi.infrastructure;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Classificação de endereços usada antes de ativar caminhos públicos IPv6. */
final class IpAddressClassifier {
    private IpAddressClassifier() { }

    /**
     * Aceita apenas unicast global roteável. Endereços de loopback, link-local,
     * site-local, multicast, ULA e a faixa de documentação não ativam o DHT IPv6.
     */
    static boolean isGlobalUnicastIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address ipv6)) return false;
        if (ipv6.isAnyLocalAddress() || ipv6.isLoopbackAddress() || ipv6.isLinkLocalAddress()
                || ipv6.isSiteLocalAddress() || ipv6.isMulticastAddress()) return false;
        byte[] bytes = ipv6.getAddress();
        int first = bytes[0] & 0xff;
        // Global unicast IPv6: 2000::/3. Isto exclui ULA fc00::/7 e faixas locais.
        if ((first & 0xe0) != 0x20) return false;
        // 2001:db8::/32 é reservado apenas para documentação, nunca para a Internet pública.
        return !(first == 0x20 && (bytes[1] & 0xff) == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
    }
}
