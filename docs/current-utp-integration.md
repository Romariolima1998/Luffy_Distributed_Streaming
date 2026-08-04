# Auditoria da integração uTP atual

Data da fotografia: 30 de julho de 2026. Este documento é uma análise do
código existente; não altera o overlay Olá Luffy, a DHT ou a transferência.

## Veredito curto

O Luffy possui um transporte UDP próprio, uma ponte de bytes para o `bt-core` e
um codec/agente de mensagens `ut_holepunch`. O desenho pretende permitir que o
handshake BitTorrent e as pieces trafeguem sobre uTP.

O caminho BitTorrent por uTP agora está **comprovado em loopback** por
`UtpBitTorrentBridgeIntegrationTest`: dois `BtRuntime`s e dois `BtClient`s
transferem um torrent real, validam a piece e concluem `teste.txt` com
`OLA LUFFY`. A prova ainda não cobre duas redes reais, NAT, CGNAT ou
interoperabilidade com clientes externos.

## Componentes auditados

| Componente | Papel atual |
| --- | --- |
| `UtpTransportService` | Listener UDP e transporte confiável próprio, com SYN/STATE/DATA/FIN/RESET. Identifica cada sessão por endpoint remoto e ID de recepção; não conhece torrent nem infoHash. |
| `UtpBitTorrentBridge` | Converte a sessão uTP em um fluxo de bytes através de um par de `SocketChannel`s locais e o entrega ao `bt-core`. |
| `Bep55HolePunchModule` | Registra `ut_holepunch` como extensão BEP 10 e instala o agente de mensagens. |
| `Bep55HolePunchAgent` | Negocia capacidades, encaminha RENDEZVOUS/CONNECT/ERROR e aciona a ponte uTP; não encaminha dados de torrent. |

## Como uma sessão uTP é identificada?

`UtpTransportService` cria, na saída, um `connectionId` aleatório de 16 bits e
um número de sequência também aleatório. O SYN usa esse ID; a sessão de saída
passa a aguardar STATE sob `connectionId + 1`.

Depois de estabelecida, a sessão é localizada por `receiveConnectionId` em:

```java
Map<Integer, UtpSession> sessionsByReceiveId
```

O endereço remoto também é conferido antes de entregar o pacote à sessão
(`session.remote().equals(remote)`). O infoHash **não faz parte** da identidade
uTP: ele só aparece mais tarde, dentro do handshake BitTorrent.

### Isolamento implementado

Desde a Etapa 2, tanto as sessões ativas quanto os SYNs de saída pendentes usam
`UtpSessionKey(remoteAddress, remotePort, receiveConnectionId)`. Assim, dois
endpoints podem usar o mesmo ID de 16 bits sem dividir contexto. Um pacote DATA
ou STATE vindo de outro endpoint não encontra a sessão e não pode receber o
STATE do contexto existente.

SYN duplicado do mesmo endpoint e ID é idempotente. Colisão para o mesmo
endpoint não substitui a sessão viva. O transporte também limita sessões,
SYNs pendentes e contextos por endpoint, e remove sessões no encerramento ou na
expiração por inatividade.

## Como o listener diferencia uTP de outras mensagens UDP?

O listener em `UtpTransportService.receiveLoop()` recebe todo datagrama UDP da
porta uTP e tenta `Packet.decode(...)`. Ele o aceita como uTP quando:

- possui ao menos 20 bytes;
- o nibble de versão é `1`;
- o nibble de tipo representa SYN, STATE, DATA, FIN ou RESET;
- o campo de extensão é `0`.

Datagramas que não obedecem a esse formato são registrados como inválidos e
ignorados. Um pacote com formato válido, mas sem sessão correspondente, recebe
RESET (exceto se já for RESET).

Não existe uma assinatura criptográfica nem demultiplexação por infoHash nesse
nível. A DHT está em outra porta UDP (normalmente 49001); uTP usa a porta P2P
(normalmente 6891). Ainda assim, qualquer tráfego na porta 6891 que pareça um
pacote uTP será processado como tal.

## Como a sessão uTP chega ao BitTorrent?

`UtpBitTorrentBridge` não entrega eventos ao motor. Ele entrega um **stream de
bytes em `SocketChannel` local**:

```text
bt-core <-> SocketChannel (btSide)
              | loopback local
          SocketChannel (bridgeSide)
              | duas bombas de bytes
           UtpSession <-> UDP <-> peer remoto
```

`startPumps(...)` inicia duas threads virtuais:

1. `UtpSession.read(...)` → `bridgeSide`;
2. `bridgeSide` → `UtpSession.write(...)`.

Assim, para o `bt-core`, há um canal local de bytes bidirecional. O UDP não é
entregue diretamente à biblioteca.

## O `UtpBitTorrentBridge` entrega stream, canal, socket ou somente eventos?

