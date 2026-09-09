#!/bin/bash
set -e

source "buildScript/init/env.sh"
vialen_source_root="$PWD"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
python3 "$vialen_source_root/scripts/ci/artifacts.py" prepare-sing-box "$PWD"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
python3 "$vialen_source_root/scripts/ci/artifacts.py" prepare-libneko "$PWD"
popd

####

if [ ! -d "sing-tun" ]; then
  git clone --no-checkout https://github.com/sagernet/sing-tun.git
fi
pushd sing-tun
git checkout "$COMMIT_SING_TUN"
python3 "$vialen_source_root/scripts/ci/artifacts.py" prepare-sing-tun "$PWD"
popd

####

popd
