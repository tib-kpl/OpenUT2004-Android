package fr.tibkpl.openut2004;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Detection et memorisation du dossier d'installation d'Unreal Tournament 2004, reglages. */
final class GameData {

    /** Elements d'une installation d'UT2004 (PC, GOG, Steam, DVD ou telechargement de l'appli). */
    static final String[] REQUIRED = {"System/Core.u", "System/Engine.u", "Maps", "Textures"};

    /** Sous-dossiers acceptes si l'utilisateur choisit le dossier parent. */
    private static final String[] SUBDIRS = {"UT2004", "Unreal Tournament 2004"};

    private static final String PREFS = "openut2004";
    private static final String KEY_DATA_PATH = "data_path";
    private static final String KEY_RENDER_HEIGHT = "render_height";
    private static final String KEY_GPU_FAILED = "gpu_failed";
    private static final String KEY_GPU_FAILED_VERSION = "gpu_failed_version";

    /** Hauteurs de rendu proposees (largeur au format de l'ecran) : 720p fluide, 1080p net. */
    static final int[] RENDER_HEIGHTS = {720, 1080};

    private GameData() {}

    static String getSavedPath(Context context) {
        return prefs(context).getString(KEY_DATA_PATH, null);
    }

    static void savePath(Context context, File dir) {
        prefs(context).edit().putString(KEY_DATA_PATH, dir.getAbsolutePath()).apply();
    }

    static int getRenderHeight(Context context) {
        return prefs(context).getInt(KEY_RENDER_HEIGHT, RENDER_HEIGHTS[0]);
    }

    static void saveRenderHeight(Context context, int height) {
        prefs(context).edit().putInt(KEY_RENDER_HEIGHT, height).apply();
    }

    /**
     * Vrai si le rendu GPU a echoue sur cet appareil avec cette version de l'appli : on reste
     * alors en rendu logiciel (reessaye apres chaque mise a jour).
     */
    static boolean isGpuFailed(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean(KEY_GPU_FAILED, false) && p.getInt(KEY_GPU_FAILED_VERSION, -1) == appVersion(context);
    }

    static void setGpuFailed(Context context, boolean failed) {
        prefs(context).edit().putBoolean(KEY_GPU_FAILED, failed)
                .putInt(KEY_GPU_FAILED_VERSION, appVersion(context)).apply();
    }

    @SuppressWarnings("deprecation")
    private static int appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Dossier du jeu : le dossier choisi ou l'un de ses sous-dossiers usuels. Null si absent. */
    static File findGameDir(File root) {
        if (root == null || !root.isDirectory()) return null;
        if (missing(root).isEmpty()) return root;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (String name : SUBDIRS) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().equalsIgnoreCase(name) && missing(child).isEmpty()) {
                    return child;
                }
            }
        }
        return null;
    }

    /** Elements requis absents (noms compares sans tenir compte de la casse, comme sous Windows). */
    static List<String> missing(File dir) {
        List<String> result = new ArrayList<>();
        for (String required : REQUIRED) {
            if (resolve(dir, required) == null) result.add(required);
        }
        return result;
    }

    /** Chemin relatif resolu sans tenir compte de la casse, ou null. */
    static File resolve(File dir, String relative) {
        File current = dir;
        for (String part : relative.split("/")) {
            File exact = new File(current, part);
            if (exact.exists()) {
                current = exact;
                continue;
            }
            File found = null;
            String[] names = current.list();
            if (names != null) {
                for (String name : names) {
                    if (name.toLowerCase(Locale.ROOT).equals(part.toLowerCase(Locale.ROOT))) {
                        found = new File(current, name);
                        break;
                    }
                }
            }
            if (found == null) return null;
            current = found;
        }
        return current;
    }

    /**
     * Convertit l'URI renvoyee par le selecteur de dossier Android en chemin reel.
     * Ex. "primary:Games/UT2004" -> /storage/emulated/0/Games/UT2004,
     *     "1234-ABCD:UT2004"     -> /storage/1234-ABCD/UT2004 (carte SD).
     */
    static File treeUriToFile(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) {
            return null;
        }
        String docId = DocumentsContract.getTreeDocumentId(uri);
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String volume = docId.substring(0, colon);
        String relative = docId.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + volume);
        return relative.isEmpty() ? base : new File(base, relative);
    }
}
