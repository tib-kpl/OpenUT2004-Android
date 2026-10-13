#!/bin/sh
# Lance Unreal Tournament 2004 (moteur Linux arm64 du patch OldUnreal) dans le runtime Linux
# de l'appli Android : ecran X virtuel (Xvfb) dont l'image est lue par l'appli dans /tmp/fb,
# clavier/souris et pause via libutbridge.so, son via /tmp/audio.fifo (voir /etc/asound.conf).
#
# Arborescence vue par le moteur (preparee par l'appli) : /game/SystemARM64 (moteur),
# /game/System, /game/Maps, /game/Textures... (donnees du jeu) ; configuration dans
# $HOME/.ut2004/System.
#
# Variables fournies par l'appli : UT_WIDTH, UT_HEIGHT, UT_INPUT_PORT,
# UT_RENDERER (gpu : Mesa Turnip + Zink sur GPU Adreno ; sinon rendu logiciel llvmpipe)

W=${UT_WIDTH:-1280}
H=${UT_HEIGHT:-720}

mkdir -p /tmp/fb
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 /tmp/fb/Xvfb_screen0

Xvfb :0 -screen 0 "${W}x${H}x24" -fbdir /tmp/fb -nolisten tcp -ac >/tmp/xvfb.log 2>&1 &
XVFB_PID=$!
i=0
while [ ! -S /tmp/.X11-unix/X0 ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done

cd /game/SystemARM64 || exit 1
export DISPLAY=:0
# Le moteur fournit ses propres SDL3, OpenAL et libomp
export LD_LIBRARY_PATH=/game/SystemARM64
export LD_PRELOAD=/opt/ut/libutbridge.so

# Rendu GPU (Adreno) : OpenGL -> Zink -> Vulkan Turnip (interface KGSL d'Android).
# Xvfb n'a pas DRI3 : Zink passe par le chemin "drisw" (LIBGL_KOPPER_DRI2) et la
# presentation Vulkan en copie logicielle (MESA_VK_WSI_DEBUG=sw).
if [ "$UT_RENDERER" = "gpu" ] && [ -d /opt/turnip ] && [ -r /dev/kgsl-3d0 ]; then
    export LD_LIBRARY_PATH=/opt/turnip/lib:$LD_LIBRARY_PATH
    export __GLX_VENDOR_LIBRARY_NAME=mesa
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export LIBGL_KOPPER_DRI2=1
    export MESA_VK_WSI_DEBUG=sw
    export VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json
    export XDG_RUNTIME_DIR=/tmp
fi

./ut2004-bin-arm64 "$@"
STATUS=$?

kill "$XVFB_PID" 2>/dev/null
exit $STATUS
