# Compatibilidade BitTorrent do Luffy

Esta matriz é a referência obrigatória antes de adicionar ou alterar mensagens de rede. O Luffy usa o protocolo BitTorrent para metadados, handshake, peças e blocos; não existe protocolo próprio para transportar arquivos.

## Biblioteca verificada

- Biblioteca: `com.github.atomashpolskiy:bt-core` e `bt-dht`
- Versão: `1.10`
- Evidência local: dependências declaradas em `build.gradle.kts` e arquivos resolvidos no cache do Gradle.
- Evidência do fornecedor: a lista oficial da biblioteca declara BEP 10 e BEP 11, mas não declara BEP 29 nem BEP 55.

| BEP | Biblioteca 1.10 | Uso no Luffy | Decisão de interoperabilidade |
| --- | --- | --- | --- |
| BEP 10 — Extension Protocol | **SIM** | Handshake estendido, negociação de extensões e `ut_holepunch`. | Usar a implementação nativa do `bt-core`; IDs são negociados por peer. |
| BEP 11 — Peer Exchange | **SIM** | `PeerExchangeModule` e observação de peers `ut_pex`. | Usar o módulo nativo; PEX continua apenas uma fonte de descoberta. |
| BEP 29 — uTP | **NÃO** | Há um transporte manual experimental em `UtpTransportService`. | Não pode ser considerado interoperável até passar testes contra outro cliente BitTorrent. TCP continua o caminho padrão. |
| BEP 55 — Hole Punching | **NÃO** | Há codec/coordenação manual de `ut_holepunch`. | Só pode ser usado depois de haver BEP 29 funcional e capacidade `ut_holepunch` negociada no BEP 10. O relay nunca transporta dados do torrent. |

## Regras adotadas

1. Metadados, peças e blocos seguem sempre o peer-wire protocol BitTorrent.
2. BEP 10 e BEP 11 não são reimplementados: o Luffy usa os módulos do `bt-core`.
3. A antiga extensão exclusiva `luffy_endpoints` foi removida. O fluxo BEP 55 usa somente a mensagem oficial `ut_holepunch`; peers que não a reconhecem a ignoram conforme o BEP 10.
4. O endpoint de um `CONNECT` BEP 55 usa a porta anunciada pelo peer no handshake BEP 10. Caso TCP e uTP não possuam a mesma porta pública, o Luffy deve registrar a limitação e não inventar uma mensagem proprietária para contorná-la.
5. Toda implementação manual futura de BEP deve incluir: referência à especificação, testes de codec, teste de interoperabilidade com outro cliente e fallback claro para TCP.

## Pipeline automático de tentativa

As camadas não chamam umas às outras diretamente fora de suas responsabilidades:

1. **DISCOVERY**: DHT e PEX apenas retornam endpoints.
2. **CONNECTIVITY**: `PeerConnectivityManager` tenta TCP direto e, se falhar, uTP direto de forma controlada.
3. **NAT / HOLEPUNCH**: `NatTraversalService` registra PCP, NAT-PMP e UPnP; somente após TCP e uTP diretos falharem o manager solicita BEP 55 a um peer C já conectado.
4. **BITTORRENT DATA**: após um socket funcionar, o motor BitTorrent faz handshake, busca metadados, solicita blocos, verifica peças e conclui o arquivo.

O log inclui os marcadores `[DISCOVERY]`, `[CONNECTIVITY]`, `[NAT]`, `[HOLEPUNCH]`, `[UTP]`, `[BITTORRENT]`, `[DOWNLOAD]` e `[RESULT]`. Uma falha sempre inclui endpoint, protocolo e próximo fallback; o fluxo termina em `PEER UNREACHABLE` quando não há outro caminho permitido.

## Trackers recebidos pelo magnet

O Luffy preserva cada parametro repetido `tr` na ordem recebida. Ao criar uma
sessao de download, streaming, Swarm Assist ou diagnostico, o gateway entrega
`MagnetLink.toUri()` diretamente a `Bt.client(...).magnet(...)`; nenhum tracker
e reduzido a uma unica entrada no caminho ate o bt-core.

