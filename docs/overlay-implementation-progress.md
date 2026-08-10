# Progresso da implementacao do overlay Luffy

## Etapa atual

Etapa 20 - Teste com magnet publico legal - concluida.

## Registro: Etapa 20 - Magnet publico legal

### Objetivo

Validar a interoperabilidade BitTorrent publica sem depender de extensoes
Luffy. O teste usa o torrent oficial do Ubuntu 24.04.4, gera um magnet a
partir do metainfo oficial e encerra imediatamente depois da primeira piece
validada; a imagem ISO inteira nao e baixada.

### Arquivos alterados

- `build.gradle.kts`
- `src/test/java/dev/lufi/infrastructure/HttpTrackerCompatibilityIntegrationTest.java`
- `src/test/java/dev/lufi/infrastructure/StandardBitTorrentPeerCompatibilityIntegrationTest.java`
- `src/integrationTest/java/dev/lufi/infrastructure/OfficialUbuntuPublicMagnetTransferIT.java`
- `docs/protocol-compatibility.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Foi adicionada somente a extensao oficial
  `com.github.atomashpolskiy:bt-http-tracker-client:1.10`. Ela e descoberta
  por `autoLoadModules()` e habilita `http` e `https` sem cliente tracker
  criado pelo Luffy.
- A DHT de lookup continua separada e somente de descoberta. Os peers que ela
  retorna sao entregues ao mesmo `PeerConnectivityManager` e `IPeerRegistry`
  usados pela sessao BitTorrent normal.
- Nenhuma classe de `lf_route`, `lf_rendezvous`, Swarm Assist, identidade,
  BEP 55 ou politica de vizinhos foi alterada.

### Testes executados

```text
gradle --no-daemon test --tests dev.lufi.infrastructure.HttpTrackerCompatibilityIntegrationTest
LUFFY_REAL_NETWORK_TESTS=true LUFFY_LEGAL_PUBLIC_MAGNET_TESTS=true \
  gradle --no-daemon integrationTest --tests dev.lufi.infrastructure.OfficialUbuntuPublicMagnetTransferIT
```

### Resultado

O teste local de tracker HTTP foi aprovado: announce, porta TCP configurada,
resposta compacta e peer `TRACKER` chegaram ao fluxo normal.

O teste local de peer BitTorrent padrao tambem foi estabilizado: ele agora
espera ambas as runtimes registrarem o torrent e inicia a conexao TCP pelo
`IConnectionSource`, exatamente como os demais testes de transferencia. Isso
nao altera o motor nem as extensoes Luffy.

O teste publico legal aprovado registrou:

```text
MAGNET PARSED: TRACKERS=2
DHT READY: rpcServers=1
DHT NODES=0 no instante da inicializacao
DHT PEERS=64
TRACKER ANNOUNCE START: trackers=2
PEER CONNECT START: TCP
PEER CONNECTED
BITTORRENT HANDSHAKE ACCEPTED
METADATA RECEIVED
PIECE REQUEST
PIECE RECEIVED: index=0
TRACKER PEERS=0
PEX PEERS=0
```

Logo, o download real ocorreu por DHT e TCP padrao, com metadata e piece
validadas pelo bt-core. Os trackers oficiais nao devolveram peers nesta
execucao; isso ficou registrado como resultado de fonte complementar, nao foi
substituido por extensoes Luffy. Uma tentativa anterior tambem observou a
variacao normal da rede publica, com DHT e tracker retornando zero peers.

A suite normal completa foi aprovada: **257 testes**, 257 aprovados, 0
falhando e 0 ignorados.

### Proxima etapa

Continuar apenas quando houver uma nova instrucao explicita do usuario.

---

## Registro: Etapa 19 - Teste controlado com µTorrent/qBittorrent

### Objetivo

Validar entre redes reais que o Luffy baixa `teste-publico.txt` semeado por um
cliente BitTorrent padrao, usando somente magnet, DHT/tracker, handshake,
metadata e pieces do protocolo BitTorrent.

### Arquivos alterados

- `src/integrationTest/java/dev/lufi/integration/RealNetworkStandardClientCompatibilityIT.java`
- `src/integrationTest/java/dev/lufi/integration/RealNetworkPublicMagnetDiscoveryIT.java`
- `src/integrationTest/java/dev/lufi/integration/RealNetworkDiagnosticTransferIT.java`
- `docs/overlay-implementation-progress.md`

### Como executar

1. Na maquina B, crie `teste-publico.txt` com o conteudo exato `OLA LUFFY
   PUBLICO`, gere o torrent no qBittorrent/µTorrent e mantenha o seeding.
2. Copie o magnet gerado e, na maquina A com somente o Luffy, execute:

```text
$env:LUFFY_REAL_NETWORK_TESTS = "true"
$env:LUFFY_STANDARD_CLIENT_MAGNET = "magnet:?xt=urn:btih:..."
.\gradlew.bat --no-daemon integrationTest --tests dev.lufi.integration.RealNetworkStandardClientCompatibilityIT
```

### Verificacoes do teste

- magnet valido e sem extensoes Luffy obrigatorias;
- peer descoberto por DHT ou tracker;
- handshake BitTorrent padrao e metadata;
- piece verificada e conteudo final exato;
- nenhuma busca `lf_route` ou sessao `lf_rendezvous`.

### Sondagem publica complementar

O magnet publico fornecido pelo usuario possui 20 trackers UDP, mas nao e o
arquivo controlado `teste-publico.txt`; portanto ele nao pode validar o
conteudo final da Etapa 19. Foi executada uma sondagem separada em
`RealNetworkPublicMagnetDiscoveryIT`, com todos os arquivos em prioridade
`SKIP`: um tracker UDP publico devolveu uma lista compacta de peers para o
infoHash e o peer entrou no `PeerConnectivityManager` com origem `TRACKER`.
O teste concluiu em 6 segundos, sem solicitar pieces nem baixar o video.

### Resultado

O teste controlado de transferencia continua preparado, mas ainda nao pode ser
executado contra µTorrent/qBittorrent: nao ha cliente padrao instalado nesta
maquina nem magnet de uma maquina B semeando `teste-publico.txt`. A execucao
real depende desses dois dados.

### Proxima etapa

Receber o magnet gerado pelo cliente padrao e executar o teste entre as duas
maquinas.

---

## Registro: Etapa 18 - Compatibilidade com trackers UDP

### Objetivo

Comprovar que um magnet publico com `tr=udp://...` preserva trackers repetidos,
executa announce UDP, interpreta a resposta compacta de peers e entrega os
peers ao fluxo normal de conectividade do Luffy.

### Arquivos analisados

- `src/main/java/dev/lufi/domain/MagnetLink.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `build.gradle.kts`
- artefato `bt-core` 1.10 resolvido pelo Gradle

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/UdpTrackerCompatibilityIntegrationTest.java`
- `docs/protocol-compatibility.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- O teste implementa somente um tracker UDP local minimo de BEP 15. Ele nao e
  parte da aplicacao e nao introduz um cliente tracker proprio no Luffy.
- A resposta de announce contem um peer IPv4 na forma compacta de seis bytes
  (quatro do IP e dois da porta), exatamente como em trackers UDP publicos.
- O teste observa `PeerDiscoveredEvent`, o mesmo evento conectado pelo gateway
  ao `PeerConnectivityManager`, e confirma a origem `TRACKER` no estado final.

### Testes criados

- magnet com dois parametros `tr` preservados pelo parser e por `toUri()`;
- handshake/announce UDP com infoHash e porta TCP configurada;
- resposta compacta de peer IPv4;
- encaminhamento do peer para o fluxo normal com origem `TRACKER`.

### Testes executados

```text
gradle --no-daemon test --tests dev.lufi.infrastructure.UdpTrackerCompatibilityIntegrationTest
gradle --no-daemon test
```

### Resultado

Teste de interoperabilidade UDP aprovado. O Luffy usa o suporte UDP tracker
ja existente do bt-core, preserva multiplos `tr` e recebe peers de tracker no
mesmo fluxo usado por DHT e PEX. A suite completa tambem foi aprovada: **256
testes**, 256 aprovados, 0 falhando e 0 ignorados.

### Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Etapa 17 - Descoberta paralela

### Objetivo

Unificar DHT, tracker e PEX como fontes complementares de peers de um magnet,
sem abrir tres downloads nem promover duas vezes o endpoint que o bt-core ja
recebeu de um tracker.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/domain/MagnetLink.java`
- `src/test/java/dev/lufi/infrastructure/PeerConnectivityManagerTest.java`
- artefato `bt-core` 1.10 resolvido pelo Gradle

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/test/java/dev/lufi/infrastructure/PeerConnectivityManagerTest.java`
- `docs/protocol-compatibility.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- O gateway passa a observar `PeerDiscoveredEvent` do bt-core para cada
  torrent ativo. Um endpoint ainda desconhecido nessa fonte e marcado como
  `TRACKER` no `PeerConnectivityManager`.
- DHT e PEX ja eram encaminhados explicitamente ao mesmo gerenciador. O estado
  e deduplicado por infoHash, familia, transporte, IP e porta, preservando as
  origens que efetivamente foram observadas.
- O evento do tracker nao e promovido de novo: o `TrackerPeerSourceFactory` do
  bt-core ja o inseriu no `IPeerRegistry`. Assim ha uma unica sessao do torrent
  e uma unica tentativa TCP nativa para esse anuncio de tracker.

### Testes criados

- tracker, DHT e PEX no mesmo endpoint mantem um unico estado com tres
  origens;
- uma observacao de tracker isolada nao cria promocao adicional;
- uma descoberta DHT posterior do mesmo endpoint ainda gera somente uma
  promocao gerenciada.

### Testes executados

```text
gradle --no-daemon test --tests dev.lufi.infrastructure.PeerConnectivityManagerTest
gradle --no-daemon test
```

### Resultado

Suite focada aprovada e suite completa aprovada: **255 testes**, 255 aprovados,
0 falhando e 0 ignorados. A descoberta por tracker e agora visivel nos logs como
`TRACKER PEER DISCOVERED` e chega ao mesmo registro usado por DHT e PEX, sem
criar uma sessao BitTorrent separada.

### Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Etapa 16 - Auditoria dos trackers do magnet

### Objetivo

Verificar que todos os parametros repetidos `tr=udp://...` sobrevivem ao parse
e chegam ao suporte de trackers existente no bt-core, sem criar cliente tracker
proprietario.

### Arquivos analisados

- `src/main/java/dev/lufi/domain/MagnetLink.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/domain/MagnetLinkTest.java`
- artefato `bt-core` 1.10 resolvido pelo Gradle

### Arquivos alterados

