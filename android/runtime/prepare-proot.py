"""Telecharge proot (paquets Termux, GPL-2) et le prepare pour l'APK.

Android n'extrait de l'APK que les fichiers nommes lib*.so, et une appli ne peut executer
que les fichiers de son nativeLibraryDir. On installe donc dans jniLibs/arm64-v8a :
  libproot.so          <- bin/proot (dependance libtalloc.so.2 renommee en libtalloc.so)
  libproot-loader.so   <- libexec/proot/loader
  libtalloc.so, libandroid-shmem.so

Usage : python -I prepare-proot.py
"""
import io
import lzma
import pathlib
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main/"
PACKAGES = ("proot", "libtalloc", "libandroid-shmem")
PREFIX = "data/data/com.termux/files/usr/"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/jniLibs/arm64-v8a"


def fetch(url):
    with urllib.request.urlopen(url) as r:
        return r.read()


def package_files():
    index = fetch(REPO + "dists/stable/main/binary-aarch64/Packages").decode()
    found = {}
    for block in index.split("\n\n"):
        fields = dict(l.split(": ", 1) for l in block.splitlines() if ": " in l and not l.startswith(" "))
        if fields.get("Package") in PACKAGES:
            found[fields["Package"]] = fields["Filename"]
    return found


def deb_data(deb):
    """Contenu de data.tar.* d'un .deb (archive ar)."""
    assert deb[:8] == b"!<arch>\n"
    pos = 8
    while pos < len(deb):
        header = deb[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58])
        data = deb[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            return tarfile.open(fileobj=io.BytesIO(data))
    raise ValueError("data.tar absent")


def read(tar, path):
    names = {m.name.removeprefix("./"): m for m in tar.getmembers()}
    member = names[PREFIX + path]
    while member.issym():
        member = names[str(pathlib.PurePosixPath(member.name.removeprefix("./")).parent / member.linkname)]
    return tar.extractfile(member).read()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    files = package_files()
    tars = {name: deb_data(fetch(REPO + path)) for name, path in files.items()}

    proot = read(tars["proot"], "bin/proot")
    old, new = b"libtalloc.so.2\0", b"libtalloc.so\0\0\0"
    assert proot.count(old) == 1, "reference libtalloc.so.2 introuvable"
    proot = proot.replace(old, new)

    outputs = {
        "libproot.so": proot,
        "libproot-loader.so": read(tars["proot"], "libexec/proot/loader"),
        "libtalloc.so": read(tars["libtalloc"], "lib/libtalloc.so.2"),
        "libandroid-shmem.so": read(tars["libandroid-shmem"], "lib/libandroid-shmem.so"),
    }
    for name, data in outputs.items():
        (OUT / name).write_bytes(data)
        print(f"{name}: {len(data)} octets")
    print("versions :", ", ".join(pathlib.PurePosixPath(p).name for p in files.values()))


if __name__ == "__main__":
    main()
