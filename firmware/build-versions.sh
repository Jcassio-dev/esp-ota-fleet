#!/usr/bin/env bash
# Gera três versões do firmware em dist/ para demonstrar atualização e rollback:
#   1.0.0  gravada pela USB na placa
#   1.1.0  atualização boa (o LED passa de 1 para 2 piscadas)
#   1.2.0  quebrada de propósito: trava no autoteste e o bootloader volta para 1.1.0
#
# Requer ESP-IDF v5.x no ambiente (. $IDF_PATH/export.sh) e sdkconfig.local preenchido.
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f sdkconfig.local ]]; then
    echo "Crie firmware/sdkconfig.local a partir de sdkconfig.local.example (Wi-Fi e URL do servidor)." >&2
    exit 1
fi

mkdir -p dist

build() {
    local version=$1 extra=${2:-}
    local dir="$PWD/build-$version"
    local defaults="sdkconfig.defaults;sdkconfig.local${extra:+;$extra}"
    echo "==> Versão $version"
    idf.py -B "$dir" -DPROJECT_VER="$version" -DSDKCONFIG="$dir/sdkconfig" -DSDKCONFIG_DEFAULTS="$defaults" build
    cp "$dir/ota-fleet-device.bin" "dist/firmware-$version.bin"
}

build 1.0.0
build 1.1.0
build 1.2.0 sdkconfig.broken

cat <<EOF

Pronto. Próximos passos:
  idf.py -B build-1.0.0 -p /dev/ttyUSB0 flash monitor      # grava a 1.0.0 na placa
  node cli/src/cli.js publish firmware/dist/firmware-1.1.0.bin --key keys/private.pem --rollout 100
  node cli/src/cli.js publish firmware/dist/firmware-1.2.0.bin --key keys/private.pem --rollout 100
EOF
