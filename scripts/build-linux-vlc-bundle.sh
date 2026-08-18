#!/usr/bin/env bash
set -euo pipefail

# Gera um pacote Linux x86_64 autocontido do Luffy com libVLC 3.x e plugins.
# O script é executado em Ubuntu 24.04 (ou derivado compatível) para que os
# binários e bibliotecas copiados sejam coerentes entre si.

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_ROOT="$PROJECT_DIR/build/linux-vlc"
DIST_DIR="$DIST_ROOT/Luffy"
RUNTIME_DIR="$DIST_DIR/vlc"
JAR_PATH="$PROJECT_DIR/build/linux/artifacts/Luffy-0.1.0-linux.jar"
DEB_OUTPUT="$DIST_ROOT/luffy_1.0.0_amd64.deb"

install_packages() {
  local runner=()
  if [[ "${EUID}" -ne 0 ]]; then
    runner=(sudo)
  fi
  "${runner[@]}" apt-get update
  DEBIAN_FRONTEND=noninteractive "${runner[@]}" apt-get install -y openjdk-21-jdk vlc libvlc-bin
}

if [[ "${1:-}" != "--skip-system-install" ]]; then
  install_packages
fi

command -v java >/dev/null || { echo "Java não foi encontrado." >&2; exit 1; }
command -v ldd >/dev/null || { echo "ldd não foi encontrado." >&2; exit 1; }

bash "$PROJECT_DIR/gradlew" --no-daemon linuxExecutableJar
[[ -f "$JAR_PATH" ]] || { echo "JAR Linux não foi gerado: $JAR_PATH" >&2; exit 1; }

if [[ -e "$DIST_ROOT" ]]; then
  previous_root="$PROJECT_DIR/build/linux-vlc-previous-$(date +%Y%m%d-%H%M%S)"
  mv "$DIST_ROOT" "$previous_root"
  echo "A saída Linux anterior foi preservada em: $previous_root"
fi
mkdir -p "$RUNTIME_DIR/lib"
cp -a "$PROJECT_DIR/src/linux/." "$DIST_DIR/"
cp -a "$JAR_PATH" "$DIST_DIR/Luffy-0.1.0-linux.jar"
chmod +x "$DIST_DIR/Luffy" "$DIST_DIR/install-desktop-integration.sh"

LIBVLC_PATH="$(ldconfig -p | awk '/libvlc\.so\.5/{ print $NF; exit }')"
LIBVLCCORE_PATH="$(ldconfig -p | awk '/libvlccore\.so\./{ print $NF; exit }')"
[[ -n "$LIBVLC_PATH" && -f "$LIBVLC_PATH" ]] || { echo "libvlc 3.x não foi encontrado após instalar VLC." >&2; exit 1; }
[[ -n "$LIBVLCCORE_PATH" && -f "$LIBVLCCORE_PATH" ]] || { echo "libvlccore não foi encontrado após instalar VLC." >&2; exit 1; }

VLC_LIBRARY_DIR="$(dirname "$LIBVLC_PATH")"
PLUGIN_SOURCE="$(find /usr/lib -type d -path '*/vlc/plugins' -print -quit)"
[[ -n "$PLUGIN_SOURCE" && -d "$PLUGIN_SOURCE" ]] || { echo "Pasta de plugins do VLC não encontrada." >&2; exit 1; }

# Copia os links e a implementação real: o carregador busca os nomes SONAME.
cp -a "$VLC_LIBRARY_DIR"/libvlc.so* "$RUNTIME_DIR/"
cp -a "$(dirname "$LIBVLCCORE_PATH")"/libvlccore.so* "$RUNTIME_DIR/"
cp -a "$PLUGIN_SOURCE" "$RUNTIME_DIR/plugins"

is_host_runtime() {
  case "$(basename "$1")" in
    libc.so.*|libm.so.*|libdl.so.*|libpthread.so.*|librt.so.*|libresolv.so.*|libnsl.so.*|ld-linux-*.so.*) return 0 ;;
  esac
  return 1
}

copy_dependency() {
  local dependency="$1"
  local destination="$RUNTIME_DIR/lib/${dependency##*/}"
  [[ -f "$dependency" ]] || return 0
  is_host_runtime "$dependency" && return 0
  [[ -e "$destination" ]] && return 0
  cp -aL "$dependency" "$destination"
  return 0
}

# ldd já devolve a árvore carregada inteira para cada plugin. Portanto uma
# varredura única de libVLC/libvlccore e dos plugins de origem basta; varrer de
# novo as bibliotecas já copiadas multiplicava o tempo do build no WSL1 sem
# acrescentar cobertura de dependências.
mapfile -d '' -t native_binaries < <(
  find "$RUNTIME_DIR" -type f \( -name '*.so' -o -name '*.so.*' \) -print0
)
for binary in "${native_binaries[@]}"; do
  while IFS= read -r dependency; do
    copy_dependency "$dependency"
  done < <(ldd "$binary" 2>/dev/null | awk '/=> \/[^ ]+/ { print $3 } /^\// { print $1 }')
done

CACHE_GENERATOR="$(find /usr/lib -type f -name 'vlc-cache-gen' -print -quit)"
[[ -n "$CACHE_GENERATOR" && -x "$CACHE_GENERATOR" ]] || { echo "vlc-cache-gen não foi encontrado." >&2; exit 1; }
LD_LIBRARY_PATH="$RUNTIME_DIR:$RUNTIME_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  VLC_PLUGIN_PATH="$RUNTIME_DIR/plugins" "$CACHE_GENERATOR" "$RUNTIME_DIR/plugins"

for copyright in /usr/share/doc/vlc/copyright /usr/share/doc/libvlc5/copyright; do
  [[ -f "$copyright" ]] && cp -a "$copyright" "$RUNTIME_DIR/$(basename "$(dirname "$copyright")")-LICENSE.txt"
done

[[ -d "$RUNTIME_DIR/plugins" ]] || { echo "Plugins VLC não foram empacotados." >&2; exit 1; }
find "$RUNTIME_DIR/plugins" -type f -name '*_plugin.so' -print -quit | grep -q . || {
  echo "Nenhum plugin VLC foi encontrado no pacote." >&2
  exit 1
}
tar -C "$DIST_ROOT" -czf "$DIST_ROOT/Luffy-0.1.0-linux-x64-vlc.tar.gz" Luffy

bash "$PROJECT_DIR/scripts/build-linux-deb.sh" "$DIST_DIR" "$DEB_OUTPUT"

echo "Pacote Linux com VLC integrado criado em: $DIST_ROOT/Luffy-0.1.0-linux-x64-vlc.tar.gz"
echo "Instalador Debian/Ubuntu criado em: $DEB_OUTPUT"
