# PEX / BEP 11 no Luffy — Etapa 12

O motor `bt-core` 1.10 já implementa Peer Exchange (BEP 11). O Luffy mantém
essa extensão ativa de forma explícita com `PeerExchangeModule`, sem criar um
protocolo PEX próprio.

Quando um peer conectado envia `ut_pex`, o módulo nativo do motor continua
alimentando sua fila interna de peers. Em paralelo, um observador do Luffy
registra cada peer adicionado, o peer que o apresentou e a origem `PEX`; em
seguida, o mesmo `PeerConnectivityManager` avalia a conexão direta, IPv6 ou
hole punching.

Cada endpoint preserva todas as origens que o apresentaram:

- `DHT`;
- `PEX`;
- `TRACKER` (pronto para uso futuro);
- `PEER_CACHE` (pronto para uso futuro);
- `MAGNET_METADATA`, usado para a dica direta `x.pe` do magnet;
- `UNKNOWN`, para uma fonte ainda não classificada.

Assim, estar conectado a um único peer pode revelar outros peers pelo PEX sem
depender apenas da DHT. As deduplicações e os limites de tentativa continuam
centralizados no `PeerConnectivityManager`.
