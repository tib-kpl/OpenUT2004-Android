#!/bin/sh
# Installe les donnees d'Unreal Tournament 2004 a partir de l'image ISO du DVD, comme
# l'installeur Linux officiel d'OldUnreal (github.com/OldUnreal/FullGameInstallers) :
#   1. extraction des dossiers Disk* de l'ISO (7-Zip)
#   2. extraction des archives InstallShield (unshield, data1.cab)
#   3. rangement des dossiers dans la destination
# Le patch OldUnreal (moteur arm64) est ensuite applique par l'appli.
#
# Usage : install-data.sh <fichier ISO> <dossier de destination>
# Progression sur la sortie standard : lignes "STEP <n> <message>" et "PROGRESS <pourcentage>".
set -e
ISO=$1
DEST=$2
STAGE="$DEST/Installer/.staging"

[ -f "$ISO" ] || { echo "ERROR ISO introuvable : $ISO"; exit 2; }
mkdir -p "$STAGE"

echo "STEP 1 Extraction de l'image du DVD"
7z x -y -bsp1 -bso0 -o"$STAGE" "$ISO" 'Disk*' | tr '\r\b' '\n\n' \
    | sed -n 's/^ *\([0-9][0-9]*\)%.*/PROGRESS \1/p'

[ -f "$STAGE/Disk1/data1.cab" ] || { echo "ERROR Extraction de l'ISO incomplete"; exit 3; }

echo "STEP 2 Extraction des archives du jeu"
mkdir -p "$STAGE/Cabs" "$STAGE/Data"
for disk in "$STAGE"/Disk*; do
    [ -d "$disk" ] || continue
    for f in "$disk"/*.cab "$disk"/*.hdr; do
        # deplacement (pas de lien : les cartes SD n'en acceptent pas)
        [ -f "$f" ] && mv -f "$f" "$STAGE/Cabs/$(basename "$f")"
    done
done
total=$(unshield -d "$STAGE/Data" l "$STAGE/Cabs/data1.cab" | tail -n 1 | awk '{print $1}')
unshield -d "$STAGE/Data" x "$STAGE/Cabs/data1.cab" | awk -v total="${total:-1}" \
    '/extracting:/ { n++; if (n % 50 == 0) { printf "PROGRESS %d\n", n * 100 / total; fflush() } }'
[ -d "$STAGE/Data/All_Maps" ] || { echo "ERROR Extraction des archives incomplete"; exit 4; }
rm -rf "$STAGE"/Disk* "$STAGE/Cabs"

echo "STEP 3 Installation des fichiers"
DATA="$STAGE/Data"
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

echo "STEP 4 Termine"