Ele entrega um `SocketChannel` local marcado por `UtpConnectionMarkers`.

- **Saída:** chama por reflexão o método interno
  `createConnection(Peer, TorrentId, SocketChannel, boolean)` da factory da
  engine, passando o lado `btSide` e o `TorrentId` derivado do infoHash.
- **Entrada:** chama `IPeerConnectionFactory.createIncomingConnection(peer,
  btSide)`. Nesse caso ainda não há infoHash no uTP; a engine o descobrirá ao
  ler o handshake BitTorrent que chega pelo canal.

Logo, não é apenas telemetria. Há uma tentativa concreta de entregar um canal
capaz de transportar o protocolo inteiro.

## O `bt-core` consegue consumir a conexão uTP atual?

**Sim, em loopback validado.**

O `bt-core` consome um `SocketChannel`, e a ponte lhe fornece exatamente esse
tipo. Porém isso depende de reflexão sobre um método interno da implementação
da factory, usando `setAccessible(true)`, em vez de uma API pública de uTP.

Consequências:

- a integração é frágil a mudanças internas da versão `bt-core` 1.10;
- o método reflexivo ainda é frágil a mudanças internas da versão `bt-core`;
- a prova cobre torrent carregado por metainfo, handshake, piece, upload,
  download e armazenamento por uTP em loopback; metadata por magnet e redes
  externas continuam sem prova automatizada.

Para que a engine agenda requests após o handshake, a ponte também registra o
`ConnectionResult` no `IPeerConnectionPool`, espelhando o passo pós-handshake
de `ConnectionSource` do `bt-core`.

## Em qual ponto ocorre o handshake BitTorrent sobre uTP?

Depois de a sessão UDP alcançar STATE:

1. `UtpTransportService.connect(...)` completa a `UtpSession` de saída;
2. `UtpBitTorrentBridge.bridgeOutgoing(...)` abre o par loopback, inicia as
   bombas e entrega `btSide` à factory com o `TorrentId`;
3. a engine escreve/lê o handshake BitTorrent pelo `SocketChannel`;
4. as bombas levam esses bytes pela `UtpSession`;
5. `BtConnectionLifecycleInstrumentation` reconhece o canal marcado e chama
   `onUtpBittorrentHandshakeStart(...)` antes de delegar ao handler da engine;
6. o retorno aceito/rejeitado do handler chama
   `onUtpBittorrentHandshakeSuccess(...)` ou
   `onUtpBittorrentHandshakeFailure(...)`.

Para uma sessão de entrada, `acceptIncoming(...)` abre a mesma ponte assim que
recebe o SYN e entrega o canal à factory de entrada. O infoHash só fica conhecido
quando o handshake BitTorrent é processado pela engine; o log o obtém de
`result.getConnection().getTorrentId()`.

## Como o infoHash é associado à conexão correta?

| Direção | Associação atual |
| --- | --- |
| Saída direta/uTP | `PeerConnectivityManager` promove `(infoHash, endpoint, UTP)`; `UtpBitTorrentBridge` converte explicitamente o infoHash para `TorrentId` antes de chamar a factory. |
| Saída após BEP 55 | A mensagem CONNECT chega no contexto de uma conexão BitTorrent já pertencente ao swarm; o agente usa `context.getTorrentId()` e abre a sessão uTP com esse infoHash. |
| Entrada uTP | O SYN uTP não contém infoHash. A ponte aceita a sessão e a factory de entrada precisa ler o handshake BitTorrent para associá-la a um torrent local. |

Isso separa corretamente transporte e torrent, mas traz duas consequências:

- um SYN de entrada pode criar ponte antes de se saber se o infoHash corresponde
  a um torrent local;
- DHT e PEX descobrem inicialmente apenas `PeerEndpoint(..., TCP)`. Desde a
  Etapa 3, o `PeerConnectivityManager` **não** cria um endpoint uTP usando a
  mesma porta. uTP e BEP 55 só são tentados quando há um `PeerEndpoint(...,
  UTP)` descoberto de forma independente.

`ExternalEndpointRegistry` armazena evidência por TCP, uTP e DHT, com família
IP, origem, expiração e nível de confirmação separados. Uma porta TCP e uma
porta uTP podem coincidir, mas isso só é aceito quando cada transporte recebeu
sua própria observação. Endereço privado/local não entra como endpoint público.

## Uma conexão uTP de entrada pode ser promovida ao torrent correto?

**Sim, em loopback validado.** `acceptIncoming(...)` entrega o canal à
`createIncomingConnection(...)`, sem um `TorrentId` prévio. No teste de
integração B aceita a sessão uTP de A, o handshake associa o torrent e B envia
a piece solicitada. O listener ainda aceita a sessão no SYN antes de conhecer o
torrent; a rejeição e limpeza de infoHash desconhecido continuam sem teste.

