# Luffy

## baixe o luffy e execulte sem compilar

e necessario baixar o vlc 3.0.x antes de rodar o luffy : 
[site oficial do vlc](https://www.videolan.org/)



[download para windows](https://github.com/Romariolima1998/Luffy_Distributed_Streaming/releases/download/1.0/Luffy-1.0.exe)

requizito para linux

```
 sudo apt update && sudo apt install openjdk-21-jdk -y
```
[download para linux](https://github.com/Romariolima1998/Luffy_Distributed_Streaming/releases/download/0.1.0/Luffy-0.1.0-linux.zip)



Luffy é uma plataforma de streaming e compartilhamento de vídeos totalmente distribuída (P2P), desenvolvida em Java 21 sobre o protocolo BitTorrent. Seu objetivo é permitir que usuários assistam e compartilhem vídeos diretamente entre si, sem depender de servidores centrais para armazenar ou distribuir o conteúdo.

O projeto utiliza DHT, PEX, uTP e técnicas de travessia de NAT (BEP 55), além de uma camada própria de descoberta e rendezvous para aumentar a conectividade entre peers, buscando uma rede mais escalável, resiliente e descentralizada.

<img alt="Captura de tela 2026-08-04 153810.png" data-hpc="true" containertiming="hpc" src="https://github.com/Romariolima1998/Luffy_Distributed_Streaming/blob/main/imagens/Captura%20de%20tela%202026-08-04%20153810.png?raw=true" style="max-width: 100%;">


## Pré-requisitos e execução caso voce queira compilar

- JDK 21 (não JRE 8)
- Gradle 8.5+ ou um wrapper Gradle gerado localmente
- FFmpeg no `PATH` para miniaturas e perfis de vídeo futuros

```
powershell
gradle run
gradle test
```

Os dados ficam em ~/.lufi por padrão. Na primeira abertura, escolha o limite do cache. O botão **Abrir magnet** aceita magnet:?xt=urn:btih:...`; no MVP ele cria uma sessão de streaming e deixa explícito quando ainda não há motor P2P conectado.

## Decisões de arquitetura

- **Portas e adaptadores:** domain contém regras puras; `application` coordena casos de uso; `infrastructure` é substituível. `TorrentGateway` é a porta para uma implementação BitTorrent futura (por exemplo, jlibtorrent em processo isolado).
- **Streaming por peças:** `StreamingSession` expõe o estado de buffer e a política `PieceScheduler`; o adaptador BitTorrent deverá pedir primeiro a janela à frente do playhead, com rarest-first fora dessa janela.
- **Segurança:** magnets são dados não confiáveis; o parser só aceita BTIH hexadecimal de 40 caracteres. Uma versão produtiva ainda deve validar metadados/tamanhos e limitar trackers.
- **Cache:** `CachePolicy` decide LRU por último acesso, nunca apaga arquivos originários da biblioteca. A persistência é local SQLite.

## Limites conscientes do MVP

### Estado atual da rede P2P

`BtTorrentGateway` agora inicia sessões BitTorrent reais por magnet, com DHT, descoberta local e seleção sequencial de peças. Ao publicar um vídeo, o cliente local passa a semeá-lo enquanto o Lufi estiver aberto. Em **Assistir e compartilhar**, o downloader continua como seed após terminar; em **Assistir apenas**, para ao concluir.

O player JavaFX ainda precisa de um backend de leitura progressiva/HTTP local para começar antes do arquivo estar completo. Em redes com NAT restritivo, o encaminhamento da porta BitTorrent 6891 no roteador pode ser necessário para receber conexões de entrada.

O motor verifica as peças via protocolo BitTorrent e mantém sessões de seed ativas. A próxima etapa é expor a leitura progressiva dessas peças ao player, sem esperar o arquivo inteiro.
# Luffy_Distributed_Streaming

Copyright (c) 2026 Romário G Lima

All Rights Reserved.

This source code is the exclusive property of the copyright holder.

No permission is granted to use, copy, modify, merge, publish,
distribute, sublicense, or create derivative works from this software,
in whole or in part, without prior written permission from the copyright holder.
