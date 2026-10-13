# Third-party software

OpenUT2004 redistributes the following free software in binary form inside its APK. Their
source code is available at the addresses below.

## proot

- License: GPL-2.0-or-later
- Binaries: Termux packages `proot`, `libtalloc` (LGPL-3.0-or-later) and `libandroid-shmem`
  (BSD-3-Clause), downloaded by `android/runtime/prepare-proot.py` from
  https://packages.termux.dev
- Sources: https://github.com/termux/proot and https://github.com/termux/termux-packages

## Linux runtime (Debian 13 "trixie" arm64 packages)

The bundled rootfs (`assets/runtime/rootfs.tgz`) contains files from unmodified Debian
packages (glibc, Mesa, the X.Org Xvfb server, 7-Zip, unshield, dash…), OpenUT2004's own files
under `/opt/ut` and `/etc/asound.conf`, and the Mesa build described below under `/opt/turnip`.

- Exact list of packages and versions: `packages.txt`, attached to every release and included
  in the rootfs (`/usr/share/doc/openut2004/packages.txt`).
- Licenses: each package's `copyright` file, included in the rootfs
  (`/usr/share/doc/<package>/copyright`).
- Sources: https://sources.debian.org, or https://snapshot.debian.org for an exact version.

## Mesa (Turnip Vulkan driver and Zink OpenGL driver)

- License: MIT (and other permissive licenses, see `docs/license.rst` in the Mesa sources)
- Built from the official Mesa release by `android/runtime/build-mesa.sh`, installed in the
  rootfs under `/opt/turnip`.
- Sources: https://archive.mesa3d.org and https://gitlab.freedesktop.org/mesa/mesa

## Not redistributed

- **OldUnreal UT2004 patch** (engine): downloaded by the app on the device from
  https://github.com/OldUnreal/UT2004Patches/releases on first launch.
- **Unreal Tournament 2004 game data** (© Epic Games): provided by the user, or downloaded by
  the app on the device at the user's request, from the sources used by OldUnreal's official
  installer (files.oldunreal.net, then archive.org as fallback; SHA-256 verified). Epic Games
  allows the game to be distributed for free.