## Uma conexão uTP de saída pode ser promovida ao torrent correto?

**Sim, em loopback validado.** Na saída, o infoHash é convertido para
`TorrentId` e passado à factory reflexiva junto com `btSide`; o teste confirma
que a conexão é registrada no torrent esperado e permanece viva até a piece ser
transferida, validada e gravada.

## O caminho atual permite transferência real de pieces?

**Sim, em loopback validado.** O teste de integração comprova que a engine
envia o request necessário, B contabiliza upload, A contabiliza download,
valida a piece e conclui o arquivo. Não há relay de conteúdo no
`Bep55HolePunchAgent`.

Isso não comprova conectividade entre redes reais: ainda é necessário testar o
mesmo caminho contra NAT/CGNAT e com endpoints UDP externos corretos.

## O que existe apenas como codec, loopback ou integração não verificada?

| Parte | Cobertura existente | O que ela não prova |
| --- | --- | --- |
| `UtpTransportService` | `UtpTransportServiceTest` transfere `OLA LUFFY`; `UtpTransportServiceSessionIsolationTest` cobre colisões, endpoint incorreto, expiração, limpeza e limites. | Não prova NAT, Internet, bridge, handshake BitTorrent nem pieces. |
| `Bep55HolePunchMessageHandler` | Testa serialização/desserialização e validações IPv4/IPv6, porta e erro. | Não prova negociação BEP 10, relay vivo, CONNECT simultâneo ou uTP entre peers. |
| `Bep55HolePunchAgent` | Há implementação de RENDEZVOUS, CONNECT e ERROR. | Não há teste de A–C–B nem prova de que C está conectado simultaneamente a B. |
| `UtpBitTorrentBridge` | `UtpBitTorrentBridgeIntegrationTest` transfere `teste.txt` entre dois motores BitTorrent por uTP em loopback. | Não prova NAT, Internet, metadata por magnet ou interoperabilidade externa. |
| Compatibilidade BEP 29 | O cabeçalho básico e a confiabilidade própria existem. | Não há teste contra cliente BitTorrent externo; extensões uTP, congestionamento e interoperabilidade não foram comprovados. |

## Pontos específicos em `Bep55HolePunchAgent`

- `Bep55HolePunchModule` registra `ut_holepunch` no protocolo de extensões da
  engine; essa é a parte de extensão BEP 10.
- A capacidade uTP é inferida de `ut_holepunch` no extension handshake. Não há
  uma confirmação independente de que o peer realmente aceita o transporte uTP.
- Para RENDEZVOUS, o agente escolhe um peer C já conectado ao mesmo infoHash.
  Só no lado de C ocorre a verificação de que ele também está conectado ao alvo.
  Se C não estiver, envia `NOT_CONNECTED`; não há tentativa automática de outro
  C nessa mesma solicitação.
- O handshake BEP 10 ainda informa somente a porta TCP do peer conectado. Se
  não existir endpoint uTP independente, o manager não inicia BEP 55. Para usar
  portas TCP/uTP diferentes com um relay, ainda falta propagar a observação uTP
  do peer até a seleção de rendezvous; hoje a tentativa falha com segurança em
  vez de reutilizar a porta TCP.
- O codec aceita IPv6, porém `UtpBitTorrentBridge.supports(...)` aceita somente
  `Inet4Address`. Logo, BEP 55/IPv6 não é um caminho uTP ativo neste estado.

## O que falta antes de afirmar que `teste.txt` pode ser transferido

1. Executar a mesma prova entre duas redes reais, registrando endpoints UDP
   externos, handshake, request de piece, bytes recebidos e arquivo concluído.
2. Criar cenários de rejeição e limpeza para infoHash desconhecido e sessão uTP
   encerrada durante handshake ou piece.
3. Substituir a dependência reflexiva por uma extensão pública da engine, ou ao
   menos fixar e testar rigorosamente a assinatura interna do `bt-core` usada
   pela ponte.
4. Executar teste de carga/abuso com muitos endpoints e pacotes inválidos para
   calibrar os limites padrão antes de expor o listener à Internet aberta.
5. Propagar endpoint UDP/uTP externo observado do peer até o
   `Bep55HolePunchAgent` e o relay, para permitir BEP 55 quando a porta UDP for
   diferente da porta TCP, sem inferir uma a partir da outra.
6. Executar teste real entre duas redes e registrar: SYN, STATE, handshake
   BitTorrent, request de piece, bytes recebidos e arquivo concluído. Só então
   testar o cenário A–C–B de BEP 55.

## Fontes auditadas

- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/ExternalEndpointRegistry.java`
- `src/main/java/dev/lufi/infrastructure/EndpointObservationService.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchMessage.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchMessageHandler.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/test/java/dev/lufi/infrastructure/UtpTransportServiceTest.java`
- `src/test/java/dev/lufi/infrastructure/UtpTransportServiceSessionIsolationTest.java`
- `src/test/java/dev/lufi/infrastructure/ExternalEndpointRegistryTest.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchMessageHandlerTest.java`

## Atualizacao da fronteira com bt-core (Etapa 4)

Desde a Etapa 4, a chamada reflexiva de saida nao permanece em
`UtpBitTorrentBridge`. Ela esta centralizada em
`BtCoreConnectionFactoryAdapter`, que registra a versao esperada `bt-core 1.10`,
localiza uma unica vez e valida a assinatura interna
`createConnection(Peer, TorrentId, SocketChannel, boolean): ConnectionResult`.

A ponte mantem apenas o que e proprio dela: abrir o par loopback, marcar o lado
do bt-core e executar as duas bombas de bytes entre esse canal e a `UtpSession`.
O adaptador recebe explicitamente esse `SocketChannel`, pois e o recurso que a
engine consome, e converte falhas reflexivas ou de promocao em
`BtCoreIntegrationException`. Em falha, fecha o canal; a ponte continua
encerrando a sessao uTP e o par loopback.

`EstablishedPeerConnectionPromoter` e a fronteira tipada usada para saida e
entrada. O teste `BtCoreConnectionFactoryAdapterTest` cobre assinatura correta,
ausente e incompativel, falhas de invocacao e limpeza. A transferencia real de
`teste.txt` por uTP continua coberta por
`UtpBitTorrentBridgeIntegrationTest`.

## Validacao de entrada, saida e limpeza (Etapa 5)

O caminho completo e executado duas vezes em testes independentes: A inicia
uTP com o `TorrentId` conhecido e baixa `teste.txt`; na outra prova, B recebe o
SYN sem infoHash no transporte, le o handshake BitTorrent pelo canal local e
associa o mesmo torrent antes de enviar a piece. As duas provas validam o
arquivo `OLA LUFFY` e o hash no bt-core.

O transporte agora envia RESET quando uma sessao e encerrada localmente e
acorda imediatamente leitores bloqueados. RESET recebido nao e ecoado. Isso
remove ambos os contextos do mapa rapidamente e evita tarefas virtuais da ponte
presas pela espera de leitura. A ponte mede pares loopback, suas duas bombas e
guards de conexao para a suite verificar a limpeza apos encerramento normal ou
falha.

`UtpTransportServiceFailureTest` verifica timeout, RESET, sessao finalizada,
peer desconectado e datagrama invalido. `UtpBitTorrentBridgeFailureIntegrationTest`
verifica infoHash inexistente pela ponte, handshake truncado/canal fechado e
torrent encerrado durante o handshake na fronteira real do bt-core.

## Validacao BEP 55 A-C-B (Etapa 6)

Esta secao atualiza as observacoes anteriores que descreviam BEP 55 como ainda
nao verificado ponta a ponta.

`Bep55HolePunchModule` registra duas partes no pipeline BEP 10 do bt-core:

1. o codec `ut_holepunch`;
2. a instancia de `Bep55HolePunchAgent` como messaging agent.

O agente e publico porque o compilador de agentes do bt-core 1.10 usa
`MethodHandles.publicLookup()` para ligar metodos `@Consumes` e `@Produces`.
Sem isso, o codec poderia existir, mas RENDEZVOUS e CONNECT nao seriam
consumidos nem produzidos pelo motor.

`Bep55HolePunchIntegrationTest` executa a topologia abaixo em loopback:

```text
A -- BitTorrent/TCP -- C -- BitTorrent/TCP -- B
                              |
                 RENDEZVOUS / CONNECT (BEP 55)
                              |
                     A -- uTP/BitTorrent -- B
```

- B semeia um torrent real com `teste.txt` e conteudo `OLA LUFFY`.
- C possui conexoes BitTorrent reais com A e B no mesmo `TorrentId`.
- A nunca abre TCP direto para B.
- A envia RENDEZVOUS a C; C so encaminha CONNECT para A e B.
- A e B iniciam uTP e a `UtpBitTorrentBridge` entrega os canais ao bt-core.
- A recebe e valida `teste.txt`; metadata, requests e pieces nao passam por C.

Para evitar anunciar dados errados, C encaminha CONNECT somente quando tem
registros UDP/uTP independentes para os dois peers. A porta TCP do extension
handshake nao e convertida em porta UDP. Se o primeiro C nao estiver conectado
ao alvo, A tenta o proximo candidato elegivel; tentativas simultaneas para o
mesmo destino sao deduplicadas.

O resultado ainda e uma prova local. NAT/CGNAT, firewalls, endpoint UDP
publico e interoperabilidade com clientes externos precisam de teste em redes
reais antes de declarar conectividade externa comprovada.
