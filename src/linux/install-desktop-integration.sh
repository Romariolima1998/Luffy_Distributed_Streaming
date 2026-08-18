#!/usr/bin/env sh
set -eu

# Instala somente a capacidade de abrir magnet e .torrent para o usuário atual.
# Não altera o aplicativo padrão: essa escolha continua nas configurações do
# navegador/ambiente gráfico ou no diálogo "Abrir com".
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LAUNCHER="$BASE_DIR/Luffy"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APPLICATIONS_DIR="$DATA_HOME/applications"
MIME_PACKAGES_DIR="$DATA_HOME/mime/packages"
DESKTOP_FILE="$APPLICATIONS_DIR/luffy.desktop"

if [ ! -x "$LAUNCHER" ]; then
  echo "O lançador Luffy não foi encontrado ou não pode ser executado: $LAUNCHER" >&2
  exit 1
fi

mkdir -p "$APPLICATIONS_DIR" "$MIME_PACKAGES_DIR"
escaped_launcher=$(printf '%s' "$LAUNCHER" | sed 's/[&|]/\\&/g')
sed "s|@LUFFY_EXECUTABLE@|$escaped_launcher|g" "$BASE_DIR/luffy.desktop.template" > "$DESKTOP_FILE"
cp "$BASE_DIR/luffy-bittorrent.xml" "$MIME_PACKAGES_DIR/luffy-bittorrent.xml"

command -v update-mime-database >/dev/null 2>&1 && update-mime-database "$DATA_HOME/mime" || true
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPLICATIONS_DIR" || true

echo "Luffy foi registrado como opção para links magnet e arquivos .torrent."
echo "Nenhum aplicativo padrão foi alterado. Use 'Abrir com' ou as configurações do seu navegador/desktop para escolhê-lo."
