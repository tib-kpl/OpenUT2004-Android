package fr.tibkpl.openut2004;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Telechargements HTTP(S) : reprise apres interruption, verification de taille et SHA-256. */
final class Http {

    interface Progress {
        void onProgress(long done, long total);
    }

    private Http() {}

    static byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = open(url, 0);
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Telecharge url dans dest. Si resume est vrai et que dest existe partiellement, le
     * telechargement reprend la ou il s'etait arrete. Verifie la taille et le SHA-256 s'ils sont
     * connus (fichier supprime s'ils ne correspondent pas).
     */
    static void download(String url, File dest, long expectedSize, String sha256, boolean resume,
                         Progress progress) throws IOException {
        long offset = resume && dest.isFile() ? dest.length() : 0;
        if (expectedSize > 0 && offset > expectedSize) offset = 0;
        if (expectedSize <= 0 || offset < expectedSize) {
            HttpURLConnection conn = open(url, offset);
            try {
                boolean partial = conn.getResponseCode() == HttpURLConnection.HTTP_PARTIAL;
                if (!partial) offset = 0; // le serveur renvoie tout le fichier
                long total = expectedSize > 0 ? expectedSize : offset + conn.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 1 << 16);
                     OutputStream out = new FileOutputStream(dest, partial)) {
                    byte[] buffer = new byte[1 << 16];
                    long done = offset;
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Annule");
                        out.write(buffer, 0, n);
                        done += n;
                        if (done - lastReport > (1 << 20)) {
                            lastReport = done;
                            progress.onProgress(done, total);
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
        }
        if (expectedSize > 0 && dest.length() != expectedSize) {
            throw new IOException("Telechargement incomplet (" + dest.length() + " / " + expectedSize + " octets)");
        }
        if (sha256 != null && !sha256.isEmpty()) {
            String actual = sha256Of(dest);
            if (!actual.equalsIgnoreCase(sha256)) {
                dest.delete();
                throw new IOException("Fichier corrompu (SHA-256 " + actual + ")");
            }
        }
    }

    private static HttpURLConnection open(String url, long offset) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "OpenUT2004-Android");
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " : " + url);
        }
        return conn;
    }

    static String sha256Of(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
