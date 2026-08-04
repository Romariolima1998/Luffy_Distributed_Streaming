# DHT dual-stack — Etapa 16

IPv4 DHT e IPv6 DHT são sobreposições diferentes. O Luffy não tenta transformar um peer IPv4 em IPv6, nem mistura seus endpoints.

Quando a máquina tem um IPv6 unicast global confirmado, cada lookup de `infoHash` é executado em paralelo nas duas redes:

```text
infoHash
 ├─ DHT IPv4 → peers IPv4
 └─ DHT IPv6 → peers IPv6
```

Os endpoints retornados seguem separados pelo `PeerConnectivityManager`, cuja chave inclui família IP, endereço, porta, transporte e `infoHash`.

O DHT IPv6 só é habilitado para endereços na faixa global unicast roteável. O Luffy rejeita:

- `::1` e endereços não especificados;
- link-local (`fe80::/10`);
- site-local antigo (`fec0::/10`);
- ULA (`fc00::/7`);
- multicast;
- faixa de documentação (`2001:db8::/32`).

Ter um listener IPv6 não ativa a sobreposição IPv6. É necessário um endereço global confirmado. A descoberta IPv6 já funciona em paralelo; a promoção de transferências IPv6 permanece isolada até a etapa específica do motor BitTorrent IPv6.
