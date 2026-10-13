package fr.tibkpl.openut2004;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/**
 * Ecran de demarrage : lance le jeu s'il est installe au chemin memorise ; sinon propose de le
 * telecharger (dossier de destination au choix) ou d'indiquer ou il se trouve.
 */
public class LauncherActivity extends Activity {

    static final String ACTION_CHANGE_FOLDER = "fr.tibkpl.openut2004.CHANGE_FOLDER";

    private static final int REQ_PICK_GAME = 1;
    private static final int REQ_PICK_DOWNLOAD = 2;
    private static final int REQ_PERMISSION = 3;

    private TextView status;
    private ProgressBar progress;
    private Button playButton;
    private Button downloadButton;
    private Button pickButton;
    private Button resolutionButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Vrai quand on vient du raccourci "Changer le dossier" : pas de lancement auto. */
    private boolean forcePick;
    private String pickError;

    private final Runnable downloadWatcher = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (GameDownloadService.STATE.running) handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        playButton = findViewById(R.id.play);
        downloadButton = findViewById(R.id.download);
        pickButton = findViewById(R.id.pick);
        resolutionButton = findViewById(R.id.resolution);
        downloadButton.setOnClickListener(v -> confirmDownload());
        pickButton.setOnClickListener(v -> openPicker(REQ_PICK_GAME));
        resolutionButton.setOnClickListener(v -> cycleResolution());
        updateResolutionButton();
        forcePick = ACTION_CHANGE_FOLDER.equals(getIntent().getAction());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (GameDownloadService.STATE.running) handler.post(downloadWatcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(downloadWatcher);
    }

    private void refresh() {
        GameDownloadService.State dl = GameDownloadService.STATE;
        boolean storage = hasStorageAccess();

        if (dl.running) {
            status.setText(dl.message);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(dl.percent < 0);
            if (dl.percent >= 0) progress.setProgress(dl.percent);
            setButtons(false, false, false);
            return;
        }
        progress.setVisibility(View.GONE);

        if (!storage) {
            status.setText(R.string.status_need_permission);
            playButton.setVisibility(View.GONE);
            downloadButton.setVisibility(View.GONE);
            pickButton.setVisibility(View.VISIBLE);
            pickButton.setEnabled(true);
            pickButton.setText(R.string.btn_grant);
            pickButton.setOnClickListener(v -> requestStorageAccess());
            return;
        }
        pickButton.setText(R.string.btn_pick);
        pickButton.setOnClickListener(v -> openPicker(REQ_PICK_GAME));

        String saved = GameData.getSavedPath(this);
        File gameDir = saved == null ? null : GameData.findGameDir(new File(saved));
        boolean justInstalled = dl.finished && dl.error == null && dl.gameDir != null;

        // Jeu present : lancement direct (y compris juste apres le telechargement)
        if (gameDir != null && !forcePick && pickError == null) {
            dl.finished = false;
            launchGame(gameDir);
            return;
        }

        if (dl.finished && dl.error != null) {
            status.setText(getString(R.string.download_failed, dl.error));
        } else if (justInstalled) {
            status.setText(getString(R.string.status_installed, dl.gameDir.getAbsolutePath()));
        } else if (pickError != null) {
            status.setText(pickError);
        } else if (gameDir != null) {
            status.setText(getString(R.string.status_found, gameDir.getAbsolutePath()));
        } else if (saved != null) {
            status.setText(getString(R.string.status_saved_missing, saved));
        } else {
            status.setText(R.string.status_no_game);
        }
        setButtons(gameDir != null, true, true);
        if (gameDir != null) {
            playButton.setOnClickListener(v -> launchGame(gameDir));
            playButton.requestFocus();
        } else {
            downloadButton.requestFocus();
        }
    }

    private void setButtons(boolean play, boolean download, boolean pick) {
        playButton.setVisibility(play ? View.VISIBLE : View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(download);
        pickButton.setVisibility(View.VISIBLE);
        pickButton.setEnabled(pick);
    }

    private void launchGame(File gameDir) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_GAME_DIR, gameDir.getAbsolutePath());
        startActivity(intent);
        finish();
    }

    /** Explique la provenance et la taille du telechargement avant de choisir la destination. */
    private void confirmDownload() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_confirm_title)
                .setMessage(R.string.download_confirm_text)
                .setPositiveButton(R.string.download_confirm_ok, (d, w) -> openPicker(REQ_PICK_DOWNLOAD))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openPicker(int request) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        File picked = GameData.treeUriToFile(data.getData());
        if (picked == null) {
            pickError = getString(R.string.status_pick_unsupported);
            return;
        }

        if (requestCode == REQ_PICK_DOWNLOAD) {
            File dest = picked.getName().equalsIgnoreCase("UT2004") ? picked : new File(picked, "UT2004");
            long free = freeSpace(picked);
            if (free >= 0 && free < GameDownloadService.REQUIRED_SPACE) {
                pickError = getString(R.string.download_no_space, free >> 30,
                        GameDownloadService.REQUIRED_SPACE >> 30);
                return;
            }
            pickError = null;
            forcePick = false;
            GameDownloadService.start(this, dest);
            GameDownloadService.STATE.running = true; // affichage immediat de la progression
            GameDownloadService.STATE.message = getString(R.string.download_preparing);
            GameDownloadService.STATE.percent = -1;
            handler.post(downloadWatcher);
            return;
        }

        File gameDir = GameData.findGameDir(picked);
        if (gameDir == null) {
            pickError = getString(R.string.status_pick_invalid, picked.getAbsolutePath(),
                    String.join(", ", GameData.missing(picked)));
            return;
        }
        GameData.savePath(this, gameDir);
        pickError = null;
        forcePick = false;
        // onResume() -> refresh() lance le jeu
    }

    private static long freeSpace(File dir) {
        try {
            File existing = dir;
            while (existing != null && !existing.exists()) existing = existing.getParentFile();
            return existing == null ? -1 : new StatFs(existing.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private void cycleResolution() {
        int current = GameData.getRenderHeight(this);
        GameData.saveRenderHeight(this, current == GameData.RENDER_HEIGHTS[0]
                ? GameData.RENDER_HEIGHTS[1] : GameData.RENDER_HEIGHTS[0]);
        updateResolutionButton();
    }

    private void updateResolutionButton() {
        boolean smooth = GameData.getRenderHeight(this) == GameData.RENDER_HEIGHTS[0];
        resolutionButton.setText(smooth ? R.string.btn_resolution_720 : R.string.btn_resolution_1080);
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }
}