O `bt-core` 1.10 ja contem `MagnetUriParser`, `TrackerService`,
`TrackerPeerSourceFactory` e o transporte UDP tracker. Portanto o Luffy usa o
suporte existente da biblioteca, junto da DHT, sem criar cliente tracker ou
protocolo proprietario.

## Trackers HTTP e HTTPS

Além do suporte UDP presente no `bt-core`, o projeto inclui
`com.github.atomashpolskiy:bt-http-tracker-client:1.10`, o modulo oficial da
mesma biblioteca que registra os esquemas `http` e `https`. O caminho continua
sendo o do bt-core: `MagnetLink.toUri()` preserva cada `tr`, o
`TrackerPeerSourceFactory` anuncia, interpreta a resposta bencode compacta e
emite `PeerDiscoveredEvent`.

`HttpTrackerCompatibilityIntegrationTest` prova este fluxo em loopback: o
modulo anuncia com a porta TCP configurada, recebe uma resposta compacta IPv4
e o peer chega ao `PeerConnectivityManager` como origem `TRACKER`. Nao existe
cliente tracker proprietario no Luffy.

## Descoberta paralela de peers

Cada magnet continua tendo **uma unica sessao `BtClient`** por `infoHash`.
As fontes de descoberta trabalham em paralelo e sao complementares:

1. a DHT consulta peers e os encaminha ao `PeerConnectivityManager` com
   origem `DHT`;
2. o PEX (BEP 11) encaminha peers com origem `PEX`;
3. o `TrackerPeerSourceFactory` do bt-core entrega respostas UDP, HTTP e HTTPS de tracker
   ao `IPeerRegistry`; o evento nativo `PeerDiscoveredEvent` registra o mesmo
   endpoint no `PeerConnectivityManager` com origem `TRACKER`.

O identificador de deduplicacao e `infoHash + familia IP + transporte +
endereco + porta`. Se a mesma origem chegar por DHT, tracker e PEX, o Luffy
mantem um so estado de conectividade com as tres origens registradas. O
tracker ja encaminhou o endpoint ao registro interno do bt-core, portanto o
Luffy nao o promove pela segunda vez nem abre um segundo download ou uma
conexao TCP redundante.

`UdpTrackerCompatibilityIntegrationTest` confirma o caminho completo em rede
local, sem depender de tracker publico: um magnet com dois `tr=udp://...` e
parseado e reconstruido sem perder nenhum tracker; o bt-core executa o
handshake e o announce UDP (BEP 15); o tracker responde com uma lista compacta
IPv4 de peers; e o `PeerDiscoveredEvent` entrega o peer ao
`PeerConnectivityManager` com origem `TRACKER`. A porta enviada no announce e
a porta TCP configurada do cliente.

## Peer sem extensoes Luffy

`lf_identity`, `lf_route` e `lf_rendezvous` sao extensoes opcionais de BEP 10.
O Luffy so envia cada uma depois de o peer remoto anuncia-la no extension
handshake. A ausencia de qualquer uma delas apenas impede o uso das funcoes de
overlay naquele peer; ela nao rejeita a conexao nem altera handshake BitTorrent,
metadata, pieces, download, seeding ou PEX.

`StandardBitTorrentPeerCompatibilityIntegrationTest` comprova o fallback em
loopback: o lado Luffy anuncia `lf_identity`, o lado B usa somente `bt-core`
padrao (sem extensao Luffy), e `teste.txt` e transferido por TCP com hash
validado. O peer B nao entra no `ConnectedLuffyRegistry`, mas a transferencia
normal continua ate a conclusao.

## Fontes

- [Biblioteca Bt 1.10 e BEPs suportados](https://github.com/atomashpolskiy/bt)
- [BEP 10 — Extension Protocol](https://www.bittorrent.org/beps/bep_0010.html)
- [BEP 11 — Peer Exchange](https://www.bittorrent.org/beps/bep_0011.html)
- [BEP 29 — uTP](https://www.bittorrent.org/beps/bep_0029.html)
- [BEP 55 — Hole Punching](https://www.bittorrent.org/beps/bep_0055.html)