- `docs/protocol-compatibility.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `MagnetLink.parse(...)` mantem uma lista ordenada de todos os `tr`, enquanto
  o mapa de parametros continua apenas como compatibilidade retroativa.
- `MagnetLink.toUri()` reconstrui todos os trackers e o gateway entrega essa
  string sem transformacao a `Bt.client(...).magnet(...)` em download,
  diagnostico e Swarm Assist.
- O bt-core 1.10 ja possui parser de magnet, `TrackerService`, fonte de peers
  por tracker e UDP tracker. Nenhuma implementacao paralela foi adicionada.

### Testes executados

```text
gradle test --tests dev.lufi.domain.MagnetLinkTest --rerun-tasks
```

### Resultado

Etapa aprovada sem alteracao de comportamento: o teste confirma que tres
trackers UDP repetidos sao preservados integralmente pelo parse e pela
reconstrucao. A sondagem publica anterior recebeu 19 trackers, concluiu
handshakes e metadata sem solicitar pieces.

### Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Etapa 15 - Nao rejeitar peer nao-Luffy

### Objetivo

Manter `lf_identity`, `lf_route` e `lf_rendezvous` estritamente opcionais, para
que torrents publicos possam continuar com handshake BitTorrent, metadata,
pieces, download, seeding e PEX diante de um cliente que nao conheca o Luffy.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/identity/LuffyIdentityExtensionTest.java`

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/StandardBitTorrentPeerCompatibilityIntegrationTest.java`
- `docs/protocol-compatibility.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Nenhuma extensao Luffy foi promovida a requisito de BitTorrent.
- Quando `lf_identity` nao esta no extension handshake remoto,
  `LuffyIdentityExtension` remove apenas o estado de identidade/overlay da
  conexao e nao produz mensagem propria para aquele peer.
- BEP 55 indisponivel tambem nao interrompe TCP: ele fica como capacidade
  opcional e o protocolo BitTorrent normal segue pelo bt-core.

### Testes criados

- `StandardBitTorrentPeerCompatibilityIntegrationTest` inicia A como Luffy e
  B como `bt-core` puro, sem `lf_identity`, `lf_route` ou `lf_rendezvous`.
  A recebe `teste.txt` por TCP, valida o conteudo e confirma que B nao foi
  registrado como peer Luffy.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.StandardBitTorrentPeerCompatibilityIntegrationTest \
  --tests dev.lufi.infrastructure.identity.LuffyIdentityExtensionTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 254 |
| Aprovados | 254 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. Um peer BitTorrent padrao sem extensoes Luffy transferiu
`teste.txt` por TCP para o Luffy. O teste publico passivo anterior tambem
confirmou handshake, PEX e metadata com peers externos que nao anunciaram
`lf_identity`; arquivos em `SKIP` impediram o recebimento de pieces nessa
sondagem externa.

### Problemas encontrados

Nenhum. A interoperabilidade por TCP e comprovada; a interoperabilidade uTP e
BEP 55 com programas externos continua uma validacao separada e nao e exigida
para download BitTorrent padrao.

### Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Testes de lifecycle e regressao da DHT de lookup

### Objetivo

Comprovar que consultas concorrentes compartilham um unico startup, que nenhuma
consulta atravessa a barreira antes de READY, que falha e encerramento impedem
o lookup e que a DHT inicializada nunca chega a `getPeers(...)` sem
`RPCServerManager`.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycle.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Os testes exercitam a mesma barreira `DhtLookupRuntimeLifecycle` usada por
  `BtTorrentGateway.requestDhtLookup(...)`; nao foi introduzida uma segunda
  implementacao ou um atalho para os testes.
- O teste de regressao inicia o `MldhtService` real, valida o RPC server em
  `RUNNING` imediatamente antes de `DHTService.getPeers(...)` e nao depende
  de encontrar peers externos.
- A sondagem de bootstrap usou o gateway real, o listener UDP DHT normal e um
  infoHash sem conteudo, portanto nao criou download nem solicitou pieces.

### Testes criados

- `tenConcurrentLookupRequestsUseOneStartupAndDoNotRunLookupWorkBeforeReady`:
  dez consultas concorrentes resultam em uma runtime, um startup e dez
  consultas liberadas somente depois de READY.
- `failedStartupNeverRunsLookupWorkAndRecoversAfterBackoff`: startup falho
  bloqueia o lookup, respeita backoff e permite nova runtime READY.
- `regression_lookupNeverCallsMldhtWithANullServerManager`: prova que a DHT
  real possui RPC server ativo no momento em que `getPeers(...)` e iniciado.
- O teste de shutdown passou a afirmar que uma consulta durante `STOPPING` e
  rejeitada antes de iniciar lookup.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --tests dev.lufi.infrastructure.Bep55HolePunchIntegrationTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 253 |
| Aprovados | 253 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

A execucao real do gateway registrou, nesta ordem, `LOOKUP RUNTIME STARTING`,
`RPC SERVER STARTED`, `LOOKUP RUNTIME READY`, `bootstrap started`,
`nodes known=0` e `LOOKUP START`. O bootstrap elevou a tabela para 21 nos;
nenhum `SocketTimeoutException`, `nodes known=-1` ou erro de
`getServerManager() == null` ocorreu. O infoHash de controle retornou zero
peers, como esperado, e nenhum arquivo foi baixado.

### Problemas encontrados

Na primeira execucao completa, `Bep55HolePunchIntegrationTest` expirou uma vez
esperando o pareamento local A--C. Ele passou imediatamente quando executado
isoladamente e a repeticao integral da suite terminou com 253/253. O teste e
anterior e nao foi alterado nesta etapa; a ocorrencia foi registrada como
instabilidade de timing da integracao BEP 55, separada do lifecycle DHT.

### Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Logs de lifecycle da runtime DHT de lookup

### Objetivo

Expor o startup e cada consulta DHT com estados verificaveis, sem aceitar
`nodes known=-1` como indicacao normal de uma runtime pronta.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- A prontidao retorna um estado tipado com quantidade de nos nao negativa e
  pelo menos um RPC server em execucao.
- O gateway registra, em ordem, `STARTING`, `RPC SERVER STARTED`, `READY`,
  bootstrap, contador de nos e o inicio da consulta.
- Cada peer retornado passa a ser registrado como `PEER DISCOVERED`; o
  contador final tambem usa o estado real da runtime, nunca um valor sentinela.

### Testes criados

- A prova de inicializacao DHT passou a verificar explicitamente que uma
  runtime READY possui RPC server ativo e `knownNodes >= 0`.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 252 |
| Aprovados | 252 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

A sondagem real do infoHash publico `2289f362ea3685afb56f851f8807bfcc465c8066`
iniciou a runtime, registrou toda a sequencia esperada, retornou 32 peers e
terminou com `nodes known=20`; nenhum log trouxe `nodes known=-1`.

## Problemas encontrados

Nenhum bloqueio estrutural. Uma tabela DHT recem-inicializada pode iniciar em
zero nos; isso e valido e distinto de uma runtime sem RPC server.

## Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Separacao entre lookup DHT e announce local

### Objetivo

Preservar a runtime de lookup como descoberta-only mesmo depois do seu startup
explicito, mantendo announce de torrents locais somente na runtime de
transferencia.

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Nenhum modulo de announce foi adicionado a runtime de lookup.
- A garantia usa o `TorrentRegistry` real do bt-core: sem `BtClient` ou torrent
  ativo, `getPeers(...)` nao possui base para anunciar o infoHash.
- A runtime de transferencia continua sendo a unica responsavel por seed,
  download e announce condicionado a conectividade confirmada.

### Testes criados

- A prova de startup DHT agora verifica que o registro de torrents esta vazio
  antes e depois da consulta `getPeers(...)`.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 252 |
| Aprovados | 252 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. A runtime de lookup inicia e executa `getPeers(...)` sem
registrar torrents, portanto nao anuncia infoHashes locais; announces continuam
exclusivos da runtime de transferencia.

## Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Compartilhamento da runtime DHT de lookup IPv4

## Registro: Compartilhamento da runtime DHT de lookup IPv4

### Objetivo

Garantir que as finalidades de descoberta IPv4 compartilhem uma unica runtime
DHT saudavel, sem criar uma runtime por magnet ou infoHash.

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Nenhuma nova runtime foi introduzida: o `BtTorrentGateway` ja possui uma
  unica lifecycle IPv4 e todos os caminhos chamam `requestDhtLookup(...)`.
- Cada lookup conserva seu infoHash e sua conclusao assincrona, enquanto a
  lifecycle compartilha o startup, o listener UDP e a tabela Kademlia.
- A separacao IPv4/IPv6 foi mantida, pois sao redes DHT distintas.

### Testes criados

- `magnetBootstrapAnnounceAndSwarmAssistShareOneReadyIpv4Runtime` inicia
  magnet, Ola Luffy, verificacao de announce, Swarm Assist e buscas adicionais
  em concorrencia, comprovando uma unica criacao e a mesma runtime pronta.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 252 |
| Aprovados | 252 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. Magnet, Ola Luffy, verificacao de announce, Swarm Assist e
outras buscas DHT IPv4 compartilham uma unica runtime em `READY`; nenhuma
runtime e criada por infoHash.

## Proxima etapa

Aguardar a proxima instrucao explicita do usuario.

---

## Registro: Correcao da descoberta BitTorrent publica

## Registro: Correcao da descoberta BitTorrent publica

### Objetivo

Restaurar a descoberta de peers para magnets publicos sem alterar o motor
BitTorrent, a DHT, o protocolo de transferencia ou as extensoes do Luffy.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtBootstrapNodes.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/domain/MagnetLink.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistManager.java`
- `src/test/java/dev/lufi/infrastructure/DhtBootstrapNodesTest.java`
- `src/test/java/dev/lufi/domain/MagnetLinkTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Os tres bootstrap nodes existentes no `bt-dht` foram preservados e um quarto
  fallback IPv4 foi adicionado ao `DHTConfig` da mesma runtime.
- O bootstrap adicional participa apenas da descoberta Kademlia; ele nao
  recebe video, pieces, metadata nem mensagens do overlay Luffy.
- `MagnetLink` passou a conservar todos os parametros `tr` repetidos e os
  repassa ao bt-core quando o magnet e reconstruido.
- Swarm Assist usa essa mesma reconstrucao e continua com todos os arquivos em
  `SKIP`, sem solicitar pieces do conteudo.

### Testes criados

- `DhtBootstrapNodesTest` valida o fallback IPv4 e a separacao do caminho IPv6.
- `MagnetLinkTest.preservesEveryRepeatedTrackerWhenTheMagnetIsRebuilt` valida
  que todos os trackers repetidos sobrevivem ao parse e a reconstrucao.

