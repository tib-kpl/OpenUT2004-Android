#!/bin/sh
# Compile Mesa (Vulkan Turnip pour GPU Adreno via l'interface KGSL d'Android + OpenGL Zink)
# pour arm64, dans un conteneur Debian 13 :
# - sur une machine arm64 (runners GitHub ubuntu-24.04-arm) : compilation native
#     docker run --rm -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-mesa.sh
# - sur une machine x86_64 : compilation croisee (meme commande)
# Resultat : android/runtime/out/turnip.tgz (contenu de /opt/turnip), utilise par
# build-runtime.sh s'il est present.
set -e
MESA_VERSION=25.0.7
OUT=/src/android/runtime/out
export DEBIAN_FRONTEND=noninteractive

DEV_PKGS="libdrm-dev libx11-dev libxext-dev libxfixes-dev libxcb-glx0-dev libxcb-shm0-dev \
libx11-xcb-dev libxcb-dri2-0-dev libxcb-dri3-dev libxcb-present-dev libxcb-randr0-dev \
libxcb-sync-dev libxcb-xfixes0-dev libxshmfence-dev libxxf86vm-dev libxrandr-dev libexpat1-dev \
zlib1g-dev libzstd-dev libglvnd-dev"
TOOLS="binutils meson ninja-build python3-mako python3-yaml python3-packaging glslang-tools \
bison flex pkg-config wget xz-utils ca-certificates"

if [ "$(uname -m)" = "aarch64" ]; then
    apt-get update -q
    apt-get install -y -q --no-install-recommends build-essential $TOOLS $DEV_PKGS
    CROSS=""
else
    dpkg --add-architecture arm64
    apt-get update -q
    apt-get install -y -q --no-install-recommends crossbuild-essential-arm64 $TOOLS \
        $(for p in $DEV_PKGS; do printf '%s:arm64 ' "$p"; done)
    cat > /tmp/cross-arm64.txt <<'EOF'
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
nm = 'aarch64-linux-gnu-nm'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = ['/usr/lib/aarch64-linux-gnu/pkgconfig', '/usr/share/pkgconfig']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
    CROSS="--cross-file /tmp/cross-arm64.txt"
fi

cd /tmp
wget -q "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
tar xJf "mesa-$MESA_VERSION.tar.xz"
cd "mesa-$MESA_VERSION"

# - zink + Turnip (kgsl) : OpenGL accelere sur Adreno sous Android
# - freedreno (Gallium) : necessaire pour que Mesa 25.0 compile le code DRI2 avec ce jeu d'options
# - sans LLVM ni GBM (pas d'affichage DRM/KMS)
meson setup build $CROSS --prefix=/opt/turnip --libdir=lib \
    -Dbuildtype=release -Dstrip=true \
    -Dplatforms=x11 -Dgallium-drivers=zink,freedreno -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=msm,kgsl -Dglx=dri -Degl=enabled -Dgbm=disabled -Dglvnd=enabled \
    -Dllvm=disabled -Dgles1=disabled -Dgles2=enabled -Dvalgrind=disabled -Dlibunwind=disabled \
    -Dlmsensors=disabled -Dvideo-codecs= -Dbuild-tests=false -Dgallium-xa=disabled
ninja -C build
DESTDIR=/tmp/stage ninja -C build install

mkdir -p "$OUT"
tar -C /tmp/stage --numeric-owner -czf "$OUT/turnip.tgz" opt/turnip
ls -la "$OUT/turnip.tgz"
