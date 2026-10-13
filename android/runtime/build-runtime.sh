#!/bin/sh
# Construit le runtime Linux embarque dans l'APK : android/runtime/out/rootfs.tgz
#
# S'execute en root dans un conteneur Debian 13 (trixie) arm64, le depot monte en /src :
#   docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh
set -e
RUNTIME=/src/android/runtime
OUT=$RUNTIME/out
export DEBIAN_FRONTEND=noninteractive

# 1. Bibliotheques du moteur (il fournit lui-meme SDL3, OpenAL et libomp), ecran X virtuel,
#    outils d'installation des donnees (7-Zip, unshield), outils de compilation du pont
apt-get update -q
apt-get install -y -q --no-install-recommends \
    libstdc++6 libatomic1 libgl1 libglx0 libopengl0 libgl1-mesa-dri libglx-mesa0 libegl1 \
    libvulkan1 libasound2t64 libx11-6 libxext6 libxcursor1 libxi6 libxfixes3 libxrandr2 \
    libxss1 libxtst6 xvfb x11-xkb-utils xkb-data 7zip unshield mawk \
    gcc libc6-dev libx11-dev libxtst-dev

# 2. Fichiers propres au runtime (run.sh, install-data.sh, asound.conf) et pont
#    clavier/souris/pause (LD_PRELOAD)
cp -r "$RUNTIME/rootfs-overlay/." /
chmod 755 /opt/ut/run.sh /opt/ut/install-data.sh
gcc -O2 -shared -fPIC -Wall -o /opt/ut/libutbridge.so "$RUNTIME/utbridge/utbridge.c" \
    -lX11 -lXtst -ldl -lpthread

# Mesa Turnip + Zink (GPU Adreno), compile par build-mesa.sh
if [ -f "$OUT/turnip.tgz" ]; then
    tar -C / -xzf "$OUT/turnip.tgz"
else
    echo "turnip.tgz absent : runtime sans acceleration GPU"
fi

# 3. Rootfs minimal : uniquement les fichiers utilises (fermeture des dependances)
mkdir -p "$OUT"
sh "$RUNTIME/mkmin.sh" /tmp/rootfs
echo "nameserver 1.1.1.1" > /tmp/rootfs/etc/resolv.conf

# 4. Liste des paquets Debian redistribues (licences, sources : https://sources.debian.org)
#    et licences correspondantes, dans le rootfs et a cote de l'archive
(cd /tmp/rootfs && find . -type f -o -type l) | sed 's|^\.||' | grep -v '^/opt/' \
    | xargs dpkg -S 2>/dev/null | cut -d: -f1 | tr ',' '\n' | sed 's/ //g' | sort -u > /tmp/pkgs
mkdir -p /tmp/rootfs/usr/share/doc/openut2004
xargs dpkg-query -W -f '${Package} ${Version} (source : ${source:Package} ${source:Version})\n' \
    < /tmp/pkgs > /tmp/rootfs/usr/share/doc/openut2004/packages.txt
while read -r pkg; do
    [ -f "/usr/share/doc/$pkg/copyright" ] && \
        install -D -m 644 "/usr/share/doc/$pkg/copyright" "/tmp/rootfs/usr/share/doc/$pkg/copyright"
done < /tmp/pkgs
cp /tmp/rootfs/usr/share/doc/openut2004/packages.txt "$OUT/packages.txt"

# 5. Archive (.tgz et non .tar.gz : AGP decompresse les assets .gz)
tar -C /tmp/rootfs --numeric-owner -czf "$OUT/rootfs.tgz" .
chmod 644 "$OUT/rootfs.tgz"
ls -la "$OUT/rootfs.tgz"