### Testes executados

```text
gradle test --tests dev.lufi.domain.MagnetLinkTest \
  --tests dev.lufi.infrastructure.DhtBootstrapNodesTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 252 |
| Aprovados | 252 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Uma sondagem DHT IPv4 do magnet publico retornou 32 peers e preencheu a tabela
com 115 nos. Em seguida, uma sessao passiva do motor BitTorrent preservou os
19 trackers recebidos, concluiu handshakes e recebeu metadata, sem verificar
nem baixar pieces.

## Proxima etapa

Validar a mesma descoberta pela interface do Luffy e, se necessario, registrar
falhas de tracker separadamente das falhas da DHT.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 7

### Objetivo

Encerrar a runtime DHT em ordem, cancelar consultas pendentes e impedir novos
lookups assim que o fechamento do Luffy comecar.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycle.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `close()` marca o gateway como encerrando antes de cancelar consultas e
  iniciar o shutdown das runtimes DHT.
- As consultas pendentes recebem cancelamento, suas threads sao interrompidas e
  o `getPeers(...)` nao e iniciado depois desse marco.
- `DhtLookupRuntimeLifecycle` muda para `STOPPING`, chama `runtime.shutdown()`
  e so entao publica `STOPPED`.
- Se o startup estava pendente, a barreira `dhtReady` e cancelada de imediato,
  mas o shutdown ainda aguarda a limpeza da runtime criada.

### Testes criados

- `stoppingCancelsTheReadinessBarrierAndWaitsForRuntimeShutdown` prova a ordem
  de cancelamento, espera pelo shutdown e bloqueio de novo startup.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 249 |
| Aprovados | 249 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. O fechamento cancela consultas DHT pendentes, nao
aceita novos lookups e publica `STOPPED` somente depois de `runtime.shutdown()`.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 6

### Objetivo

Encerrar runtimes DHT parcialmente iniciadas, descartar referencias corrompidas
e permitir nova tentativa somente apos um backoff configuravel.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeSettings.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycle.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeSettingsTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- A transicao para `FAILED` encerra a runtime criada, limpa a referencia e
  conclui a barreira com erro.
- `dhtRetryBackoff` e configuravel; o padrao e 5 segundos.
- Durante backoff, novas consultas recebem erro claro e nao criam sockets,
  runtimes ou startups adicionais.
- Depois do prazo, uma unica consulta pode criar uma nova runtime; ela nunca
  reutiliza a instancia que falhou.

### Testes criados

- A prova `failedStartupClosesTheRuntimeAndAppliesBackoffBeforeRecreatingIt`
  verifica fechamento, limpeza, bloqueio durante o prazo e nova criacao depois
  do backoff.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeSettingsTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 248 |
| Aprovados | 248 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. Falhas encerram e descartam a runtime DHT; novas
tentativas respeitam o backoff configuravel antes de criar uma instancia nova.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 5

### Objetivo

Limitar a espera de startup da runtime DHT de lookup e registrar uma falha
clara quando o servidor RPC nao ficar pronto.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeSettings.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeSettingsTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `dhtStartupTimeout` e configuravel, com valor padrao de 15 segundos.
- O timeout e aplicado apenas ao `Config` da runtime de lookup. O lifecycle do
  bt-core usa esse limite para aguardar o binding assincrono do `MldhtService`.
- Sem `RPCServerManager` e `RPCServer` utilizavel ao fim do startup, o
  lifecycle falha e a barreira de prontidao e completada excepcionalmente.
- O log usa a causa clara `RPC server did not become ready`; nenhum `getPeers`
  recebe a runtime que falhou.

### Testes criados

- `DhtLookupRuntimeSettingsTest`: valor padrao de 15 segundos e rejeicao de
  valores zero ou negativos.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeSettingsTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 247 |
| Aprovados | 247 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. A runtime DHT de lookup possui timeout configuravel
de 15 segundos e nao libera `getPeers(...)` caso o servidor RPC nao fique
pronto.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 4

### Objetivo

Exigir evidencia concreta de que o mldht iniciou antes de marcar a runtime DHT
de lookup como pronta, sem usar espera por tempo ou polling.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `BtRuntime.startup()` e a barreira do lifecycle do bt-core: ele aguarda o
  binding assincrono do `MldhtService` terminar.
- Depois disso, o inicializador exige `DHT.getServerManager() != null` e um
  `RPCServer` no estado `RUNNING` retornado pelo `RPCServerManager`.
- Ausencia do manager ou de um servidor utilizavel e falha de inicializacao;
  a runtime nao chega a `READY`.
- Nenhum `Thread.sleep(...)`, polling ou palpite temporal participa do criterio.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 245 |
| Aprovados | 245 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. A prontidao depende exclusivamente do lifecycle
concluido do bt-core e de estado verificavel do `RPCServerManager`/`RPCServer`.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 3

### Objetivo

Criar uma barreira de prontidao real para impedir qualquer `getPeers(...)`
antes de a inicializacao assincrona do `MldhtService` concluir.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycle.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- O lifecycle possui uma barreira `CompletableFuture<Void>` por familia DHT.
- A thread de startup e a unica que conclui a barreira, somente depois de
  `DhtLookupRuntimeInitializer` observar o `RPCServerManager` e listener UDP
  em execucao.
- Todas as consultas chamam `awaitDhtReady(...)` antes de obter a runtime e
  executar `DHTService.getPeers(...)`.
- Falha e encerramento completam a barreira excepcionalmente; nenhuma consulta
  recebe uma runtime parcialmente inicializada.

### Testes criados

- A prova `readinessBarrierCompletesOnlyAfterTheDhtStartupFinishes` confirma
  que a barreira fica pendente em `STARTING` e so completa em `READY`.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 245 |
| Aprovados | 245 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. Nenhuma consulta DHT atinge `getPeers(...)` antes
de a barreira compartilhada `dhtReady` ser concluida com a runtime em `READY`.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 2

### Objetivo

Evitar startups concorrentes na runtime DHT exclusiva de lookup e tornar seu
ciclo de vida observavel e seguro para consultas simultaneas.

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/DhtLookupState.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycle.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeLifecycleTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Cada familia IP possui uma maquina de estados independente: `NEW`,
  `STARTING`, `READY`, `FAILED`, `STOPPING` e `STOPPED`.
- O primeiro chamador cria, guarda e inicializa a runtime. Chamadores que
  chegam em `STARTING` aguardam a mesma inicializacao, sem executar outro
  `startup()`.
- Uma falha deixa o estado `FAILED` visivel aos chamadores que aguardavam a
  tentativa; uma consulta posterior pode criar uma runtime nova.
- O encerramento muda para `STOPPING`, aguarda startup pendente se necessario
  e termina em `STOPPED`, sem permitir reinicio posterior.

### Testes criados

- `DhtLookupRuntimeLifecycleTest`: concorrencia, compartilhamento de instancia,
  transicao para falha com nova tentativa e bloqueio de restart apos stop.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.DhtLookupRuntimeLifecycleTest \
  --tests dev.lufi.infrastructure.DhtLookupRuntimeInitializerTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 244 |
| Aprovados | 244 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada localmente. Consultas DHT simultaneas compartilham uma unica
inicializacao e so recebem a runtime depois de ela alcancar `READY`.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro: Correcao da runtime DHT de lookup - Etapa 1

### Objetivo

Garantir que a runtime exclusiva usada para descoberta DHT IPv4 seja iniciada
antes da primeira chamada a `DHTService.getPeers(...)`, sem criar uma runtime
por consulta e sem alterar o protocolo BitTorrent normal.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/LuffyDhtDiscoveryModule.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `bt-dht` 1.10 (`MldhtService`, `DHT` e `RPCServerManager`)

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializer.java`
- `src/test/java/dev/lufi/infrastructure/DhtLookupRuntimeInitializerTest.java`
- `docs/dht-lookup-runtime.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- A runtime e guardada antes do startup e permanece compartilhada por todas as
  consultas IPv4 enquanto saudavel.
- `DhtLookupRuntimeInitializer` chama `runtime.startup()` e espera a criacao
  do `RPCServerManager` e um servidor UDP em estado `RUNNING`.
- Em falha de inicializacao a referencia compartilhada e removida e a runtime
  e encerrada; uma consulta posterior pode criar uma nova runtime saudavel.
- Nao foi usado um `catch` para esconder a excecao do `getPeers`. A origem da
  excecao foi removida inicializando de fato o `MldhtService`.
- A disponibilidade externa continua separada: listener local ativo nao prova
  que bootstrap UDP, roteador ou firewall permitam trafego pela Internet.

### Testes criados

- `DhtLookupRuntimeInitializerTest` inicia uma runtime DHT IPv4 real em porta
  UDP temporaria, verifica o startup e confirma que o primeiro lookup nao
  falha por `DHT.getServerManager()` nulo.

### Testes executados

```text
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 241 |
| Aprovados | 241 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Correcao aprovada localmente. A runtime de lookup DHT e iniciada antes de
`getPeers(...)`, e a suite completa nao encontrou regressao no motor
BitTorrent, PEX, uTP, BEP 55 ou overlay existente.

### Problemas encontrados

- `DHTService` nao oferece uma API publica de prontidao. O acesso ao `DHT`
  interno ficou concentrado em uma unica classe adaptadora, com validacao de
  compatibilidade para `bt-dht` 1.10.

## Proxima etapa

Aguardar a instrucao explicita do usuario depois da validacao desta correcao.

---

## Registro da Etapa 1

### Objetivo

Registrar uma nova linha de base do motor atual e comprovar, sem DHT, PEX,
overlay ou TCP entre os peers de conteudo, que o caminho
`UtpTransportService -> UtpBitTorrentBridge -> bt-core` transfere um torrent
real de `teste.txt` entre duas runtimes BitTorrent.

### Arquivos analisados

- `build.gradle.kts`
- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/test/java/dev/lufi/infrastructure/UtpBitTorrentBridgeIntegrationTest.java`

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/UtpBitTorrentBridgeIntegrationTest.java`
- `docs/overlay-implementation-progress.md`

Nenhuma classe de producao, DHT, PEX, torrent, handshake, storage, downloader
ou seeder foi alterada nesta etapa.

### Decisoes tecnicas

- A linha de base executou toda a suite sem reutilizar resultados anteriores:
  240 testes aprovados, sem falhas nem ignorados.
- A prova existente `UtpBitTorrentBridgeIntegrationTest` ja cria duas
  `BtRuntime`s, duas `BtClient`s, dois transportes uTP e duas pontes. Ela foi
  fortalecida em vez de criar outro transporte ou outro downloader de teste.
