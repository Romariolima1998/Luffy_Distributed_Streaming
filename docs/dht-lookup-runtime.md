# Runtime DHT exclusiva para lookup

`BtTorrentGateway.dhtLookupRuntime(boolean)` usa uma runtime exclusiva para
descoberta: ela nao cria `BtClient`, nao anuncia torrents locais e e reutilizada
por todas as consultas da mesma familia IP enquanto estiver saudavel.

`BtRuntime.builder(...).build()` apenas monta os modulos; ele nao executa o
ciclo de vida do `MldhtService`. Antes desta correcao, a runtime de lookup era
construida e usada por `DHTService.getPeers(...)` sem receber
`BtRuntime.startup()`. Como o `MldhtService` cria o `RPCServerManager` durante
o startup, esse fluxo podia chegar ao lookup com
`DHT.getServerManager() == null`.

Agora `DhtLookupRuntimeInitializer` inicia a runtime antes do primeiro lookup e
aguarda a criacao do `RPCServerManager` e de um listener UDP DHT no estado
`RUNNING`. Se a inicializacao falhar, `BtTorrentGateway` remove a referencia
compartilhada e encerra a runtime parcialmente iniciada; uma consulta posterior
pode criar outra runtime saudavel.

O criterio confirma que a DHT local foi inicializada e elimina a falha por
`RPCServerManager` nulo. Ele nao confirma a acessibilidade na Internet:
bootstrap, resposta UDP, NAT e firewall continuam sendo resultados reais de
rede, registrados separadamente pelo lookup.

## Lifecycle compartilhado

Cada familia DHT possui um `DhtLookupRuntimeLifecycle` independente e com uma
maquina de estados explicita:

```text
NEW -> STARTING -> READY -> STOPPING -> STOPPED
                  |
                  +-> FAILED -> STARTING
```

Somente a thread que troca `NEW` (ou `FAILED`) para `STARTING` cria e inicia a
runtime. As demais consultas que chegam durante `STARTING` aguardam a mesma
transicao e recebem a mesma instancia apenas depois de `READY`. Nao ha dois
`startup()` concorrentes para a mesma familia IP.

Durante o encerramento, novas consultas nao podem reiniciar a runtime. O
gateway passa ambas as familias para `STOPPED` antes de encerrar seus demais
recursos.

## Uma runtime, varias finalidades

Para IPv4 existe uma unica instancia de `DhtLookupRuntimeLifecycle` por
`BtTorrentGateway`. Toda consulta passa por `requestDhtLookup(...)`, que chama
`awaitDhtReady(false)` antes de `DHTService.getPeers(...)`. Portanto, os
seguintes usos compartilham o mesmo listener UDP e a mesma tabela DHT enquanto
ela estiver em `READY`:

- abertura de magnets;
- manutencao do swarm oficial Ola Luffy;
- verificacao de announce de um seed;
- Swarm Assist;
- demais consultas de descoberta.

Cada consulta possui seu proprio infoHash e sua propria `CompletableFuture`,
mas nenhuma delas cria outra runtime DHT. IPv6 continua separado por ser outra
rede DHT; ele nao substitui nem duplica a runtime IPv4.

## Lookup nao e announce local

O startup da runtime de lookup e necessario para criar o listener UDP e a
tabela DHT, mas nao a transforma em seeder. Ela e construida apenas com
`LuffyDhtDiscoveryModule` e nunca recebe um `BtClient`, torrent local ou evento
`TorrentStartedEvent`.

No `bt-dht`, um announce depende de o `TorrentRegistry` possuir um torrent
ativo. A prova automatizada inicia essa runtime, executa `getPeers(...)` e
verifica que o registro permanece vazio. Assim, consultas de magnet, Ola
Luffy, announce-verification e Swarm Assist somente descobrem peers nessa
runtime.

Os torrents locais continuam exclusivamente na runtime de transferencia. E ela
que recebe o `BtClient` de seed/download e o modulo DHT quando a conectividade
de entrada foi confirmada; em modo outbound-only, esse announce permanece
suprimido.

## Barreira de prontidao

O lifecycle possui uma barreira compartilhada `CompletableFuture<Void>` chamada
conceitualmente de `dhtReady`. A unica thread que entra em `STARTING` executa:

```text
BtRuntime.startup()
    -> RPCServerManager criado
    -> listener UDP DHT RUNNING
    -> state READY
    -> dhtReady.complete(null)
```

`BtTorrentGateway.requestDhtLookup(...)` chama `awaitDhtReady(...)` antes de
invocar `DHTService.getPeers(...)`. Portanto, uma consulta nunca executa
`getPeers` entre `startup()` e a disponibilidade real do `RPCServerManager`.
Falhas e encerramento completam a mesma barreira excepcionalmente, em vez de
permitir uma consulta usar uma runtime parcialmente iniciada.

## Criterio de prontidao mldht

O Luffy nao usa `Thread.sleep(...)` nem polling para supor que a DHT iniciou.
`BtRuntime.startup()` ja aguarda os lifecycle bindings assincronos do bt-core,
incluindo o binding `Initialize DHT facilities` do `MldhtService`. Ao retornar,
o inicializador valida diretamente as APIs do mldht:

- `DHT.getServerManager()` deve ser nao nulo;
- `RPCServerManager.getAllServers()` deve conter pelo menos um `RPCServer` no
  estado `RUNNING`.

Somente depois dessas duas evidencias a barreira `dhtReady` e completada e o
lookup pode chamar `getPeers(...)`. A confirmacao de rota UDP externa continua
fora desse criterio: ela e uma propriedade da rede, nao da inicializacao local.

## Logs da runtime de lookup

