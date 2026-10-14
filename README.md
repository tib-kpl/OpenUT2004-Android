# UT2004-Android

**Unofficial** Android launcher for **Unreal Tournament 2004** on ARM64 Android handhelds and
phones, running the native Linux ARM64 engine of the community
[OldUnreal UT2004 patch](https://github.com/OldUnreal/UT2004Patches).

> [!IMPORTANT]
> Unreal Tournament 2004 contains **no game files and no game engine**. On first launch it downloads the
> OldUnreal patch (engine) from its official GitHub releases. For the game data, you can
> either point it to an existing installation (PC, DVD, GOG…), or let it download the game:
> Epic Games allows Unreal Tournament 2004 to be distributed for free, and Unreal Tournament 2004 fetches
> the DVD image from the same sources as OldUnreal's official installer (OldUnreal servers,
> with the Internet Archive as fallback).

## Features

- **Download the game** to the folder of your choice (internal storage or SD card), or
  **use an existing installation**. The location is remembered.
- Native ARM64 engine (OldUnreal patch 3374) running in a small embedded Linux environment:
  no emulation.
- GPU rendering on Adreno (Snapdragon) devices through Mesa Turnip + Zink, with automatic
  fallback to software rendering elsewhere. Resolution: 720p (smooth) or 1080p (sharp), in
  the screen's aspect ratio (16:9…).
- Built-in or Bluetooth gamepad (translated to keyboard and mouse, so it works in menus
  too), physical keyboard.
- Sound. The game pauses and goes silent when the app is in the background or the screen is
  locked.

Tested on the **AYN Odin 2 Portal** (Snapdragon 8 Gen 2, Android 13).

## Installation

1. Download `UT2004-x.y.z.apk` from the [Releases](../../releases) page and install it.
2. Start **Unreal Tournament 2004** and grant **all files access**.
3. Either:
   - **Download the game (≈ 3 GB)**: pick a destination folder; an `UT2004` folder is
     created there. About 9 GB of free space are needed during installation (≈ 6 GB
     afterwards). The download continues with the screen off and resumes if interrupted;
   - or **I already have the game**: pick the folder of an existing UT2004 installation
     (the one containing `System`, `Maps`, `Textures`…).
4. On first launch, Unreal Tournament 2004 installs its Linux runtime and downloads the OldUnreal patch.
   An Internet connection is required for that first launch only.

To change the game folder later: **long-press the icon → "Changer le dossier du jeu"**.

### Gamepad controls

The gamepad is translated to keyboard and mouse controls. It starts in **Menus** mode.
The mode follows the engine's GUI state. **Select** (or the small mode button) remains a
manual override until the next GUI transition. The centered button displays Mouse ON/OFF
in the selected game language, with a compact translucent button and small text.

In Menus mode, the right stick moves the cursor more slowly, R2 clicks, A sends Enter,
B goes back, X sends Tab to focus the next control, and the D-pad moves between controls
(down: next, up: previous); left/right change the selected value. The left stick sends arrow keys to change the
selected list or value. Select a text field, then press Y to enter text with
the Android keyboard (the text is appended to the selected field). Touch input also
positions and clicks the mouse in Menus mode.

The settings gear pauses the current session. **Retour au jeu** resumes it without
restarting the engine. Graphics changes apply on the next launch; the settings provide
automatic GPU selection, integrated Vulkan Turnip, software rendering for troubleshooting,
540p/720p/1080p, and an optional counter that measures engine frames rather than screen refreshes.
Toggle it in **Graphismes > Afficher les FPS du moteur**; the visibility changes immediately
when returning to the running session.

In Game mode:

| Button | Action |
|---|---|
| Left stick | Move (W/A/S/D) |
| Right stick | Aim / move the menu cursor (mouse) |
| R2 | Fire (left click, also clicks in menus) |
| L2 | Alt fire (right click) |
| A | Jump |
| B | Escape / menu |
| X | Use |
| Y | Translocator |
| L1 / R1 | Previous / next weapon |
| Start | Menu |
| Select | Switch Menus / Game mode |
| D-pad | Arrow keys (menus) |
| L3 | Crouch |
| R3 | Best weapon |

## How it works

```
Android app (Java)
 ├─ proot ─► minimal Debian arm64 ─► Xvfb (virtual X screen) ─► UT2004 engine (OldUnreal, arm64)
 │                                     ├─ OpenGL: Mesa Zink -> Vulkan Turnip -> Adreno GPU (KGSL)
 │                                     └─ libutbridge.so (LD_PRELOAD): keyboard/mouse (XTest), pause
 ├─ shows the Xvfb framebuffer (mmap) with OpenGL ES, scaled to the screen
 ├─ sends gamepad/keyboard input to libutbridge (local socket)
 └─ plays the sound: OpenAL -> ALSA -> FIFO -> AudioTrack
```

