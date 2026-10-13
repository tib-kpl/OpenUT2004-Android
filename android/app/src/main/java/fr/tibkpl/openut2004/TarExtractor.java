package fr.tibkpl.openut2004;

import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

/**
 * Extraction minimale d'une archive .tar.gz (ustar/GNU/pax) avec liens symboliques,
 * liens physiques (copies) et bits d'execution, pour installer le rootfs Linux.
 */
final class TarExtractor {

    interface Progress {
        void onBytes(long compressedBytesRead);
    }

    private static final int BLOCK = 512;

    private TarExtractor() {}

    static void extract(InputStream compressed, File destDir, Progress progress) throws IOException {
        CountingInputStream counting = new CountingInputStream(new BufferedInputStream(compressed, 1 << 16));
        InputStream in = new GZIPInputStream(counting, 1 << 16);
        byte[] header = new byte[BLOCK];
        byte[] buffer = new byte[1 << 16];
        String longName = null;
        String longLink = null;
        long lastReport = 0;

        while (true) {
            if (!readBlock(in, header)) break;
            if (isZero(header)) break;

            String name = longName != null ? longName : entryName(header);
            String link = longLink != null ? longLink : string(header, 157, 100);
            longName = null;
            longLink = null;
            char type = (char) header[156];
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8);

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                if (type == 'L') longName = cString(data);
                else if (type == 'K') longLink = cString(data);
                else if (type == 'x') {
                    String path = paxValue(data, "path");
                    String linkpath = paxValue(data, "linkpath");
                    if (path != null) longName = path;
                    if (linkpath != null) longLink = linkpath;
                }
                continue;
            }

            File target = new File(destDir, name);
            if (!target.toPath().normalize().startsWith(destDir.toPath().normalize())) {
                throw new IOException("Chemin hors du dossier : " + name);
            }

            switch (type) {
                case '5':
                    mkdirs(target);
                    break;
                case '2':
                    mkdirs(target.getParentFile());
                    deleteIfExists(target);
                    symlink(link, target);
                    break;
                case '1':
                    mkdirs(target.getParentFile());
                    deleteIfExists(target);
                    Files.copy(new File(destDir, stripDot(link)).toPath(), target.toPath());
                    break;
                case '0':
                case '\0':
                case '7':
                    mkdirs(target.getParentFile());
                    deleteIfExists(target);
                    try (OutputStream out = new FileOutputStream(target)) {
                        long remaining = size;
                        while (remaining > 0) {
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (n < 0) throw new EOFException();
                            out.write(buffer, 0, n);
                            remaining -= n;
                        }
                    }
                    skip(in, padding(size));
                    chmod(target, mode);
                    break;
                default:
                    // Peripheriques, FIFO... : inutiles dans le rootfs
                    skip(in, size + padding(size));
                    break;
            }

            if (progress != null && counting.count - lastReport > (1 << 20)) {
                lastReport = counting.count;
                progress.onBytes(counting.count);
            }
        }
    }

    private static String entryName(byte[] header) {
        String name = string(header, 0, 100);
        String prefix = string(header, 345, 155);
        String full = prefix.isEmpty() ? name : prefix + "/" + name;
        return stripDot(full);
    }

    private static String stripDot(String path) {
        while (path.startsWith("./")) path = path.substring(2);
        return path;
    }

    private static String paxValue(byte[] data, String key) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            int space = line.indexOf(' ');
            int eq = line.indexOf('=');
            if (space > 0 && eq > space && line.substring(space + 1, eq).equals(key)) {
                return stripDot(line.substring(eq + 1));
            }
        }
        return null;
    }

    private static void symlink(String link, File target) throws IOException {
        try {
            Os.symlink(link, target.getPath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + target + " -> " + link, e);
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getPath(), (mode & 0777) | 0600);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + file, e);
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) file.delete();
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Creation impossible : " + dir);
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < block.length) {
            int n = in.read(block, off, block.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new EOFException();
            }
            off += n;
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        skip(in, padding(size));
        return data;
    }

    private static void skip(InputStream in, long count) throws IOException {
        byte[] scratch = new byte[8192];
        while (count > 0) {
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, count));
            if (n < 0) throw new EOFException();
            count -= n;
        }
    }

    private static long padding(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static long octal(byte[] header, int off, int len) {
        if ((header[off] & 0x80) != 0) { // encodage binaire GNU (grandes tailles)
            long value = 0;
            for (int i = off + 1; i < off + len; i++) value = (value << 8) | (header[i] & 0xff);
            return value;
        }
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static String string(byte[] header, int off, int len) {
        int end = off;
        while (end < off + len && header[end] != 0) end++;
        return new String(header, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        return stripDot(new String(data, 0, end, StandardCharsets.UTF_8));
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