- B semeia `teste.txt` com conteudo exato `OLA LUFFY`; A carrega o mesmo
  metainfo e so abre o caminho uTP para B. Nenhum peer TCP, DHT, PEX ou relay
  participa da transferencia.
- O teste agora confirma que cada ponte abriu seu par de `SocketChannel`s locais
  e as duas bombas de bytes, alem de SYN de saida, SYN de entrada, entrega ao
  bt-core, handshake aceito, conexao associada ao `TorrentId`, bytes baixados,
  bytes enviados, piece verificada e limpeza dos recursos.
- O bt-core emite `onPieceVerified` somente apos validar a hash declarada pelo
  metainfo. O teste tambem compara o SHA-1 final de `teste.txt` recebido com o
  arquivo que originou a piece.

### Testes criados

- A prova de integracao real ja existia no repositorio e foi fortalecida com
  verificacao explicita dos canais/bombas da ponte e do hash final do arquivo.
- Os dois cenarios nela exercitam a saida uTP de A e a entrada uTP de B, onde B
  conhece o `TorrentId` apenas depois de ler o handshake BitTorrent.

### Testes executados

```text
gradle test --rerun-tasks
gradle test --tests dev.lufi.infrastructure.UtpBitTorrentBridgeIntegrationTest --rerun-tasks
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 240 |
| Aprovados | 240 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa 0 e Etapa 1 aprovadas localmente. A inicia uTP, B aceita a sessao, as
pontes entregam canais ao bt-core, o handshake associa o torrent correto, A
solicita a piece, B a envia, A valida a piece e grava `teste.txt` com o conteudo
`OLA LUFFY` e hash final correspondente. As pontes, sessoes uTP, canais e
tarefas virtuais sao encerrados no fim da prova.

### Problemas encontrados

- A primeira versao reforcada do teste nao compilou porque lambdas capturavam
  referencias de ponte nao efetivamente finais. A correcao foi limitada ao
  proprio teste: referencias estaveis foram criadas apos o `attach(...)`.
- Esta prova usa loopback e, portanto, nao comprova Internet, NAT, CGNAT,
  endpoint UDP externo ou hole punching entre redes reais. Esses pontos ficam
  para uma etapa posterior explicitamente solicitada.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 25

### Objetivo

Padronizar os eventos observaveis do overlay e manter contadores locais de
diagnostico para rota, rendezvous, uTP, ponte BitTorrent e Swarm Assist, sem
transmitir dados de diagnostico pela rede nem expor dados privados.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/P2pDiagnostics.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistManager.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/P2pDiagnostics.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/bootstrap/BootstrapSwarmManager.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistManager.java`
- `src/test/java/dev/lufi/infrastructure/P2pDiagnosticsEventTest.java`
- `src/test/java/dev/lufi/infrastructure/overlay/FindNodeServiceTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtensionTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `P2pDiagnostics.event(...)` preserva o log humano existente e acrescenta
  linhas estruturadas com as categorias `LF-IDENTITY`, `LF-OVERLAY`,
  `LF-ROUTE`, `LF-RENDEZVOUS`, `LF-UTP`, `LF-BEP55`, `LF-BT-BRIDGE` e
  `LF-SWARM-ASSIST`.
- Cada contador e estritamente local ao processo. `metricsSnapshot()` e
  `BtTorrentGateway.diagnosticMetrics()` apenas expoem um retrato local e nao
  enviam metricas a DHT, PEX, peers ou extensoes BEP 10.
- Os valores de evento sao saneados, limitados a 128 caracteres e os nomes de
  eventos/campos sao validados. IDs sao abreviados nos logs estruturados; nomes
  de arquivos, caminhos, magnets, biblioteca e listas de peers nao entram
  nesses eventos.
- `PUNCH_START` registra endpoints uTP somente quando a coordenacao ja usa a
  evidencia externa aprovada pela politica de privacidade. O ambiente real
  continua rejeitando endpoints privados; loopback e permitido apenas pelos
  testes de integracao.
- Os marcos solicitados sao emitidos nos seus pontos reais: `FIND_NODE_START`,
  `NODE_FOUND`, `RENDEZVOUS_START`, `PUNCH_START`,
  `BITTORRENT_CONNECTED` e `PIECE_TRANSFER_CONFIRMED`.

### Testes criados

- formato, validacao, saneamento, limpeza e contagem local de eventos
  estruturados em `P2pDiagnosticsEventTest`;
- verificacao de `FIND_NODE_START` e `NODE_FOUND` no servico de rota;
- verificacao de `RENDEZVOUS_START`, `PUNCH_START` e
  `BITTORRENT_CONNECTED` no fluxo de rendezvous existente.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.P2pDiagnosticsEventTest \
  --tests dev.lufi.infrastructure.overlay.FindNodeServiceTest \
  --tests dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtensionTest \
  --tests dev.lufi.infrastructure.UtpTransportServiceTest \
  --tests dev.lufi.infrastructure.SwarmAssistManagerTest
gradle test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 240 |
| Aprovados | 240 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Os eventos do overlay agora podem ser filtrados de modo consistente no
terminal de logs, e as metricas locais permitem diagnosticar quantas vezes
cada marco ocorreu sem afetar o trafego BitTorrent. A camada continua
separada de DHT/PEX, transporte e dados de conteudo.

### Problemas encontrados

- Nenhum problema estrutural identificado nesta etapa.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 24

### Objetivo

Garantir que o overlay Luffy revele apenas os dados minimos necessarios para
localizar um NodeId e coordenar rendezvous, sem compartilhar biblioteca,
historico, nomes de arquivos, lista completa de torrents, topologia ou
enderecos privados locais.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteMessage.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteCodec.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityMessage.java`
- `src/main/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistry.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousEndpointSelector.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/rendezvous/OverlayPrivacyPolicy.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/OverlayPrivacyPolicyTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtensionTest.java`
- `src/test/java/dev/lufi/infrastructure/overlay/LuffyRouteCodecTest.java`
- `src/test/java/dev/lufi/infrastructure/OverlayLocalIntegrationTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `lf_identity` identifica somente uma instalacao por `LuffyNodeId`; ele nao
  transporta conta, usuario, biblioteca, historico, nome de arquivo ou IP.
- `FIND_NODE` usa apenas o NodeId alvo e o infoHash de contexto exigido pela
  coordenacao posterior. Nomes de torrent e links magnet nao fazem parte da
  extensao. `NODE_FOUND`, `NODE_NOT_FOUND` e `ROUTE_ERROR` nao carregam o
  infoHash: a resposta comunica somente se o alvo foi localizado e, quando
  necessario, o NodeId/capacidades minimas do coordenador.
- O registro global de conexoes continua exclusivamente local: nenhuma API de
  rede expoe a lista completa de peers, torrents ou a topologia de vizinhos.
- O gateway ja selecionava somente evidencia uTP externa, publica, confirmada
  e vigente para iniciar um rendezvous. A nova politica tambem rejeita qualquer
  endpoint privado/local recebido ou encaminhado por `lf_rendezvous`, evitando
  que mensagens de controle sejam usadas para divulgar ou alcancar uma rede
  privada.
- O unico modo que permite loopback privado e explicitamente marcado como teste
  de integracao. Ele nao e instanciado pelo `BtTorrentGateway` da aplicacao.
- Mensagens de coordenacao continuam contendo apenas IDs aleatorios, um
  `TorrentId` (hash sem nome), flags de capacidade e um endpoint externo quando
  esse endpoint for indispensavel para o hole punching. Nenhuma piece, metadata
  ou byte de video passa pelo overlay.

### Testes criados

- rejeicao de IPv4 privado, loopback e IPv6 ULA pela politica estrita;
- permissao de loopback somente na politica de teste;
- rejeicao de uma requisicao de rendezvous privada antes de qualquer relay;
- resposta `NODE_FOUND` com tamanho e campos de coordenacao, sem identificador
  de conteudo.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.rendezvous.OverlayPrivacyPolicyTest \
  --tests dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtensionTest \
  --tests dev.lufi.infrastructure.overlay.LuffyRouteCodecTest \
  --tests dev.lufi.infrastructure.OverlayLocalIntegrationTest
gradle test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 238 |
| Aprovados | 238 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

O overlay agora reforca tecnicamente a divulgacao minima: apenas o peer que
precisa coordenar o hole punching pode receber um endpoint externo valido, e
enderecos privados nao sao enviados nem retransmitidos. O comportamento
BitTorrent de conteudo e a biblioteca local permanecem isolados dessa camada.

### Problemas encontrados

- O teste de integracao local usa `127.0.0.1` por definicao. Para manter a
  cobertura do transporte sem enfraquecer a aplicacao, ele passou a declarar
  uma politica permissiva exclusiva para loopback; a politica de producao e
  estrita por padrao.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 23

### Objetivo

Aplicar limites configuraveis e penalizacoes exclusivamente temporarias na
camada de controle Luffy, sem alterar DHT, PEX, handshake BitTorrent,
metadata, pieces, streaming ou seeding.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/security/AbuseProtectionConfig.java`
- `src/main/java/dev/lufi/infrastructure/security/AbuseProtectionService.java`
- `src/main/java/dev/lufi/infrastructure/AbuseProtectionSettings.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteExtension.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteMessageHandler.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousMessageHandler.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- testes de seguranca, uTP, identidade e rendezvous correspondentes.

### Decisoes tecnicas

- `AbuseProtectionConfig` centraliza os nove limites solicitados. Eles podem
  ser persistidos pela configuracao local ou sobrescritos por propriedades
  `-Dluffy.security.*`.
- `AbuseProtectionService` mantem contadores separados por minuto para
  `FIND_NODE`, encaminhamento e pedidos de rendezvous, alem de orcamentos para
  buscas de rota e sessoes de rendezvous simultaneas.
- Flood, mudanca de identidade e TTL abusivo bloqueiam a origem por cinco
  minutos. Payload, endpoint e sessao invalidos exigem repeticao na janela de
  um minuto antes do bloqueio. Nao existe banimento permanente.
- `lf_route` limita payload, TTL, requisicoes recebidas e encaminhamentos;
  `lf_rendezvous` limita payload, origem e sessoes; `lf_identity` penaliza a
  troca de NodeId na mesma conexao.
- O uTP valida a origem e limita SYNs/pendencias globalmente e por endereco IP.
  SYN duplicado para uma sessao viva continua idempotente; uma unica mensagem
  UDP invalida nao bloqueia um peer legitimo.
- Versao, NodeId, UUID, infoHash, timestamp, endpoint, porta, transporte,
  capacidades e transicoes de sessao permanecem validados pelos codecs e
  modelos existentes. A nova camada agrega limite, expiracao e penalizacao em
  vez de duplicar o protocolo.

### Testes criados

- configuracao de todos os nove limites;
- janela de flood, expiracao de bloqueio e contadores independentes;
- limite e liberacao de buscas de rota e sessoes de rendezvous;
- bloqueio por mudanca de identidade;
- limite de rendezvous simultaneos;
- limite de SYN uTP por endereco sem remover a primeira sessao valida.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.UtpTransportServiceFailureTest \
  --tests dev.lufi.infrastructure.identity.LuffyIdentityExtensionTest \
  --tests dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtensionTest \
  --tests dev.lufi.infrastructure.security.AbuseProtectionServiceTest \
  --tests dev.lufi.infrastructure.AbuseProtectionSettingsTest
gradle test --tests dev.lufi.infrastructure.Bep55HolePunchIntegrationTest --rerun-tasks
gradle test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 234 |
| Aprovados | 234 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

A camada de controle agora possui protecao temporaria contra abuso e limites
locais sem criar socket, runtime, relay ou protocolo de dados alternativo.
Sessoes BitTorrent existentes continuam fora dessa camada e a transferencia de
conteudo permanece exclusivamente entre peers de conteudo.

### Problemas encontrados

- Uma primeira versao bloqueava a origem apos um unico datagrama UDP invalido,
  o que quebrava a tolerancia a ruido de rede. A penalizacao foi refinada para
  exigir reincidencia nesse tipo de violacao; flood continua bloqueado de
  imediato.
- Uma execucao completa teve uma corrida no teste ja existente de integracao
  uTP/BEP55, apos o handshake e durante o fechamento de conexao duplicada. O
  teste passou quando repetido isoladamente e tambem na execucao completa
  seguinte; a Etapa 23 nao alterou a ponte uTP ou o handshake BitTorrent.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 22

### Objetivo

Controlar novas conexoes BitTorrent de forma global e por finalidade, sem
alterar DHT, PEX, uTP, BEP 55, handshake, metadata ou transferencia de pecas.
A atividade do usuario deve ter preferencia sobre a malha persistente de
controle e sobre Swarm Assist.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistConnectionPolicy.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistResourceGovernor.java`
- `src/main/java/dev/lufi/infrastructure/bootstrap/BootstrapPeerConnectionRegistry.java`
- `src/main/java/dev/lufi/infrastructure/bootstrap/BootstrapNeighborManager.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/ConnectionRole.java`
- `src/main/java/dev/lufi/infrastructure/ConnectionLimits.java`
- `src/main/java/dev/lufi/infrastructure/ConnectionLimitSettings.java`
- `src/main/java/dev/lufi/infrastructure/GlobalConnectionBudget.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistResourceGovernor.java`
- `src/main/java/dev/lufi/infrastructure/bootstrap/BootstrapPeerConnectionRegistry.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- `src/test/java/dev/lufi/infrastructure/GlobalConnectionBudgetTest.java`
- `src/test/java/dev/lufi/infrastructure/ConnectionLimitSettingsTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- Foram criadas configuracoes persistidas e tambem sobrescreviveis por
  propriedades Java: `maxOverlayConnections`, `maxAssistConnections`,
  `maxSeedConnections`, `maxDownloadConnections`, `maxPendingConnections` e
  `maxTotalConnections`.
