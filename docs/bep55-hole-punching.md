# BEP 55 / Hole Punching no Luffy — Etapa 8

O Luffy anuncia `ut_holepunch` pelo handshake estendido BEP 10 e implementa
os tres payloads binarios do BEP 55:

- `rendezvous`;
- `connect`;
- `error` (`NoSuchPeer`, `NotConnected`, `NoSupport` e `NoSelf`).

## Validacao do protocolo — Etapa 9

O codec aceita somente os valores do BEP 55: `RENDEZVOUS=0`, `CONNECT=1` e
`ERROR=2`; familia `IPv4=0` ou `IPv6=1`; endereco de quatro ou dezesseis
bytes; porta entre 1 e 65535; e os codigos de erro definidos pela especificacao.
Pacotes com familia, tipo, porta ou codigo desconhecido sao recusados.

Antes de encaminhar uma solicitacao, o Luffy tambem verifica que o endpoint
nao e nulo, loopback, link-local ou multicast; que o relay ja possui uma
conexao BitTorrent para o mesmo infoHash; que relay e alvo anunciaram
`ut_holepunch`; e que o alvo nao e o proprio peer. Um `CONNECT` repetido para
uma sessao uTP em andamento e ignorado, como previsto pelo BEP 55.

IPv6 e codificado, decodificado e mantido como caminho independente. Como o
listener uTP atual foi aberto em IPv4, um `CONNECT` IPv6 e registrado como
adiado em vez de ser enviado por um socket IPv4 incorreto; TCP IPv6 direto
continua disponivel como caminho separado.

## Capacidades do peer — Etapa 10

Em cada extension handshake o log agora registra `extension protocol`,
`ut_holepunch`, `ut_metadata`, `ut_pex` e `utp`. Para BEP 55, o suporte a uTP
e inferido do anuncio de `ut_holepunch`, porque o BEP 29 nao define uma chave
BEP 10 independente para anuncia-lo.

O Luffy apenas escolhe relay ou envia `rendezvous` quando o peer anunciou
extension protocol, `ut_holepunch` e, portanto, uTP. Se isso nao existir, o
log registra `HOLE PUNCH UNSUPPORTED`. O mesmo destino nao recebe tentativas
automaticas repetidas durante dois minutos; o usuario pode pedir uma nova
rodada ao abrir o magnet novamente.

## Rendezvous descentralizado — Etapa 11

O Luffy mantém, por infoHash, a lista dos peers atualmente conectados que
anunciaram BEP 55/uTP. O peer A seleciona somente um desses peers como
candidato C. C então verifica localmente se também já está conectado ao alvo
B no mesmo swarm antes de encaminhar `CONNECT`; nenhum servidor central é
usado e nenhum dado do torrent passa por C.

Quando só existem A e B e não há C conectado aos dois, o estado é encerrado
como indisponível. O log e a tela mostram: `Hole punch indisponível: nenhum
peer rendezvous conectado ao target.` O endpoint passa a `UNREACHABLE` para
essa rodada, em vez de continuar em “buscando peers”.

O relay e sempre um peer BitTorrent que ja esta conectado ao mesmo infoHash.
Ele encaminha apenas mensagens de controle. O torrent, as pecas e o video
nunca passam pelo relay.

## Fluxo aplicado

1. A DHT devolve um peer como `DISCOVERED`.
2. O `PeerConnectivityManager` tenta TCP direto e registra o resultado real.
3. Somente apos a falha direta ele altera o caminho para
   `HOLE_PUNCH_PENDING` e pede BEP 55.
4. Um relay conectado confirma que conhece o alvo e envia `connect` aos dois
   peers.
5. Ambos iniciam uTP/UDP para o endpoint informado.
6. A ponte entrega a sessao uTP ao motor BitTorrent; o handshake e as pecas
   continuam usando o protocolo BitTorrent normal.
7. Apenas um handshake aceito promove o endpoint para `CONNECTED`.

## Portas externas reais

Quando os dois peers sao Luffy, a extensao BEP 10 `luffy_endpoints` informa ao
relay as portas TCP e uTP observadas por PCP, NAT-PMP ou UPnP. Assim, um
mapeamento como `192.168.1.5:6891 -> IP-publico:43817` e encaminhado usando
43817, e nao por suposicao usando 6891. Essa extensao e somente um complemento
para os peers Luffy; as mensagens `ut_holepunch` permanecem no formato BEP 55.

## Protecoes

- Um peer nao pode solicitar conexao para ele mesmo.
- O relay exige que iniciador e alvo estejam no mesmo torrent e que ambos
  tenham anunciado `ut_holepunch`.
- Falhas de socket e handshake ficam registradas; nao ha repeticao infinita.
- Quando nao existe relay conectado ao alvo, a rodada termina como
  `UNREACHABLE`; nao finge que o NAT traversal funcionou nem continua em busca infinita.
