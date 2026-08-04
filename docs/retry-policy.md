# Controle de retries — Etapa 14

O Luffy trata descoberta e tentativa de conexão como coisas distintas. Um evento repetido da DHT, PEX ou outra fonte atualiza apenas a origem e a última vez em que o endpoint foi visto; ele não abre outro socket enquanto o endpoint estiver em conexão ou em espera.

Cada endpoint possui uma chave de deduplicação independente:

`infoHash + família IP + IP + porta + transporte`

Na prática a chave registra `infoHash|IPV4/IPV6|TCP/uTP|IP:porta`. Assim, os caminhos TCP e uTP, ou IPv4 e IPv6, não são misturados.

Após cada falha daquele endpoint, a próxima tentativa automática usa backoff:

| Falha | Espera |
| --- | --- |
| 1ª | 5 segundos |
| 2ª | 15 segundos |
| 3ª | 30 segundos |
| 4ª | 2 minutos |
| 5ª | endpoint marcado como não alcançável |

O log mostra `PEER RETRY BACKOFF` quando a espera é criada e `PEER RETRY SUPPRESSED` quando uma descoberta repetida chega durante essa espera. Uma nova tentativa explícita do usuário zera apenas o controle daquele `infoHash`.
