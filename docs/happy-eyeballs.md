# Estratégia de conexão escalonada — Etapa 13

O `PeerConnectivityManager` reúne endpoints do mesmo infoHash durante 75 ms e
inicia uma rodada de até quatro caminhos controlados. A prioridade é:

1. IPv6/TCP;
2. IPv6/uTP;
3. IPv4/TCP;
4. IPv4/uTP.

Os caminhos são iniciados com 300 ms de diferença. Assim, o Luffy não espera
30 segundos por um caminho antes de experimentar outro, mas também não abre
uma tempestade de sockets.

O primeiro handshake BitTorrent aceito vence a rodada. As tentativas ainda
agendadas são canceladas e os timeouts restantes deixam de iniciar hole
punching enquanto outra rota da mesma rodada ainda estiver ativa. Quando
todos os caminhos falham, o fluxo normal pode então avaliar NAT traversal.

IPv6 só entra na rodada quando existe uma runtime de download IPv6 ativa; de
outro modo ele continua registrado separadamente como caminho futuro, sem ser
tentado por um socket IPv4.