- Os valores iniciais sao 12 para overlay/rendezvous, 60 para Assist, 24 para
  seed, 32 para transferencias do usuario, 24 tentativas pendentes e 128 no
  total. O total sempre reserva pelo menos as capacidades de download e seed.
- A classificacao e explicita: `STREAM`, `DOWNLOAD`, `SEED`, `RENDEZVOUS`,
  `OVERLAY` e `ASSIST`. A ordem de protecao e stream, download, seed,
  rendezvous, overlay e Assist. Stream e download compartilham o limite de
  transferencia do usuario; uma vaga e reservada para que downloads em lote nao
  impeçam o inicio de um stream.
- `PeerConnectivityManager` continua sendo o unico ponto que promove peers de
  saida. Antes de entregar um peer ao bt-core, `BtTorrentGateway` consulta
  `GlobalConnectionBudget`; a decisao limita categoria, total e conexoes
  pendentes, sem bloquear a DHT, PEX ou event loops.
- Ao iniciar stream/download, Swarm Assist e pausado como antes e novas conexoes
  de overlay sao adiadas. Alem disso, os limites inferiores reservam capacidade
  para as categorias acima, portanto controle nao ocupa a faixa destinada ao
  conteudo do usuario.
- Conexoes recebidas passam a ser contabilizadas apos o handshake, quando o
  infoHash e conhecido. Se a conexao aceita exceder uma categoria ou o total, a
  conexao de menor prioridade e encerrada. Isso evita reimplementar ou tentar
  interpretar o handshake BitTorrent antes do bt-core.
- Swarm Assist ainda respeita seus limites proprios por swarm; para ele vale o
  limite mais restritivo entre sua politica e o novo orcamento global.

### Testes criados

- reserva de capacidade para stream antes de Swarm Assist;
- compartilhamento do limite de download por stream e downloads;
- reserva de uma vaga para stream antes de downloads em lote;
- limite de tentativas pendentes;
- deduplicacao de uma conexao ja contabilizada;
- persistencia e leitura das seis configuracoes;
- protecao da reserva download + seed quando o total persistido for baixo.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.GlobalConnectionBudgetTest \
  --tests dev.lufi.infrastructure.ConnectionLimitSettingsTest \
  --tests dev.lufi.infrastructure.PeerConnectivityManagerTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 226 |
| Aprovados | 226 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

As promocoes de peer agora obedecem um orcamento global por prioridade. Stream
e download recebem capacidade protegida, seeds e rendezvous sao admitidos antes
da malha Ola Luffy, e Assist permanece a ultima atividade oportunista. O
orçamento considera tentativas pendentes e conexoes ja aceitas; nenhuma camada
nova de transferencia ou protocolo paralelo foi criada.

### Problemas encontrados

- O bt-core revela o torrent de uma conexao recebida apenas depois do handshake.
  Por isso a admissao preventiva existe para conexoes de saida; uma conexao de
  entrada que ultrapasse o limite e fechada imediatamente apos o handshake
  aceito, sem analisar ou reimplementar bytes do protocolo.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 21

### Objetivo

Manter uma presenca BitTorrent passiva, configuravel e limitada em swarms que
o usuario apenas assistiu, sem afetar seeding, download ou streaming. Essas
conexoes fornecem DHT, PEX, `lf_identity` e possiveis relacoes de rendezvous;
elas nao solicitam nem anunciam pecas de conteudo.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/SwarmMembershipRepository.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistSettings.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistConnectionPolicy.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistDhtScheduler.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistResourceGovernor.java`
- `src/main/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistry.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/SwarmAssistManager.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistEntry.java`
- `src/main/java/dev/lufi/infrastructure/SwarmNeedEvaluator.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistPolicy.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistSettings.java`
- `src/main/java/dev/lufi/infrastructure/SwarmAssistConnectionPolicy.java`
- `src/main/java/dev/lufi/infrastructure/SwarmMembershipRepository.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- `src/test/java/dev/lufi/infrastructure/SwarmAssistManagerTest.java`
- `src/test/java/dev/lufi/infrastructure/SwarmNeedEvaluatorTest.java`
- `src/test/java/dev/lufi/infrastructure/SwarmAssistSettingsTest.java`
- `src/test/java/dev/lufi/infrastructure/SwarmAssistConnectionPolicyTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `SwarmAssistManager` e a unica camada que decide entrada, substituicao,
  restauracao e expiracao da lista passiva. A UI apenas informa que uma sessao
  temporaria terminou; DHT, PEX e listeners nao tomam decisao de retencao.
- `SwarmAssistPolicy` centraliza os valores configuraveis: 25 swarms por
  padrao, permanencia minima de 30 minutos, histerese de 20%, tres conexoes por
  swarm e 75 conexoes assistenciais no total. Esses limites nao se aplicam a
  streaming, downloads ou swarms que o usuario esta semeando.
- A selecao usa `SwarmNeedEvaluator`: populacao observada, conexoes atuais,
  peers com hole punching, peers alcancaveis, atividade e frescor da observacao.
  Swarms pequenos e sem conexoes uteis recebem prioridade; uma diferenca pequena
  nao provoca substituicao.
- Antes de substituir uma lista cheia, dados vencidos sao renovados por DHT/PEX.
  No reinicio, todas as populacoes persistidas sao invalidadas e restauradas de
  forma sequencial, nunca confiando em uma contagem antiga.
- Um cliente Assist usa a `BtRuntime` principal com todos os arquivos em `SKIP`.
  Assim ele permanece `NOT INTERESTED`, nunca anuncia `I HAVE PIECE` e nao faz
  download involuntario. As conexoes criadas por essa runtime passam pela mesma
  extensao `lf_identity` e pelo mesmo `ConnectedLuffyRegistry`; portanto uma
  conexao assistencial de Z com B pode responder a `FIND_NODE(B)`.
- Prioridade de recursos continua no gateway: reproducao, download e seeding do
  usuario podem pausar as conexoes oportunistas de Assist.

### Testes criados

- avaliacao de necessidade: swarm pequeno, desconectado e sem peer de
  rendezvous tem prioridade maior que um swarm saudavel;
- estatistica vencida nao dirige uma decisao de retencao;
- lista cheia substitui o swarm mais saudavel por um candidato mais fragil;
- candidato mais saudavel nao remove uma entrada existente;
- seed/download voluntario remove apenas sua entrada passiva equivalente;
- restauracao invalida a populacao persistida e a reobserva;
- limites de conexao por swarm e globais sao configuraveis e aplicados pelo
  controle de admissao.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.SwarmAssistManagerTest \
  --tests dev.lufi.infrastructure.SwarmNeedEvaluatorTest \
  --tests dev.lufi.infrastructure.SwarmMembershipRepositoryTest \
  --tests dev.lufi.infrastructure.SwarmAssistConnectionPolicyTest \
  --tests dev.lufi.infrastructure.SwarmAssistDhtSchedulerTest \
  --tests dev.lufi.infrastructure.SwarmAssistResourceGovernorTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 219 |
| Aprovados | 219 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

A Swarm Assist List esta ativa e separada de seeding. Um magnet assistido entra
somente apos o termino da sessao temporaria. A lista respeita capacidade,
histerese, permanencia minima, decaimento de swarms vazios/inativos e os
orcamentos de conexao. As conexoes de Assist mantem presenca BitTorrent real e
sao visiveis ao registro global de identidades Luffy, sem transportar conteudo.

### Problemas encontrados

- A politica foi validada com runtime falso e testes do motor/overlay ja
  existentes. A eficacia de rendezvous entre redes externas ainda depende de
  conectividade real, NAT e compatibilidade uTP/BEP 55 de cada peer.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 20

### Objetivo

Provar em uma topologia local de cinco runtimes que a busca distribuida e o
rendezvous roteado podem levar a uma transferencia BitTorrent real por uTP:

```text
A -- Ola Luffy -- C -- Ola Luffy -- X -- Ola Luffy -- Z -- outro torrent -- B
\                                               /
 \---------------- lf_route / lf_rendezvous ---/