Os logs do lifecycle usam o estado real validado pelo
`DhtLookupRuntimeInitializer`. Para uma runtime IPv4 saudavel, a sequencia e:

```text
[DHT] LOOKUP RUNTIME STARTING: family=IPv4; udpLocalPort=49001; mode=DISCOVERY_ONLY.
[DHT] RPC SERVER STARTED: family=IPv4; runningServers=1.
[DHT] LOOKUP RUNTIME READY: family=IPv4; mode=DISCOVERY_ONLY.
[DHT] bootstrap started: family=IPv4; source=bt-dht.
[DHT] nodes known=0; family=IPv4.
[DHT] LOOKUP START: infoHash=<infoHash>; family=IPv4; purpose=<purpose>.
[DHT] PEER DISCOVERED: infoHash=<infoHash>; endpoint=<ip:porta>; source=DHT.
```

`nodes known=0` e valido imediatamente apos o listener iniciar, antes de o
bootstrap preencher a tabela. Em contrapartida, `nodes known=-1` nao e mais
um valor sentinela aceito: READY exige pelo menos um `RPCServer` em `RUNNING`
e um contador de nos nao negativo. Se essa evidencia nao existir, a runtime
falha antes de qualquer chamada a `getPeers(...)`.

## Provas de lifecycle

Os testes locais cobrem dez consultas concorrentes para a mesma familia IPv4:
elas compartilham uma runtime e um unico startup, e o trabalho equivalente a
`getPeers(...)` permanece bloqueado enquanto o estado e `STARTING`. Tambem
cobrem falha de startup, backoff, recuperacao, rejeicao durante `STOPPING` e a
regressao real do mldht: o teste confirma um `RPCServer` `RUNNING` no instante
imediatamente anterior a `DHTService.getPeers(...)`.

Uma sondagem de bootstrap pelo `BtTorrentGateway` real validou a ordem dos
logs e demonstrou que uma tabela nova pode ir de `nodes known=0` para uma
tabela preenchida pelo bootstrap. Essa sondagem usa apenas descoberta DHT e
nao cria sessao de download nem solicita pieces.

## Timeout de startup

`DhtLookupRuntimeSettings` fornece `dhtStartupTimeout`, configuravel por
`BtTorrentGateway.setDhtLookupRuntimeSettings(...)`. O valor padrao e 15
segundos. Esse valor e aplicado ao `Config` exclusivo da runtime de lookup;
apesar do nome `shutdownHookTimeout` no bt-core, ele limita a espera que
`BtRuntime.startup()` faz pelos lifecycle bindings, inclusive o `MldhtService`.

Se a runtime nao apresentar um `RPCServerManager` e um servidor `RUNNING` ao
fim desse prazo, a inicializacao falha, a barreira `dhtReady` fica excepcional
e o lifecycle muda de `STARTING` para `FAILED`. O diagnostico registra:

```text
[DHT] startup failed reason=RPC server did not become ready; timeout=15s
```

Consultas futuras nao chamam `getPeers(...)` nessa instancia invalida. Elas
recebem a falha da barreira; uma nova tentativa somente cria outra runtime pelo
fluxo controlado do lifecycle.

## Falha e recuperacao

Ao falhar, `DhtLookupRuntimeLifecycle` encerra a runtime parcialmente iniciada,
remove sua referencia e conclui a barreira com erro. O estado fica em `FAILED`;
ela nunca volta a ser usada.

`DhtLookupRuntimeSettings.dhtRetryBackoff` e configuravel, precisa ser positivo
e vale 5 segundos por padrao. Durante esse intervalo, novas consultas recebem a falha de backoff
e nao criam outra runtime. Depois do prazo, a proxima consulta inicia uma nova
instancia, seguindo:

```text
FAILED -> backoff -> nova runtime -> STARTING -> READY ou FAILED
```

Esse limite evita loops rapidos de criacao, bind de porta e shutdown quando a
rede ou a inicializacao local esta indisponivel.

## Shutdown

`BtTorrentGateway.close()` marca o gateway como em encerramento antes de fechar
qualquer outro componente. A partir desse ponto, nenhum novo lookup e iniciado.
As consultas DHT pendentes sao completadas com erro de cancelamento e suas
threads virtuais recebem interrupcao; em seguida, as runtimes IPv4 e IPv6
seguem o fluxo:

```text
STOPPING -> runtime.shutdown() -> STOPPED
```

Quando o startup ainda esta em andamento, `dhtReady` e cancelada logo no inicio
do shutdown para liberar quem aguardava. O `STOPPED` so e publicado depois de a
runtime parcialmente iniciada terminar o seu shutdown, evitando uma runtime
assincrona sobrevivente ou um novo lookup durante o fechamento.

## Bootstrap IPv4 resiliente

O `bt-dht` 1.10 traz tres routers publicos por padrao. Eles continuam ativos,
mas uma rede pode nao obter resposta UDP de nenhum deles em determinado momento.
`DhtBootstrapNodes` acrescenta `dht.libtorrent.org:25401` somente como quarto
bootstrap IPv4. Ele nao e tracker, relay nem servidor do Luffy: fornece apenas
um primeiro no para a tabela Kademlia poder encontrar os demais nos da DHT.

O fallback e configurado pelo `DHTConfig` da mesma runtime e somente para IPv4.
O caminho IPv6 continua independente e nao reutiliza um endereco IPv4.

## Magnets com trackers repetidos

Um magnet pode conter varios parametros `tr`. `MagnetLink` agora conserva a
lista completa na ordem recebida e `toUri()` a transmite novamente ao bt-core.
Assim, DHT e todos os trackers informados pelo magnet continuam fontes de
descoberta complementares. Uma sessao que usa `SKIP` para todos os arquivos
pode obter peers e metadata sem solicitar pieces.
