package fr.tibkpl.openut2004;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioRouting;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Joue le son du moteur : OpenAL -> ALSA -> FIFO (PCM S16_LE stereo 48 kHz, voir
 * /etc/asound.conf du rootfs) -> AudioTrack. Un petit tampon de FIFO et l'attente
 * lorsque la sortie est pleine cadencent le mixage sans bloquer la pause ou l'arret.
 */
final class AudioBridge {

    private static final String TAG = "OpenUT2004";
    private static final int SAMPLE_RATE = 48000;
    private static final int F_SETPIPE_SZ = 1031;
    private static final int PIPE_SIZE = 8192; // ~40 ms

    private final File fifo;
    private volatile boolean running = true;
    private boolean paused;
    private AudioTrack track;
    private Thread thread;
    private final AudioRouting.OnRoutingChangedListener routeListener = router -> {
        synchronized (AudioBridge.this) {
            if (!running || router != track) return;
            AudioDeviceInfo device = router.getRoutedDevice();
            if (device != null) Log.i(TAG, "Route audio Android : type " + device.getType());
        }
    };

    AudioBridge(File fifo) {
        this.fifo = fifo;
    }

    /** Cree le FIFO (avant le lancement du moteur) et demarre la lecture. */
    void start() throws IOException {
        fifo.delete();
        try {
            Os.mkfifo(fifo.getPath(), 0666);
        } catch (ErrnoException e) {
            throw new IOException("mkfifo " + fifo, e);
        }
        thread = new Thread(this::run, "ut2004-audio");
        thread.start();
    }

    /**
     * Coupe le son (appli en arriere-plan) : AudioTrack en pause et FIFO plus lu, ce qui
     * bloque aussi le mixage OpenAL du jeu.
     */
    synchronized void setPaused(boolean value) {
        paused = value;
        if (track != null && running) {
            if (value) {
                track.pause();
                track.flush();
            } else track.play();
        }
        notifyAll();
    }

    private synchronized void waitWhilePaused() throws InterruptedException {
        while (paused && running) wait();
    }

    synchronized void stop() {
        running = false;
        notifyAll();
        if (track != null) {
            track.pause();
            track.flush();
        }
        if (thread != null) thread.interrupt();
        // Debloque une ouverture du FIFO en attente d'un ecrivain (non bloquant si aucun lecteur)
        try {
            Os.close(Os.open(fifo.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0));
        } catch (ErrnoException ignored) {
            // aucun lecteur en attente
        }
    }

    private synchronized void createTrack() {
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuffer * 2, 7680))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        this.track = track;
        track.addOnRoutingChangedListener(routeListener, new Handler(Looper.getMainLooper()));
        if (running && !paused) track.play();
    }

    private synchronized void releaseTrack() {
        if (track != null) {
            track.removeOnRoutingChangedListener(routeListener);
            track.release();
            track = null;
        }
    }

    /** Preserve unwritten PCM and rebuild the output if Android invalidates it on a route change. */
    private void writeFrames(byte[] buffer, int bytes) throws IOException, InterruptedException {
        int offset = 0;
        boolean recovered = false;
        while (offset < bytes && running) {
            waitWhilePaused();
            if (!running) return;
            int written;
            synchronized (this) {
                if (paused) continue;
                written = track.write(buffer, offset, bytes - offset, AudioTrack.WRITE_NON_BLOCKING);
                if (written == AudioTrack.ERROR_DEAD_OBJECT && !recovered) {
                    releaseTrack();
                    createTrack();
                    recovered = true;
                    Log.i(TAG, "Sortie audio recreee apres invalidation Android");
                    continue;
                }
            }
            if (written < 0) throw new IOException("AudioTrack.write : " + written);
            if (written == 0) {
                synchronized (this) { if (running && !paused) wait(10); }
            } else {
                offset += written;
            }
        }
    }

    private void run() {
        byte[] buffer = new byte[3840]; // 20 ms
        try {
            createTrack();
            while (running) {
                // open() bloque jusqu'a ce que le moteur ouvre le FIFO en ecriture
                try (FileInputStream in = new FileInputStream(fifo)) {
                    try {
                        Os.fcntlInt(in.getFD(), F_SETPIPE_SZ, PIPE_SIZE);
                    } catch (ErrnoException e) {
                        Log.w(TAG, "F_SETPIPE_SZ : " + e.getMessage());
                    }
                    // N'ecrit que des trames completes (4 octets) ; le reste attend la lecture suivante
                    int filled = 0;
                    int n;
                    while (running) {
                        waitWhilePaused();
                        if ((n = in.read(buffer, filled, buffer.length - filled)) <= 0) break;
                        filled += n;
                        int frames = filled & ~3;
                        writeFrames(buffer, frames);
                        System.arraycopy(buffer, frames, buffer, 0, filled - frames);
                        filled -= frames;
                    }
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Audio : " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Sortie audio indisponible", e);
        } finally {
            releaseTrack();
        }
    }
}