A <========================== uTP ==========================> B
```

A conexao TCP inicial entre A e B permanece ausente. C, X e Z participam
somente do plano de controle; o torrent de conteudo nao e carregado por eles.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchIntegrationTest.java`

### Arquivos alterados

- `src/test/java/dev/lufi/infrastructure/OverlayLocalIntegrationTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- A prova sobe cinco `BtRuntime` reais, cada um com as extensoes BEP 10
  `lf_identity`, `lf_route`, `lf_rendezvous` e com a ponte uTP/bt-core ja
  existente. Nao foi criado transporte de teste paralelo.
- A-C, C-X e X-Z usam o `TorrentId` oficial de Ola Luffy. Z-B usa um segundo
  torrent, demonstrando que `ConnectedLuffyRegistry` encontra uma identidade
  viva fora do swarm bootstrap.
- Os limites de conexoes do teste impedem atalhos de PEX, preservando a rota
  obrigatoria A -> C -> X -> Z. O teste confirma a tabela de rota temporaria
  em C, X e Z depois de `NODE_FOUND` retornar a A.
- B semeia `teste.txt`; A possui o metainfo, mas nao abre TCP para B. Apos
  `lf_rendezvous`, A e B iniciam uTP e a ponte somente considera a sessao
  conectada depois de o bt-core aceitar o peer.
- C, X e Z nao possuem o `TorrentId` do conteudo no `TorrentRegistry` e seus
  logs nao registram o infoHash dele. Assim eles nao podem transportar
  handshake BitTorrent do conteudo, metadata, requests, pieces ou bytes do
  arquivo. O unico arquivo validado e o recebido diretamente por A de B.

### Testes criados

- topologia real A-C-X-Z-B, com tres conexoes no swarm Ola Luffy e Z-B em
  outro torrent;
- `FIND_NODE` multi-hop, retorno `NODE_FOUND` e preservacao da rota inversa;
- `lf_rendezvous` roteado, preparacao de B por Z e uTP iniciado nos extremos;
- handshake aceito e peer registrado no bt-core sobre uTP;
- download completo de `teste.txt`, hash validado pelo bt-core e conteudo
  exato `OLA LUFFY`;
- ausencia do torrent de conteudo e de seus bytes nos tres intermediarios.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.OverlayLocalIntegrationTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 212 |
| Aprovados | 212 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada em loopback. A encontrou o `LuffyNodeId` de B pela rota
A -> C -> X -> Z, recebeu Z como candidato de rendezvous, fez a preparacao
por `lf_rendezvous` e baixou `teste.txt` diretamente de B pelo caminho
`uTP -> UtpBitTorrentBridge -> bt-core`. O arquivo final e `OLA LUFFY`; C, X
e Z permaneceram exclusivamente no plano de controle.

### Problemas encontrados

- A validacao usa loopback e confirma o fluxo do protocolo e do motor
  BitTorrent, nao a compatibilidade de NAT/CGNAT de duas redes externas.
- O teste desabilita atalhos de topologia pela configuracao de capacidade dos
  peers; em producao, PEX continua livre para adicionar vizinhos conforme a
  politica de `BootstrapNeighborManager`.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 19

### Objetivo

Usar `lf_rendezvous` como o ultimo fallback do gerenciador de conectividade
existente, na ordem: TCP direto, uTP direto com endpoint independente, BEP 55
local no swarm do conteudo, rendezvous pelo swarm Ola Luffy e, por fim,
backoff. Nenhuma segunda DHT, runtime, fila de download ou gerenciador de
conexoes foi criada.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousFallbackConfig.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousFallbackCoordinator.java`
- `src/test/java/dev/lufi/infrastructure/PeerConnectivityManagerTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousFallbackCoordinatorTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinatorFallbackTest.java`

### Decisoes tecnicas

- `PeerConnectivityManager` continua dono da ordem de estrategias. Ele chama
  `RendezvousFallbackCoordinator.onDirectConnectivityExhausted(...)` somente
  depois de `onHolePunchUnavailable`, isto e, apos o BEP 55 local nao possuir
  relay ou o uTP iniciado por ele falhar.
- `PeerConnectivityContext` e uma fotografia imutavel: infoHash, endpoint
  uTP, NodeId/capacidades conhecidos, atividade do torrent, estado de peer,
  backoff e encerramento da aplicacao. O callback nao abre socket e nao bloqueia
  DHT, PEX, listeners ou event loop do bt-core.
- O coordenador recusa antes de iniciar a rota quando o torrent encerrou, peer
  foi removido, conexao direta ja venceu, ha backoff, falta `LuffyNodeId`, as
  capacidades nao confirmam rendezvous/uTP/hole punch, falta endpoint uTP local
  confirmado ou a aplicacao esta fechando.
- `lf_identity` agora informa a identidade validada ao gerenciador. Ela fica
  associada ao endpoint TCP autenticado e so pode ser propagada ao caminho uTP
  quando existe exatamente um TCP e um uTP conhecidos para aquele endereco no
  swarm; casos ambiguos sao recusados. A identidade nao e derivada de IP,
  porta, MAC ou torrent, e nenhuma porta TCP e convertida em porta UDP.
- O limite configuravel inicial e quatro sessoes de overlay. Reservas em inicio
  contam para esse limite, impedindo que tentativas concorrentes o ultrapassem;
  uma chave `infoHash + NodeId + endpoint` deduplica tentativas equivalentes.
- O termino de `RendezvousCoordinator` e devolvido ao gerenciador. `CONNECTED`
  so e registrado apos o aceite anterior do bt-core; falha, expiracao ou rota
  recusada mudam o peer para inacessivel e programam o backoff existente.

### Testes criados

- ordem obrigatoria uTP direto -> BEP 55 local -> overlay;
- associacao de `lf_identity` ao contexto de conectividade;
- ausencia de NodeId, capacidade insuficiente, torrent inativo, peer removido,
  conexao direta concluida, backoff, encerramento e endpoint local ausente;
- deduplicacao de tentativa equivalente, limite global e liberacao de vaga no
  fim da sessao;
- notificacao de estado terminal do coordenador para a conectividade.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.PeerConnectivityManagerTest \
  --tests dev.lufi.infrastructure.rendezvous.RendezvousFallbackCoordinatorTest \
  --tests dev.lufi.infrastructure.rendezvous.RendezvousCoordinatorFallbackTest \
  --tests dev.lufi.infrastructure.Bep55HolePunchIntegrationTest \
  --tests dev.lufi.infrastructure.UtpBitTorrentBridgeIntegrationTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 211 |
| Aprovados | 211 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. O overlay agora e um fallback automatico e controlado da
conectividade, sem interferir quando TCP ou uTP direto funcionam. A atividade
de overlay e assincrona, deduplicada e limitada; o backoff ja existente recebe
o resultado terminal se nao houver conexao BitTorrent aceita.

### Problemas encontrados

- Um peer descoberto apenas pela DHT/PEX ainda nao possui necessariamente um
  `LuffyNodeId`. Nessa situacao o overlay e corretamente suprimido e o
  gerenciador segue para backoff; ele nao tenta inferir identidade pelo IP.
- A transferencia atraves de NAT/CGNAT reais continua exigindo validacao entre
  redes externas. Os testes desta etapa cobrem o encadeamento e a transferencia
  uTP/BEP 55 em loopback, nao a compatibilidade de um NAT especifico.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 18

### Objetivo

Conectar o controle roteado de `lf_rendezvous` ao caminho existente
`Bep55HolePunchAgent -> UtpTransportService -> UtpBitTorrentBridge -> bt-core`,
sem criar transporte paralelo nem permitir que C/X/Z carreguem metadados,
requests, pieces ou arquivos do torrent.

### Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/ExternalEndpointRegistry.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousMessage.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousCodec.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousEndpointProvider.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousEndpointSelector.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousPunchExecutor.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousFallbackPolicy.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchIntegrationTest.java`
- `src/test/java/dev/lufi/infrastructure/UtpBitTorrentBridgeFailureIntegrationTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousCodecTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtensionTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousEndpointSelectorTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinatorFallbackTest.java`

### Decisoes tecnicas

- `RENDEZVOUS_REQUEST` leva o endpoint uTP confirmado de A; Z somente aceita a
  solicitacao depois de confirmar novamente a conexao viva, as capacidades e o
  endpoint uTP valido de B. O endpoint de B retorna a A em `PREPARE`.
- O seletor usa apenas observacoes `UTP`, publicas, confirmadas e nao expiradas
  de `ExternalEndpointRegistry`. Endpoint TCP nunca e convertido em endpoint
  uTP, e observacoes privadas ou estimadas sao recusadas.
- Como `CONNECT` do BEP 55 nao carrega o `infoHash`, ele nao pode ser enviado
  cegamente pela conexao Z--B de outro swarm. O controle `lf_rendezvous`
  fornece o `TorrentId` explicito e chama o `Bep55HolePunchAgent` existente;
  este usa o mesmo transporte e a mesma ponte uTP para o torrent de conteudo.
- A promocao de saida uTP so conclui depois de o bt-core aceitar o handshake e
  registrar o peer. Em corrida de punch simultaneo, a primeira conexao aceita
  fica na pool e apenas o tunel duplicado e fechado.
- Falhas de rota, endpoint, desconexao de B, timeout, uTP e handshake encerram
  a sessao. O fallback e limitado por `RendezvousFallbackPolicy` (padrao: tres
  coordenadores) e so troca de candidato antes de `PUNCHING`.
- `BtTorrentGateway.requestOverlayRendezvous(...)` une a busca `lf_route`
  vencedora a uma sessao `lf_rendezvous`; ela e uma entrada de fallback e deve
  ser chamada apenas depois das estrategias diretas falharem.

### Testes criados

- fluxo de controle A -> C -> X -> Z -> B, com preparacao de ambos os lados,
  resultado voltando pela rota e nenhuma transferencia de dados pelo overlay;
