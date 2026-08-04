# Auditoria do motor BitTorrent atual

Data da fotografia: 30 de julho de 2026. Este documento descreve somente o
estado que já existe no código. Nenhum protocolo, fluxo de conexão ou comportamento
de rede foi alterado durante esta auditoria.

## Engine e versão

| Item | Estado atual |
| --- | --- |
| Engine BitTorrent | `bt-core` (`com.github.atomashpolskiy:bt-core`) |
| DHT usada pela engine | `bt-dht` (`com.github.atomashpolskiy:bt-dht`) |
| Versão declarada | `1.10` para ambas, em `build.gradle.kts` |
| Java | Java 21 |
| Biblioteca de mapeamento de porta | `com.offbynull.portmapper:portmapper:2.0.6`; ela não é a engine BitTorrent |

## Mapa do fluxo atual

| Responsabilidade | Localização no código | Funcionamento observado |
| --- | --- | --- |
| Inicialização da runtime BitTorrent IPv4 | `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`, método `runtime()` | Cria `BtRuntime` com `networkConfig(false)`, ativa módulos e instala a instrumentação de conexão. |
| Runtime de busca DHT | `BtTorrentGateway.dhtLookupRuntime(boolean)` | Cria uma runtime separada, apenas para descoberta. Ela usa porta TCP efêmera e não cria `BtClient`; portanto não anuncia um torrent local. |
| Módulo DHT | `BtTorrentGateway.dhtDiscoveryModule(boolean)` e `src/main/java/dev/lufi/infrastructure/LuffyDhtDiscoveryModule.java` | Configura `DHTConfig`, bootstrap por roteadores públicos, porta UDP local e família IPv4/IPv6; expõe `DHTService` com `MldhtService`. |
| Consulta e recebimento de peer por DHT | `BtTorrentGateway.requestDhtLookup(...)` | Chama `DHTService.getPeers(TorrentId)` e encaminha cada resultado para `PeerConnectivityManager.onDhtPeerDiscovered(...)`. A DHT descobre; ela não inicia diretamente o socket TCP. |
| Recebimento de peer por PEX | `BtTorrentGateway.onPexPeerExchange(...)`, `PexObservationModule` e `src/main/java/bt/peerexchange/LuffyPexObserver.java` | Peers recebidos via `ut_pex` são encaminhados a `PeerConnectivityManager.onPexPeerDiscovered(...)` com origem PEX. |
| Decisão de conectividade | `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java` | Mantém endpoint, origem, estado, deduplicação, backoff e tentativas escalonadas. Um peer começa como `DISCOVERED`; descoberta e conexão são etapas separadas. |
| Início de conexão TCP direta | `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`, `ObservedPeerConnectionFactory.createOutgoingConnection(...)` | A instrumentação registra `onTcpConnectStart(...)` imediatamente antes de delegar a criação da conexão de saída ao `bt-core`. |
| Conexão TCP e handshake | `BtConnectionLifecycleInstrumentation.ObservedOutgoingConnectionHandler` e `BtTorrentGateway.attachTorrentDiagnostics(...)` | A instrumentação registra sucesso/falha do socket e início/resultado do handshake; o listener de eventos da runtime também registra `onPeerConnected`. |
| Entrega de peer ao torrent | `BtTorrentGateway.promotePeerToBitTorrent(...)` | Para TCP/IPv4, entrega `InetPeer` a `runtime().service(IPeerRegistry.class).addPeer(TorrentId, peer)`. Essa é a fronteira entre Connectivity e o motor BitTorrent. |
| Seeder e downloader | `BtTorrentGateway.seed(...)` e fluxos de download/streaming no mesmo arquivo | Criam `BtClient` por `Bt.client(runtime())`, com storage, torrent/magnet e seleção sequencial quando aplicável. |

## Listeners locais

| Transporte | Localização | Porta/comportamento atual |
| --- | --- | --- |
| TCP BitTorrent | `BtTorrentGateway.networkConfig(boolean)` | `Config.setAcceptorPort(connectivity.torrentListeningPort())`. A porta configurada atual é normalmente 6891. A runtime de lookup DHT usa porta TCP `0` para não se apresentar como listener de transferência. |
| UDP DHT | `BtTorrentGateway.dhtDiscoveryModule(boolean)` | `DHTConfig.setListeningPort(connectivity.dhtListeningPort())`. A porta configurada atual é normalmente 49001. IPv4 é criada sempre; IPv6 só é criada quando há IPv6 unicast global confirmado. |
| UDP/uTP do Luffy | `BtTorrentGateway.ensureUtpTransport()` e `src/main/java/dev/lufi/infrastructure/UtpTransportService.java` | Listener UDP próprio ligado a `0.0.0.0` na porta BitTorrent configurada, normalmente 6891. O caminho ativo atual é IPv4. |

Ter um listener local não confirma acessibilidade pela Internet. A decisão de anunciar
na DHT continua condicionada a um endpoint TCP externo confirmado por
`ConnectivityProfile`.

## Extensões e transportes

Há duas respostas importantes nesta auditoria: o que o `bt-core` 1.10 oferece
nativamente e o que o Luffy acrescentou ao redor dele.

