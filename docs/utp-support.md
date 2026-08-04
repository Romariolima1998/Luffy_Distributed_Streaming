# uTP no Luffy — Etapa 7

O motor externo `bt-core` 1.10 nao oferece BEP 29/uTP. Por isso o Luffy
mantem TCP como caminho independente e adicionou um transporte uTP proprio,
isolado em `UtpTransportService`.

## O que esta ativo

- Listener UDP uTP na porta P2P local (6891 por padrao), separado da DHT UDP
  (49001).
- Fluxo de bytes confiavel sobre os pacotes de cabecalho definidos no BEP 29:
  SYN, STATE, DATA, FIN e RESET, com ACK, retransmissao limitada e janela de
  envio.
- Sessoes uTP de entrada e saida.
- Ponte para o motor BitTorrent: os bytes do handshake, metadados e pecas do
  torrent atravessam a sessao uTP diretamente entre os dois peers. A ponte usa
  somente um par de sockets de loopback interno para adaptar a API TCP do
  motor; nao cria servidor, relay ou caminho de dados externo adicional.
- Registros separados para `uTP CONNECT START/SUCCESS/FAILED` e para o
  handshake BitTorrent sobre uTP.
- Regra de firewall especifica para o executavel do Luffy em UDP 6891, alem
  da regra TCP 6891 e DHT UDP 49001.

## Descoberta de endpoint

O mapeamento NAT pede uma regra UDP adicional para uTP e preserva a porta
externa que o roteador realmente devolver. Uma extensao BEP 10 privada entre
duas instancias Luffy (`luffy_endpoints`) compartilha as portas TCP e uTP
observadas. Isso evita inferir que a porta externa e igual a 6891.

Peers que nao conhecem essa extensao continuam podendo usar uTP/BEP 55 com a
porta anunciada no handshake, mas podem depender de o roteador preservar a
porta. O log deixa essa diferenca explicita.

## Limites conscientes

O transporte foi coberto por teste local bidirecional de UDP e o codec das
mensagens foi testado. A interoperabilidade completa com implementacoes
externas de uTP e a validacao em duas redes/NATs reais continuam sendo a
proxima validacao operacional; o Luffy nunca apresenta isso como conectividade
publica confirmada sem uma tentativa de entrada bem-sucedida.
