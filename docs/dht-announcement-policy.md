# Anúncio DHT correto — Etapa 15

O Luffy separa agora duas funções que a biblioteca BitTorrent mistura por padrão:

1. descoberta de peers pela DHT;
2. anúncio deste computador como peer de um torrent.

Para anunciar um torrent, é necessário um endpoint TCP externo confirmado. A porta anunciada nunca é inferida a partir da porta local.

| Situação | Comportamento |
| --- | --- |
| TCP local `6891` mapeado e confirmado externamente como `43127` | Anuncia somente a porta pública `43127`. |
| Há observação STUN UDP, sem prova TCP | Não anuncia como peer TCP. |
| Há mapeamento UPnP/NAT-PMP/PCP, mas ainda sem teste de entrada | Mantém o endpoint apenas como observado; modo `OUTBOUND_ONLY_FIREWALLED`. |
| Sem rota pública confirmada | Mantém a descoberta DHT, mas suprime o anúncio local. |

O protocolo/biblioteca atual não fornece um campo DHT compatível para declarar um peer como `firewalled`. Por isso, no modo de saída o Luffy cria uma runtime DHT exclusiva para lookup: ela nunca inicia um torrent e, consequentemente, não emite `announce_peer`. A runtime de transferência continua podendo baixar e iniciar conexões de saída com peers encontrados.

Os logs relevantes são:

- `DHT ANNOUNCE READY`: endpoint público confirmado e porta efetivamente anunciada;
- `DHT ANNOUNCE SUPPRESSED`: conteúdo local em modo de saída;
- `DHT LOOKUP IPv4 criado`: descoberta DHT ativa, sem anúncio local.