| Protocolo | `bt-core`/`bt-dht` 1.10 | Estado no Luffy | Evidência principal |
| --- | --- | --- | --- |
| BEP 10 — Extension Protocol | Suportado | Usado. `PeerCapabilities.fromExtensionHandshake(...)` lê o extension handshake e as extensões anunciadas pelo peer. | `PeerCapabilities`, `BtConnectionLifecycleInstrumentation` e módulos de mensagens estendidas. |
| BEP 11 — Peer Exchange (PEX) | Suportado por `PeerExchangeModule` | Ativado explicitamente e observado pelo módulo `PexObservationModule`; peers PEX entram no `PeerConnectivityManager`. | `BtTorrentGateway.runtime()`, `PexObservationModule`, `LuffyPexObserver`. |
| BEP 29 — uTP | Não há suporte nativo identificado na versão usada | Há implementação própria em `UtpTransportService` e ponte `UtpBitTorrentBridge`; TCP e esse caminho UDP são mantidos separados. | `UtpTransportService`, `UtpBitTorrentBridge`, `UtpTransportServiceTest`. |
| BEP 55 — Hole Punching | Não há suporte nativo identificado na versão usada | Há implementação própria: `Bep55HolePunchModule`, `Bep55HolePunchAgent` e codec/mensagens para `ut_holepunch` (`RENDEZVOUS`, `CONNECT` e `ERROR`). Só é considerada quando extensão e transporte necessários foram negociados. | `Bep55HolePunchModule`, `Bep55HolePunchAgent`, `Bep55HolePunchMessageHandlerTest`. |

### Limites de validação atuais

- O suporte a uTP e BEP 55 acima é uma camada do Luffy; não deve ser confundido com suporte nativo confirmado do `bt-core` 1.10.
- Há testes unitários/loopback para o transporte e codec, mas ainda não há uma transferência de `teste.txt` comprovada ponta a ponta entre duas máquinas em redes diferentes.
- Os registros recentes mostram peers em modo `OUTBOUND_ONLY_FIREWALLED` quando não há endpoint TCP público confirmado. Nesse estado, a runtime de descoberta continua fazendo lookup, mas o announce local é corretamente suprimido para não publicar uma porta sabidamente não confirmada.
- IPv6 é tratado separadamente para DHT. A promoção de download por IPv6 ainda está deliberadamente bloqueada em `BtTorrentGateway.promotePeerToBitTorrent(...)`; portanto não é um caminho de transferência ativo nesta fotografia.

## Fronteiras de responsabilidade existentes

```text
DHT / PEX
    -> PeerConnectivityManager (endpoint, estado, backoff e estratégia)
    -> promotePeerToBitTorrent(...)
    -> IPeerRegistry / BtRuntime
    -> handshake, metadata e pieces do bt-core
```

O código já separa descoberta de peer, tentativa de conectividade e entrega ao
motor. A auditoria não introduz o swarm global “Olá Luffy”, um protocolo paralelo
ou mudanças no fluxo de transferência.

## Fontes auditadas

- `build.gradle.kts`
- `src/main/java/dev/lufi/infrastructure/BtTorrentGateway.java`
- `src/main/java/dev/lufi/infrastructure/LuffyDhtDiscoveryModule.java`
- `src/main/java/dev/lufi/infrastructure/PeerConnectivityManager.java`
- `src/main/java/dev/lufi/infrastructure/BtConnectionLifecycleInstrumentation.java`
- `src/main/java/dev/lufi/infrastructure/UtpTransportService.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchModule.java`
- `src/main/java/dev/lufi/infrastructure/Bep55HolePunchAgent.java`
- `src/main/java/dev/lufi/infrastructure/PexObservationModule.java`
- `src/main/java/bt/peerexchange/LuffyPexObserver.java`
- `docs/protocol-compatibility.md`, `docs/utp-support.md`, `docs/bep55-hole-punching.md`, `docs/pex-support.md` e `docs/dual-stack-dht.md`

## Extensao Luffy `lf_identity` (Etapa 8)

O Luffy registra `lf_identity` como uma extensao BEP 10 propria e opcional. A
mensagem associa a conexao a um `LuffyNodeId` persistente; ela nao altera o
peer ID BitTorrent, PEX, `ut_holepunch`, metadados ou pieces.

- O codec v1 limita o payload, valida a versao, o `LuffyNodeId`, UTF-8 de
  `clientVersion` e todos os bits de capacidade. Campos ou flags desconhecidos
  sao rejeitados.
- O envio so ocorre depois que o peer anuncia `lf_identity` no extension
  handshake. Peers sem a extensao continuam no fluxo BitTorrent normal.
- O mesmo observador de handshake ja usado por BEP 55 encaminha a capacidade
  para `LuffyIdentityExtension` quando uTP esta ativo. Sem uTP, um observador
  minimo de identidade e instalado no lugar dele; os dois nao coexistem na
  mesma runtime.
- A mudanca de `LuffyNodeId` na mesma conexao e um conflito e a identidade
  deixa de ser confiavel para essa conexao.
- `supportsUtp`, `supportsHolePunch` e `supportsRendezvous` sao anunciados
  somente quando a ponte uTP/bt-core esta pronta e a familia do peer e IPv4.
  `supportsRoute` continua falso enquanto `lf_route` nao existir.
