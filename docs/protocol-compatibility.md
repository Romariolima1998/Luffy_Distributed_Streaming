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

## Fontes

- [Biblioteca Bt 1.10 e BEPs suportados](https://github.com/atomashpolskiy/bt)
- [BEP 10 — Extension Protocol](https://www.bittorrent.org/beps/bep_0010.html)
- [BEP 11 — Peer Exchange](https://www.bittorrent.org/beps/bep_0011.html)
- [BEP 29 — uTP](https://www.bittorrent.org/beps/bep_0029.html)
- [BEP 55 — Hole Punching](https://www.bittorrent.org/beps/bep_0055.html)
