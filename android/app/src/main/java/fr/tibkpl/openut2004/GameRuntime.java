package fr.tibkpl.openut2004;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Execute des commandes dans le runtime Linux (rootfs Debian arm64) via proot, sans
 * emulation : le moteur UT2004 arm64 du patch OldUnreal, et l'installation des donnees du jeu.
 */
final class GameRuntime {

    private static final String TAG = "OpenUT2004";

    private final Context context;
    private final GameInstaller.LaunchConfig config;
    private java.lang.Process process;

    GameRuntime(Context context, GameInstaller.LaunchConfig config) {
        this.context = context;
        this.config = config;
    }

    /** Fichier de l'image affichee par l'ecran X virtuel (voir /opt/ut/run.sh). */
    File framebufferFile() {
        return new File(config.rootfsDir, "tmp/fb/Xvfb_screen0");
    }

    File audioFifo() {
        return new File(config.rootfsDir, "tmp/audio.fifo");
    }

    /** Journal du moteur, dans Android/data/<appli>/files (lisible pour les rapports de bug). */
    File logFile() {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), "ut2004.log");
    }

    /**
     * Rendu GPU possible : GPU Adreno accessible (interface KGSL) et Mesa Turnip present dans
     * le runtime. Sinon rendu logiciel (Mesa llvmpipe).
     */
    boolean gpuAvailable() {
        return new File("/dev/kgsl-3d0").canRead() && new File(config.rootfsDir, "opt/turnip/lib").isDirectory();
    }

    java.lang.Process start(int width, int height, int inputPort, boolean gpu) throws IOException {
        List<String> binds = new ArrayList<>();
        binds.add(config.gameRoot.getAbsolutePath() + ":/game");
        binds.add(config.patchDir.getAbsolutePath() + ":" + GameInstaller.PATCH_MOUNT);
        binds.add(config.homeDir.getAbsolutePath() + ":/home/ut");
        binds.add(logFile().getParentFile().getAbsolutePath() + ":/logs");
        List<String> env = new ArrayList<>();
        env.add("HOME=/home/ut");
        env.add("UT_WIDTH=" + width);
        env.add("UT_HEIGHT=" + height);
        env.add("UT_INPUT_PORT=" + inputPort);
        env.add("UT_RENDERER=" + (gpu ? "gpu" : "software"));
        env.add("UT_LOG_DIR=/logs");
        env.add("SDL_JOYSTICK_HIDAPI=0");
        LanNetwork.prepare(context, config.rootfsDir);
        ProcessBuilder pb = proot(context, config.rootfsDir, binds, env, "/game", "/opt/ut/run.sh");
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile());
        process = pb.start();
        return process;
    }

    /**
     * Commande proot dans le rootfs : /dev, /proc, /sys et /storage (memes chemins qu'Android,
     * pour les donnees du jeu sur la memoire interne ou la carte SD), plus les montages donnes.
     */
    static ProcessBuilder proot(Context context, File rootfs, List<String> binds, List<String> env,
                                String workDir, String... command) throws IOException {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        File prootTmp = new File(context.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) throw new IOException("Creation impossible : " + prootTmp);

        List<String> cmd = new ArrayList<>();
        cmd.add(libDir + "/libproot.so");
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        for (String b : new String[]{"/dev", "/proc", "/sys", "/storage",
                new File(rootfs, "tmp").getAbsolutePath() + ":/dev/shm"}) {
            cmd.add("-b");
            cmd.add(b);
        }
        // Android forwards controller input; do not also open physical evdev devices.
        File inputDevices = new File(context.getFilesDir(), "linux-input");
        if (!inputDevices.isDirectory() && !inputDevices.mkdirs()) {
            throw new IOException("Creation du dossier des entrees impossible");
        }
        cmd.add("-b");
        cmd.add(inputDevices.getAbsolutePath() + ":/dev/input");
        for (String b : binds) {
            cmd.add("-b");
            cmd.add(b);
        }
        cmd.add("-w");
        cmd.add(workDir);
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.addAll(env);
        cmd.add("/bin/sh");
        java.util.Collections.addAll(cmd, command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> penv = pb.environment();
        penv.remove("LD_PRELOAD");
        penv.put("LD_LIBRARY_PATH", libDir);
        penv.put("PROOT_LOADER", libDir + "/libproot-loader.so");
        penv.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        Log.i(TAG, "proot : " + String.join(" ", cmd));
        return pb;
    }

    /**
     * Arrete le moteur. Tuer proot ne suffit pas (ses processus traces lui survivent) :
     * on tue tous les processus de l'appli autres que le processus Android lui-meme.
     */
    void stop() {
        if (process != null) process.destroy();
        killAppProcesses();
    }

    static void killAppProcesses() {
        int self = Process.myPid();
        int uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            int pid;
            try {
                pid = Integer.parseInt(p.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self || uidOf(p) != uid) continue;
            try {
                Os.kill(pid, android.system.OsConstants.SIGKILL);
            } catch (Exception ignored) {
                // processus deja termine
            }
        }
    }

    private static int uidOf(File procDir) {
        try {
            for (String line : Files.readAllLines(new File(procDir, "status").toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.split("\\s+")[1]);
            }
        } catch (Exception ignored) {
            // processus disparu ou illisible
        }
        return -1;
    }

    /** Dernieres lignes du journal, pour afficher une erreur a l'utilisateur. */
    String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile().toPath(), StandardCharsets.ISO_8859_1);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