The game is installed like OldUnreal's official Linux installer does
([FullGameInstallers](https://github.com/OldUnreal/FullGameInstallers)): the DVD image is
extracted with 7-Zip, its InstallShield cabinets with unshield, inside the embedded Linux
environment. The engine sees the game data through symbolic links, with the patch files on
top, so an existing installation is never modified.

## Repository layout

| Path | Content |
|---|---|
| `android/app/` | Android app |
| `android/runtime/` | Linux runtime build: rootfs, Mesa Turnip, bridge library, install script, proot |
| `.github/workflows/release.yml` | APK build and release |

## Building

### With GitHub Actions

Every push to `main` builds the APK and publishes it as a new release (version
`0.1.<build number>`). Mesa and the Linux runtime are built natively on GitHub's ARM64
runners.

**Signing**: the APK is signed with the key stored in the repository secrets
`OPENUT2004_KEYSTORE_BASE64`, `OPENUT2004_KEYSTORE_PASSWORD`, `OPENUT2004_KEY_ALIAS` and
`OPENUT2004_KEY_PASSWORD` (debug key if they are missing). Always sign with the same key so
that updates install over the previous version.

### Locally

Requirements: JDK 17+, Android SDK (platform 36), Python 3, Docker (ARM64 host or emulation).

```sh
# 1. Mesa Turnip + Zink -> android/runtime/out/turnip.tgz
docker run --rm -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-mesa.sh

# 2. ARM64 Linux runtime -> android/runtime/out/rootfs.tgz
docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh

# 3. proot (Termux packages) -> android/app/src/main/jniLibs/arm64-v8a/
python3 android/runtime/prepare-proot.py

# 4. APK
cd android && ./gradlew assembleRelease
```

## Credits and licenses

- **Unreal Tournament 2004** © Epic Games. The game's trademarks and content belong to Epic
  Games. This project is **neither affiliated with nor endorsed** by Epic Games.
- **OldUnreal** (https://www.oldunreal.com): UT2004 patch 3374 and official installers. Not
  redistributed by this project: the app downloads them from their official sources.
- **proot** (GPL-2.0) and **libtalloc** (LGPL-3.0): [Termux](https://termux.dev) packages.
- **Linux runtime**: [Debian](https://www.debian.org) 13 packages (glibc, Mesa, X.Org Xvfb,
  7-Zip, unshield…) and Mesa built from source, each under its own license.
- License details and source code access: [THIRD_PARTY.md](THIRD_PARTY.md).
- Unreal Tournament 2004's icon and artwork are original creations of this project.

### Language, audio and restart

The launcher offers both **Retour au jeu** (resume the existing session) and **Redemarrer le jeu** (apply engine language and graphics changes). The compact mouse toggle has a translucent background. **Configurer la manette** stores per-device button mappings and right-stick axis choices. Controller detection accepts generic Android gamepad buttons and alternate right-stick axes; device-specific validation is still needed for AYN Thor.

French voice audio requires the localized `Sounds/*.frt_uax` files in addition to French UI text. The English OldUnreal installation media does not include those voices. They can be obtained from the [DXM French patch](https://www.dxm.be/navigator.php5?content=216&lang=fr) and placed alongside the original `.uax` files. The launcher sets the engine language to `frt` when French is selected. Voice data is not bundled in the APK or this repository.

Launcher artwork and the colored app icon are original generated fan art inspired by the game.

### External controllers

Xbox, PlayStation (DualShock / DualSense), Switch-style and generic Android gamepads
use Android's standard button positions over USB or Bluetooth. The configuration screen
shows the corresponding physical button labels for Microsoft, Sony and Nintendo devices;
other controllers keep the generic A/B/X/Y labels. Custom bindings remain saved per device.
Both digital and analog triggers are accepted and merged, with no duplicate action.
Right-stick Z/RZ and RX/RY layouts and device dead zones are handled; held actions are
released when a controller disconnects or is reconfigured. Linux cannot open a second
copy of the physical controller alongside the Android input bridge.

Odin 2 Portal mappings are preserved. AYN Thor menu and in-game controls were validated in Daikatana.
The other ports use the same Android controller normalization. Xbox, DualSense and Switch controllers still need physical testing.
