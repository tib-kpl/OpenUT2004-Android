#!/bin/sh
# Appele par build-runtime.sh, dans le systeme Debian arm64 complet : copie dans $1
# uniquement les fichiers necessaires au runtime (binaires utilises + fermeture de leurs
# bibliotheques, donnees X11/ALSA/Mesa, /opt/ut).
set -e
OUT=${1:?dossier de sortie}
LIB=/usr/lib/aarch64-linux-gnu

rm -rf "$OUT" && mkdir -p "$OUT"

# Disposition "usrmerge" de Debian : /bin, /lib, /sbin sont des liens vers /usr
for l in bin lib sbin; do ln -s usr/$l "$OUT/$l"; done
mkdir -p "$OUT/usr/bin" "$OUT/usr/lib" "$OUT/usr/sbin" "$OUT/etc" "$OUT/tmp" "$OUT/root" \
         "$OUT/home" "$OUT/game" "$OUT/dev" "$OUT/proc" "$OUT/sys" "$OUT/storage" "$OUT/sdcard"
chmod 1777 "$OUT/tmp"

copy() { # copie un chemin en preservant l'arborescence ; pour un lien, copie aussi chaque
          # etape de la chaine (ex. /usr/bin/awk -> /etc/alternatives/awk -> /usr/bin/mawk)
    for p in "$@"; do
        [ -e "$p" ] || [ -L "$p" ] || { echo "absent: $p" >&2; continue; }
        [ -e "$OUT$p" ] || [ -L "$OUT$p" ] || cp -a --parents "$p" "$OUT/"
        if [ -L "$p" ]; then
            target=$(readlink "$p")
            case "$target" in /*) ;; *) target="$(dirname "$p")/$target" ;; esac
            copy "$target"
        fi
    done
}

# Binaires utilises par run.sh, install-data.sh et proot
BINS="/usr/bin/dash /usr/bin/sh /usr/bin/env /usr/bin/mkdir /usr/bin/rm /usr/bin/sleep \
/usr/bin/cat /usr/bin/ls /usr/bin/cp /usr/bin/mv /usr/bin/ln /usr/bin/basename /usr/bin/tail \
/usr/bin/tr /usr/bin/sed /usr/bin/mawk /usr/bin/awk /usr/bin/7z /usr/bin/unshield /usr/bin/nice /usr/bin/sync \
/usr/bin/Xvfb /usr/bin/xkbcomp"

# Bibliotheques chargees par dlopen (absentes de ldd) + celles du moteur (SDL3 et OpenAL,
# fournis par le moteur, ouvrent X11 et ALSA par dlopen)
DLOPEN="$LIB/libasound.so.2 $LIB/libstdc++.so.6 $LIB/libatomic.so.1 $LIB/libgcc_s.so.1 \
$LIB/libGL.so.1 $LIB/libGLX.so.0 $LIB/libOpenGL.so.0 $LIB/libGLX_mesa.so.0 $LIB/libGLdispatch.so.0 \
$LIB/libX11.so.6 $LIB/libXext.so.6 $LIB/libXtst.so.6 \
$LIB/libEGL.so.1 $LIB/libEGL_mesa.so.0 $LIB/libgbm.so.1 $LIB/libX11-xcb.so.1 \
$LIB/libXcursor.so.1 $LIB/libXi.so.6 $LIB/libXrandr.so.2 $LIB/libXss.so.1 $LIB/libXxf86vm.so.1 \
$LIB/libXfixes.so.3 $LIB/libxkbcommon.so.0 $LIB/libdl.so.2 $LIB/libpthread.so.0 $LIB/libm.so.6 $LIB/librt.so.1"
DRI=$(ls $LIB/dri/*.so $LIB/gbm/*.so $LIB/libgallium-*.so 2>/dev/null)
# Mesa Turnip + Zink (optionnel) et chargeur Vulkan qu'il ouvre par dlopen
TURNIP=$(ls /opt/turnip/lib/*.so* $LIB/libvulkan.so.1 2>/dev/null || true)

for f in $BINS $DLOPEN $DRI $TURNIP; do copy "$f"; done

# Fermeture des dependances (ldd), iteree jusqu'a stabilite
deps() { ldd "$1" 2>/dev/null | awk '/=>/ { print $3 } /^\t\/.*ld-linux/ { print $1 }' | grep '^/' ; }
todo="$BINS $DLOPEN $DRI $TURNIP"
seen=""
while [ -n "$todo" ]; do
    next=""
    for f in $todo; do
        for d in $(deps "$f"); do
            case " $seen " in *" $d "*) continue ;; esac
            seen="$seen $d"
            copy "$d"
            next="$next $d"
        done
    done
    todo=$next
done
copy /usr/lib/ld-linux-aarch64.so.1 2>/dev/null || true

# Donnees : clavier X, ALSA, GLVND/Mesa, locale X11
copy /usr/share/X11/xkb /usr/share/X11/locale /usr/share/alsa /usr/share/glvnd /usr/share/drirc.d \
     /usr/lib/xorg/protocol.txt /usr/share/X11/XErrorDB
copy /etc/passwd /etc/group /etc/nsswitch.conf /etc/ld.so.conf /etc/ld.so.conf.d /etc/asound.conf
# Conversions de texte (iconv) dont le moteur a besoin au demarrage : Windows-1252, UTF-16
copy $LIB/gconv/gconv-modules $LIB/gconv/gconv-modules.cache $LIB/gconv/CP1252.so \
     $LIB/gconv/UTF-16.so $LIB/gconv/UTF-32.so $LIB/gconv/UNICODE.so $LIB/gconv/ISO8859-1.so
[ -d $LIB/gconv/gconv-modules.d ] && copy $LIB/gconv/gconv-modules.d
copy /opt/ut
[ -d /usr/lib/7zip ] && copy /usr/lib/7zip
[ -d /opt/turnip ] && copy /opt/turnip/share

echo "termine : $(du -sh "$OUT" | cut -f1)"
