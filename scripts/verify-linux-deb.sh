#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_PATH="${1:-$PROJECT_DIR/build/linux-vlc/luffy_1.0.0_amd64.deb}"

[[ -f "$PACKAGE_PATH" ]] || { echo "Instalador não encontrado: $PACKAGE_PATH" >&2; exit 1; }
command -v dpkg-deb >/dev/null || { echo "dpkg-deb não foi encontrado." >&2; exit 1; }
command -v ldd >/dev/null || { echo "ldd não foi encontrado." >&2; exit 1; }

VERIFY_ROOT="$(mktemp -d)"
cleanup_verify_root() { rm -rf "$VERIFY_ROOT"; }
trap cleanup_verify_root EXIT
dpkg-deb -x "$PACKAGE_PATH" "$VERIFY_ROOT"

APP_DIR="$VERIFY_ROOT/opt/luffy"
RUNTIME_DIR="$APP_DIR/vlc"
test -x "$APP_DIR/Luffy"
test -L "$VERIFY_ROOT/usr/bin/luffy"
test -f "$VERIFY_ROOT/usr/share/applications/luffy.desktop"
test -f "$VERIFY_ROOT/usr/share/mime/packages/luffy-bittorrent.xml"
test -d "$RUNTIME_DIR/plugins"
test -f "$RUNTIME_DIR/libvlc.so.5.6.1"
test -f "$RUNTIME_DIR/libvlccore.so.9.0.1"
find "$RUNTIME_DIR/plugins" -type f -name '*_plugin.so' -print -quit | grep -q .

missing="$(LD_LIBRARY_PATH="$RUNTIME_DIR:$RUNTIME_DIR/lib" ldd "$RUNTIME_DIR/libvlc.so.5.6.1" \
  | awk '/not found/ { print }')"
[[ -z "$missing" ]] || { echo "Dependência nativa ausente: $missing" >&2; exit 1; }

echo "DEB_VLC_VERIFY=OK; package=$PACKAGE_PATH"