- rejeicao de endpoint uTP expirado, privado ou nao confirmado;
- troca limitada para o proximo coordenador quando o envio inicial falha;
- codec com endpoint, direcao e sem expansao de payload;
- regressao de corrida BEP 55: duas conexoes uTP simultaneas preservam a
  primeira aceita pelo bt-core e fecham somente a duplicada.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.Bep55HolePunchIntegrationTest \
  --tests dev.lufi.infrastructure.UtpBitTorrentBridgeIntegrationTest \
  --tests dev.lufi.infrastructure.UtpBitTorrentBridgeFailureIntegrationTest \
  --tests dev.lufi.infrastructure.rendezvous.*
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 206 |
| Aprovados | 206 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada em ambiente de integracao local: o teste BEP 55 transfere
`teste.txt` com o conteudo `OLA LUFFY` apos a coordenacao do relay, e a
promocao para o bt-core ocorre antes de a sessao ser considerada conectada.
Z atua somente no plano de controle; os bytes do arquivo seguem diretamente
entre A e B por uTP.

### Problemas encontrados

- O teste automatizado usa loopback. A compatibilidade com NAT/CGNAT reais
  continua dependente de endpoints UDP publicos confirmados e deve ser validada
  posteriormente entre redes externas; esta etapa nao promete atravessar um
  NAT incompativel.
- A busca atual retorna um coordenador vencedor. A API de fallback aceita
  candidatos da mesma busca quando eles estiverem disponiveis; a coleta de
  multiplos `NODE_FOUND` para alimentar essa lista sera uma evolucao posterior.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 17

### Objetivo

Criar o protocolo BEP 10 `lf_rendezvous` para encaminhar somente comandos de
controle por uma rota vencedora de `lf_route`, permitindo a preparacao entre
A e um coordenador Z mesmo quando a rota e A -> C -> X -> Z. A etapa nao
inicia BEP 55, nao abre sockets uTP e nao transporta metadata, requests,
pieces, video ou qualquer arquivo.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/overlay/ReverseRouteRegistry.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteExtension.java`
- `src/main/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistry.java`
- `src/main/java/dev/lufi/infrastructure/PeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/overlay/OverlayRoutePathRegistry.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`
- `src/main/java/dev/lufi/infrastructure/overlay/ReverseRouteRegistry.java`
- `src/main/java/dev/lufi/infrastructure/overlay/LuffyRouteExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtension.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousMessage.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousCodec.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousMessageHandler.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCoordinator.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousSession.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousSessionRegistry.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousState.java`
- `src/main/java/dev/lufi/infrastructure/PeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousCodecTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousSessionRegistryTest.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/LuffyRendezvousExtensionTest.java`
- `src/test/java/dev/lufi/infrastructure/overlay/MultiHopFindNodeServiceTest.java`
- `src/test/java/dev/lufi/infrastructure/PeerCapabilitiesTest.java`

### Decisoes tecnicas

- A primeira forma da mensagem binaria v1 tinha tamanho fixo de 151 bytes e
  aceitava somente os tipos `RENDEZVOUS_REQUEST`, `RENDEZVOUS_PREPARE`,
  `RENDEZVOUS_ACCEPTED`, `RENDEZVOUS_REJECTED`, `RENDEZVOUS_RESULT` e
  `RENDEZVOUS_ERROR`. Ela carrega IDs da sessao e rota, os tres NodeIds e o
  `TorrentId` de conteudo; nao possui campo para dados BitTorrent. A Etapa 18
  ampliou o frame fixo para 171 bytes a fim de carregar somente endpoints UDP
  confirmados, sem adicionar dados do torrent.
- Apos `NODE_FOUND`, cada peer guarda por no maximo o TTL de rota somente seu
  predecessor e sucessor vencedores. Essa informacao e removida na expiracao
  ou ao encerrar uma das conexoes; nenhuma lista de vizinhos e publicada.
- `RendezvousSessionRegistry` valida transicoes, aceita retransmissoes
  idempotentes, rejeita colisao de `sessionId`, expira sessoes e remove estados
  terminais `CONNECTED`, `FAILED` e `CANCELLED`.
- O coordenador Z confirma apenas que possui conexao Luffy viva com B. Nesta
  etapa ele retorna `PREPARED`; a execucao BEP 55/uTP continua sendo uma etapa
  posterior. O torrent permanece fora do caminho C/X/Z.
- A extensao e um modulo adicional da `BtRuntime` ja existente. Nao cria
  listener, runtime, canal ou protocolo paralelo; peers sem `lf_rendezvous`
  continuam inalterados.

### Testes criados

- codec para todos os seis tipos, versao e tamanhos invalidos;
- registro de sessoes: transicoes validas/invalidas, idempotencia, expiracao,
  colisao e limpeza apos falha ou sucesso;
- rota completa A -> C -> X -> Z e retorno por Z -> X -> C -> A, com a
  sequencia REQUEST, PREPARE, ACCEPTED e RESULT;
- preservacao dos dois saltos locais da rota vencedora;
- anuncio e verificacao da capacidade `lf_rendezvous` no extension handshake.

### Testes executados

```text
gradle test --tests dev.lufi.infrastructure.rendezvous.LuffyRendezvousCodecTest \
  --tests dev.lufi.infrastructure.rendezvous.RendezvousSessionRegistryTest \
  --tests dev.lufi.infrastructure.rendezvous.LuffyRendezvousExtensionTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 202 |
| Aprovados | 202 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. O Luffy pode negociar uma sessao de rendezvous de controle
atraves do overlay, sem entregar dados de torrent aos intermediarios. A sessao
termina em `PREPARING`; o proximo passo deve conectar esse ponto, de forma
limitada e testada, ao BEP 55 e ao transporte uTP existente.

### Problemas encontrados

- Esta etapa nao executa hole punching nem promove uma conexao uTP ao
  bt-core. Essa separacao e intencional: sem ela, uma falha de conectividade
  poderia ser confundida com roteamento ou transferencia de pieces.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.

## Registro da Etapa 16 (historico)

### Objetivo

Escolher localmente o melhor coordenador de rendezvous ja conectado ao alvo B,
sem iniciar BEP 55, uTP, DHT ou transferencia de dados nesta etapa.

## Arquivos analisados

- `docs/current-bittorrent-engine.md`
- `docs/current-utp-integration.md`
- `src/main/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistry.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyPeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/ObservedEndpoint.java`
- `src/main/java/dev/lufi/infrastructure/ExternalEndpointRegistry.java`
- `src/main/java/dev/lufi/infrastructure/ConnectivityProfile.java`
- `src/main/java/dev/lufi/infrastructure/RendezvousPeerSelector.java`
- `src/main/java/dev/lufi/infrastructure/overlay/FindNodeService.java`

## Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCandidate.java`
- `src/main/java/dev/lufi/infrastructure/rendezvous/RendezvousCandidateSelector.java`
- `src/test/java/dev/lufi/infrastructure/rendezvous/RendezvousCandidateSelectorTest.java`
- `docs/overlay-implementation-progress.md`

## Decisoes tecnicas

- `RendezvousCandidate` mantem somente uma fotografia de controle: NodeIds,
  distancia, capacidades Luffy, latencia estimada, carga, estabilidade,
  endpoint uTP observado e estado de conexao. Ele nao contem dados de torrent.
- A conexao local direta com B vence imediatamente e dispensa rendezvous.
- Um candidato elegivel deve manter conexao de controle ativa, conexao ativa
  com B, endpoint externo uTP confirmado e nao expirado, e suporte real a uTP,
  hole punching e rendezvous. Backoff, bloqueio e sobrecarga sao rejeicoes.
- A ordenacao privilegia primeiro peer comum no swarm do conteudo, depois
  menor distancia, menor carga, maior estabilidade e menor latencia. Um peer
  marcado como servidor permanece apenas como fallback, atras de peers comuns.
- A selecao recebe um verificador de vida e o consulta no instante da decisao;
  portanto uma conexao encerrada durante a selecao nao e retornada.

## Testes criados

- `RendezvousCandidateSelectorTest` cobre candidato unico, varios candidatos,
  conexao direta com B, primeiro candidato invalido, servidor versus peer
  comum, endpoint expirado, capacidades insuficientes e conexao encerrada
  durante a selecao.

## Testes executados

```text
gradle test --tests dev.lufi.infrastructure.rendezvous.RendezvousCandidateSelectorTest
gradle test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 195 |
| Aprovados | 195 |
| Falhando | 0 |
| Ignorados | 0 |

## Resultado

Etapa aprovada. O Luffy possui uma selecao de rendezvous testada, que escolhe
somente peers ainda elegiveis e prioriza conectividade direta antes de recorrer
a qualquer coordenador. Nenhum video, metadata ou piece passa pelo candidato.

## Problemas encontrados

- A criacao de candidatos a partir do registro global de conexoes e o
  acionamento do BEP 55 ainda pertencem a proxima etapa de integracao. Esta
  etapa intencionalmente apenas seleciona e revalida candidatos.

## Registro da Etapa 6

### Objetivo

Validar que um relay C, conectado por BitTorrent a A e B no mesmo infoHash,
envia somente as mensagens oficiais BEP 55 e permite que A baixe `teste.txt`
de B pela conexao uTP direta A-B.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/RendezvousPeerSelector.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchAgentTest.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchIntegrationTest.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/RendezvousPeerSelector.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchMessage.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchAgentTest.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchIntegrationTest.java`
- `src/test/java/dev/lufi/infrastructure/UtpBitTorrentBridgeFailureIntegrationTest.java`
- `docs/overlay-implementation-progress.md`
- `docs/current-utp-integration.md`

### Decisoes tecnicas

- `Bep55HolePunchAgent` agora e um agente publico do pipeline de mensagens do
  `bt-core`; isso e necessario porque o compilador de agentes da biblioteca usa
  `MethodHandles.publicLookup()` para os handlers `@Consumes` e `@Produces`.
- A porta TCP anunciada no extension handshake nunca e reutilizada como porta
  UDP/uTP. CONNECT so e encaminhado quando C possui observacoes UDP
  independentes, associadas de forma nao ambigua aos peers A e B.
- Quando um candidato C retorna `NOT_CONNECTED`, `NO_SUCH_PEER` ou `NO_SELF`,
  A tenta o proximo candidato elegivel no mesmo swarm. `NO_SUPPORT` continua
  sendo terminal para o alvo.
- Tentativas concorrentes para o mesmo `(infoHash, endpoint UDP)` sao
  atomicas: somente uma mensagem RENDEZVOUS e enviada dentro do cooldown.
