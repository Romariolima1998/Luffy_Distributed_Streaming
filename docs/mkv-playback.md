# Reprodução MKV

O player do Luffy usa o JavaFX Media para MP4 e utiliza FFmpeg integrado para
containers como MKV e WebM. Assim, arquivos MKV com codecs comuns, inclusive
HEVC/H.265 e AAC quando presentes no FFmpeg distribuído, não dependem de uma
instalação separada de VLC.

O quadro mantém a proporção original e se ajusta ao espaço disponível do
player ou da tela cheia; por isso o mesmo arquivo pode ser exibido de 240p até
HD sem esticar a imagem.

O arquivo só é aberto depois que o buffer P2P inicial foi confirmado. Se o
arquivo recebido estiver corrompido, incompleto ou usar um codec que o FFmpeg
não reconheça, o Luffy mostra a falha no próprio player sem encerrar a sessão
BitTorrent.
