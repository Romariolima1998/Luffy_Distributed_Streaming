#!/usr/bin/env bash
set -euo pipefail

# Cria o instalador Debian/Ubuntu a partir de uma pasta Luffy já montada.
# Separar essa etapa permite recriar apenas o .deb sem repetir a cópia pesada
# dos codecs/plugins VLC.

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${1:-$PROJECT_DIR/build/linux-vlc/Luffy}"
OUTPUT_DEB="${2:-$PROJECT_DIR/build/linux-vlc/luffy_1.0.0_amd64.deb}"
TEMPLATE_DIR="$PROJECT_DIR/src/linux/debian"

[[ -x "$SOURCE_DIR/Luffy" ]] || { echo "Lançador Linux não encontrado em: $SOURCE_DIR/Luffy" >&2; exit 1; }
[[ -d "$SOURCE_DIR/vlc/plugins" ]] || { echo "Runtime VLC integrado não encontrado em: $SOURCE_DIR/vlc" >&2; exit 1; }
command -v dpkg-deb >/dev/null || { echo "dpkg-deb não foi encontrado." >&2; exit 1; }

DEB_STAGE="$(mktemp -d)"
cleanup_deb_stage() { rm -rf "$DEB_STAGE"; }
trap cleanup_deb_stage EXIT

mkdir -p "$DEB_STAGE/DEBIAN" \
  "$DEB_STAGE/opt" \
  "$DEB_STAGE/usr/bin" \
  "$DEB_STAGE/usr/share/applications" \
  "$DEB_STAGE/usr/share/mime/packages"
cp -a "$SOURCE_DIR" "$DEB_STAGE/opt/luffy"
cp -a "$TEMPLATE_DIR/control" "$DEB_STAGE/DEBIAN/control"
cp -a "$TEMPLATE_DIR/postinst" "$DEB_STAGE/DEBIAN/postinst"
cp -a "$TEMPLATE_DIR/postrm" "$DEB_STAGE/DEBIAN/postrm"
sed 's|@LUFFY_EXECUTABLE@|/opt/luffy/Luffy|g' "$PROJECT_DIR/src/linux/luffy.desktop.template" \
  > "$DEB_STAGE/usr/share/applications/luffy.desktop"
cp -a "$PROJECT_DIR/src/linux/luffy-bittorrent.xml" \
  "$DEB_STAGE/usr/share/mime/packages/luffy-bittorrent.xml"
ln -s /opt/luffy/Luffy "$DEB_STAGE/usr/bin/luffy"

# O sistema de arquivos compartilhado pelo WSL não preserva todos os modos
# Unix. Normalizamos os modos no staging ext4 antes de criar o .deb.
find "$DEB_STAGE/opt/luffy" -type d -exec chmod 0755 {} +
find "$DEB_STAGE/opt/luffy" -type f -exec chmod 0644 {} +
chmod 0755 "$DEB_STAGE/opt/luffy/Luffy" "$DEB_STAGE/opt/luffy/install-desktop-integration.sh"
chmod 0644 "$DEB_STAGE/DEBIAN/control" "$DEB_STAGE/usr/share/applications/luffy.desktop" \
  "$DEB_STAGE/usr/share/mime/packages/luffy-bittorrent.xml"
chmod 0755 "$DEB_STAGE/DEBIAN/postinst" "$DEB_STAGE/DEBIAN/postrm"

mkdir -p "$(dirname "$OUTPUT_DEB")"
dpkg-deb --root-owner-group --build "$DEB_STAGE" "$OUTPUT_DEB"
trap - EXIT
cleanup_deb_stage

echo "Instalador Debian/Ubuntu criado em: $OUTPUT_DEB"