- O teste usa `IConnectionSource` do proprio bt-core para criar A-C e C-B;
  assim handshake, pool e extensoes nao sao simulados por um protocolo paralelo.

### Testes criados ou ampliados

- `Bep55HolePunchAgentTest` cobre candidato alternativo, target sem
  `ut_holepunch`, endpoints UDP do iniciador ou alvo ausentes, CONNECT para os
  dois peers, CONNECT duplicado, duas solicitacoes simultaneas e limpeza apos
  desconexao.
- `Bep55HolePunchIntegrationTest` cria A, B e C em diretorios temporarios;
  B semeia `teste.txt` com `OLA LUFFY`, C mantem conexoes BitTorrent reais com
  A e B, e A baixa o arquivo apos RENDEZVOUS/CONNECT e uTP A-B.
- Os testes de falha da etapa anterior continuam cobrindo timeout de SYN,
  RESET, canal fechado, infoHash desconhecido e limpeza de sessoes/pumps.

### Testes executados

```text
gradle --no-daemon test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 111 |
| Aprovados | 111 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada em loopback. C so coordena RENDEZVOUS/CONNECT; metadata,
requests de pieces, pieces e `teste.txt` trafegam somente entre A e B pelo
canal uTP entregue ao bt-core.

### Problemas encontrados

- Um `BtClient.stop()` manual pode disputar com a remocao assincrona do
  metainfo no registry interno do bt-core. O teste de encerramento durante
  handshake valida a propriedade relevante para a ponte: em ambos os resultados
  possiveis da factory, o SocketChannel e fechado e nao fica recurso preso.
- A validacao e local. Ela nao demonstra que NAT/CGNAT externo permite o mesmo
  caminho, nem substitui o teste entre redes reais de uma etapa posterior.

## Registro da Etapa 7

### Objetivo

Criar uma identidade persistente, aleatoria e independente da rede para cada
instalacao do Luffy, sem alterar o peer ID BitTorrent nem o fluxo atual de
DHT, torrents, seeding ou download.

### Arquivos analisados

- `build.gradle.kts`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- `src/main/java/dev/lufi/infrastructure/P2pDiagnostics.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/identity/LuffyNodeId.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyNodeIdentity.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityStorage.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- `src/test/java/dev/lufi/infrastructure/identity/LuffyIdentityStorageTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `LuffyNodeId` possui 256 bits gerados por `SecureRandom`, representacao
  binaria defensiva e texto Base64 URL sem padding (43 caracteres).
- A identidade fica em `~/.lufi/luffy-node-identity.json`, com `version`,
  `nodeId` e `createdAt`. Ela nao contem IP, porta, MAC, infoHash nem substitui
  o peer ID BitTorrent.
- A escrita cria um arquivo temporario no mesmo diretorio, chama `fsync` por
  `FileChannel.force(true)` e so entao faz `ATOMIC_MOVE`. Se a troca atomica
  nao puder ser realizada, a criacao falha em vez de gravar parcialmente.
- Um arquivo invalido e movido para um backup com sufixo `.corrupt-...`; o log
  da aba de diagnostico informa explicitamente que a identidade anterior nao
  foi recuperada antes de criar a nova.

### Testes criados

- primeira criacao e persistencia;
- leitura da mesma identidade apos reinicializacao;
- identidades diferentes entre duas instalacoes;
- arquivo invalido com backup e log explicito;
- interrupcao antes da troca atomica;
- igualdade, `hashCode` e copia defensiva;
- serializacao do formato JSON;
- rejeicao de tamanho binario ou texto invalidos.

### Testes executados

```text
gradle --no-daemon test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 119 |
| Aprovados | 119 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. A instalacao agora reutiliza a mesma identidade local em cada
abertura; ela e criada de modo seguro somente quando ainda nao existe, ou apos
recuperacao explicita de um arquivo corrompido. Nenhum identificador nem
comportamento do motor BitTorrent foi substituido nesta etapa.

### Problemas encontrados

- Ainda nao ha uso da identidade no protocolo de overlay. Isso e intencional:
  esta etapa estabelece somente a fundacao persistente, sem alterar a rede
  BitTorrent existente.

## Registro da Etapa 8

### Objetivo

Associar uma conexao BitTorrent entre Luffys a um `LuffyNodeId` persistente
por `lf_identity`, sem substituir o peer ID BitTorrent nem tornar a extensao
obrigatoria para peers comuns.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/PeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyNodeIdentity.java`
- `src/test/java/dev/lufi/infrastructure/Bep55HolePunchIntegrationTest.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityMessage.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityCodec.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityMessageHandler.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyPeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/PeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/UtpBitTorrentBridge.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/ui/LufiApplication.java`
- `src/test/java/dev/lufi/infrastructure/identity/LuffyIdentityExtensionTest.java`
- `src/test/java/dev/lufi/infrastructure/LuffyIdentityBepHandshakeBridgeTest.java`
- `src/test/java/dev/lufi/infrastructure/PeerCapabilitiesTest.java`
- `docs/current-bittorrent-engine.md`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `lf_identity` e uma mensagem BEP 10 binaria v1 com no maximo 99 bytes:
  versao, 32 bytes de node ID, `clientVersion` UTF-8 e quatro flags de
  capacidade. Versoes, flags e bytes excedentes desconhecidos sao rejeitados.
- O envio so e liberado depois de o extension handshake remoto anunciar
  `lf_identity`; peer sem suporte nao recebe a mensagem e segue normalmente no
  protocolo BitTorrent.
- A runtime com uTP reutiliza o observador de `ExtendedHandshake` de BEP 55
  para notificar a extensao. Se uTP nao iniciou, o Luffy instala apenas um
  observador minimo de identidade, sem duplicar observadores na mesma runtime.
- Se o node ID mudar na mesma conexao, as capacidades daquela conexao sao
  removidas e ela e rejeitada. A limpeza tambem usa o evento de desconexao
  BitTorrent ja existente.
- As flags uTP/rendezvous/hole punch sao calculadas na hora do envio: a ponte
  deve estar pronta e o peer conectado deve ser IPv4. `supportsRoute` e falso
  enquanto o protocolo `lf_route` nao existir.

### Testes criados

- round-trip do codec e handler;
- anuncio real de `lf_identity` no handshake BEP 10 do bt-core;
- peer sem extensao;
- node ID e payload invalidos, versao incompativel, excesso e flags
  desconhecidas;
- conflito de node ID na mesma conexao;
- regras de capacidades;
- encaminhamento pelo observador BEP 55 ja existente.

### Testes executados

```text
gradle --no-daemon test
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 128 |
| Aprovados | 128 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Etapa aprovada. Dois Luffys que anunciam `lf_identity` no handshake podem
trocar e validar seu node ID persistente e as capacidades declaradas. Peers
BitTorrent sem a extensao permanecem compativeis e nao sofrem bloqueio de
download, PEX, seeding ou BEP 55.

### Problemas encontrados

- A primeira centralizacao do observador de handshake tirava o BEP 55 do seu
  teste isolado. A correcao preserva um observador por runtime: BEP 55 quando
  uTP existe e identidade minima somente quando ele nao existe.
- A extensao nao autentica criptograficamente o node ID ainda; ela apenas
  estabelece uma identidade persistente por conexao. Assinatura/chaves, rota e
  rendezvous global pertencem a etapas posteriores.

## Registro da Etapa 9

### Objetivo

Manter um registro local, global ao `BtTorrentGateway`, das conexoes BitTorrent
vivas que concluiram a negociacao `lf_identity`. A busca e por `LuffyNodeId`,
independente do torrent que originou a conexao, sem abrir sockets, sem iniciar
transferencia e sem interferir no DHT, PEX ou bt-core.

### Arquivos analisados

- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyPeerCapabilities.java`
- `src/main/java/dev/lufi/infrastructure/ObservedEndpoint.java`
- `src/test/java/dev/lufi/infrastructure/identity/LuffyIdentityExtensionTest.java`

### Arquivos alterados

- `src/main/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistry.java`
- `src/main/java/dev/lufi/infrastructure/identity/LuffyIdentityExtension.java`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/test/java/dev/lufi/infrastructure/identity/ConnectedLuffyRegistryTest.java`
- `src/test/java/dev/lufi/infrastructure/identity/LuffyIdentityExtensionTest.java`
- `docs/overlay-implementation-progress.md`

### Decisoes tecnicas

- `ConnectionKey` do bt-core e a referencia interna de uma conexao viva. Cada
  entrada armazena tambem o `TorrentId` que a originou; assim um mesmo NodeId
  pode ter diversas conexoes em torrents diferentes.
- A entrada so e criada depois de `lf_identity` ser validada. Mudanca de node
  ID na mesma conexao remove a referencia, e `onPeerDisconnected(...)`, o
  lifecycle existente, remove as referencias encerradas.
- A escolha da melhor conexao de controle prioriza capacidades reais de
  rendezvous, rota, uTP e hole punch e, depois, a conexao vista mais
  recentemente. A API nao expoe uma lista global de referencias de conexao.
- Endpoints TCP/uTP externos permanecem opcionais: esta etapa nao transforma
  o endereco de um socket em endpoint publico sem observacao independente.
  A direcao fica `UNKNOWN` ate haver instrumentacao associada sem ambiguidade
  ao `ConnectionKey`.

### Testes criados

- mesmo NodeId em dois torrents;
- remocao de uma entre varias conexoes e da ultima conexao;
- selecao da melhor conexao de controle e capacidades diferentes;
- limpeza pelo evento de conexao encerrada;
- registro/remocao concorrentes;
- integracao da identidade aceita e da desconexao com o registro global.

### Testes executados

```text
gradle --no-daemon test --tests ConnectedLuffyRegistryTest --tests LuffyIdentityExtensionTest
```

| Metrica | Resultado |
| --- | ---: |
| Testes focados | 16 |
| Aprovados | 16 |
| Falhando | 0 |
| Ignorados | 0 |

### Resultado

Os testes focados e a suite completa passaram. O registro agrega conexoes
identificadas por `lf_identity` em torrents diferentes, remove referencias no
lifecycle de desconexao e nao muda os caminhos de DHT, PEX ou transferencia.

### Validacao completa

```text
gradle --no-daemon test --rerun-tasks
```

| Metrica | Resultado |
| --- | ---: |
| Testes totais | 136 |
| Aprovados | 136 |
| Falhando | 0 |
| Ignorados | 0 |

### Problemas encontrados

- O registro ainda nao envia `lf_route`, nao cria o swarm global Ola Luffy e
  nao inicia rendezvous. Ele apenas disponibiliza a informacao local para as
  etapas posteriores.

## Proxima etapa

Aguardar a proxima etapa explicitamente solicitada.
