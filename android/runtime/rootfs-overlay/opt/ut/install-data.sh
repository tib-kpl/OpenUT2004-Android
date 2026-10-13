#!/bin/sh
# Installe les donnees d'Unreal Tournament 2004 a partir de l'image ISO du DVD, comme
# l'installeur Linux officiel d'OldUnreal (github.com/OldUnreal/FullGameInstallers) :
#   1. extraction des archives InstallShield (dossiers Disk*) de l'ISO (7-Zip)
#   2. extraction de leur contenu (unshield, data1.cab)
#   3. rangement des dossiers dans la destination
# Le patch OldUnreal (moteur arm64) est ensuite applique par l'appli.
#
# Usage : install-data.sh <fichier ISO> <dossier de destination> <dossier de travail>
#   Le dossier de travail (memoire interne de l'appli) recoit les archives extraites de l'ISO :
#   ecrire 3 Go a pleine vitesse sur une carte SD (stockage partage, FUSE) sature la memoire et
#   Android tue alors l'appli. Les ecritures vers la destination sont regulierement videes
#   sur le disque (sync) pour la meme raison.
# Progression sur la sortie standard : lignes "STEP <n> <message>" et "PROGRESS <pourcentage>".
set -e
ISO=$1
DEST=$2
WORK=$3
DATA="$DEST/Installer/.staging/Data"

[ -f "$ISO" ] || { echo "ERROR ISO introuvable : $ISO"; exit 2; }
[ -n "$WORK" ] || { echo "ERROR Dossier de travail manquant"; exit 2; }
mkdir -p "$WORK" "$DEST"
# Pas d'indexation multimedia des milliers de fichiers du jeu (musiques, sons...)
: > "$DEST/.nomedia"

# Vide regulierement les ecritures en attente tant que l'installation tourne
(while kill -0 $$ 2>/dev/null; do sync; sleep 1; done) &
SYNC_PID=$!
trap 'kill $SYNC_PID 2>/dev/null' EXIT

echo "STEP 1 Extraction de l'image du DVD"
rm -rf "$WORK/Disk"*
nice -n 10 7z x -y -bsp1 -bso0 -o"$WORK" "$ISO" 'Disk*' | tr '\r\b' '\n\n' \
    | sed -n 's/^ *\([0-9][0-9]*\)%.*/PROGRESS \1/p'
[ -f "$WORK/Disk1/data1.cab" ] || { echo "ERROR Extraction de l'ISO incomplete"; exit 3; }

echo "STEP 2 Extraction des archives du jeu"
mkdir -p "$WORK/Cabs"
for disk in "$WORK"/Disk*; do
    [ -d "$disk" ] || continue
    for f in "$disk"/*.cab "$disk"/*.hdr; do
        [ -f "$f" ] && mv -f "$f" "$WORK/Cabs/$(basename "$f")"
    done
done
rm -rf "$WORK"/Disk* "$DEST/Installer/.staging"
mkdir -p "$DATA"
total=$(unshield -d "$DATA" l "$WORK/Cabs/data1.cab" | tail -n 1 | awk '{print $1}')
nice -n 10 unshield -d "$DATA" x "$WORK/Cabs/data1.cab" | awk -v total="${total:-1}" \
    '/extracting:/ { n++; if (n % 50 == 0) { printf "PROGRESS %d\n", n * 100 / total; fflush() } }'
[ -d "$DATA/All_Maps" ] || { echo "ERROR Extraction des archives incomplete"; exit 4; }
rm -rf "$WORK"

echo "STEP 3 Installation des fichiers"
rm -f "$DATA/All_UT2004.EXE/"*.exe "$DATA/English_Sounds_Speech_System_Help/System/"*.bat \
      "$DATA/English_Sounds_Speech_System_Help/System/"*.dll \
      "$DATA/English_Sounds_Speech_System_Help/System/"*.exe 2>/dev/null || true
move() { # move <dossier source> <cible relative> : deplace le contenu (rapide, sans copie)
    [ -d "$DATA/$1" ] || return 0
    mkdir -p "$DEST/$2"
    for f in "$DATA/$1"/* "$DATA/$1"/.[!.]*; do
        [ -e "$f" ] || continue
        name=$(basename "$f")
        if [ -d "$f" ] && [ -d "$DEST/$2/$name" ]; then
            cp -rf "$f/." "$DEST/$2/$name/" && rm -rf "$f"
        else
            rm -rf "$DEST/$2/$name"
            mv -f "$f" "$DEST/$2/$name"
        fi
    done
}
move All_Animations Animations
move All_Benchmark Benchmark
move All_ForceFeedback ForceFeedback
move All_Help Help
move All_KarmaData KarmaData
move All_Maps Maps
move All_Music Music
move All_StaticMeshes StaticMeshes
move All_Textures Textures
move All_Web Web
move All_UT2004.EXE System
move English_Manual Manual
move English_Sounds_Speech_System_Help .
move US_License.int System
rm -rf "$DEST/Installer/.staging"
sync

echo "STEP 4 Termine"
