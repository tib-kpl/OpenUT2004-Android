package fr.tibkpl.openut2004;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.app.AlertDialog;

import java.io.File;

/**
 * Ecran du jeu : plein ecran immersif sur toute la surface (16:9 et ecrans plus larges,
 * zone de l'encoche comprise). Prepare les fichiers, lance le moteur dans le runtime Linux
 * et relie affichage, manette/clavier et son.
 */
public class GameActivity extends Activity {

    static final String EXTRA_GAME_DIR = "game_dir";
    /** Nouvel essai en rendu logiciel apres un echec au demarrage en rendu GPU. */
    static final String EXTRA_SOFTWARE_RETRY = "software_retry";

    private static final String TAG = "OpenUT2004";

    private FrameLayout root;
    private TextView message;
    private GameRuntime runtime;
    private InputBridge input;
    private ControllerMonitor controllerMonitor;
    private Button inputMode;
    private TextView fpsCounter;
    private AudioBridge audio;
    private LanNetwork lanNetwork;
    private volatile boolean finishing;
    private boolean resumed;
    private java.util.List<String> availableLanguages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControllerSettings.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(0xFFEEEEEE);
        message.setTextSize(16);
        message.setPadding(48, 48, 48, 48);
        message.setText(R.string.game_preparing);
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        addSettingsButton();
        addInputModeButton();
        hideSystemBars();

        String path = getIntent().getStringExtra(EXTRA_GAME_DIR);
        File dataDir = path == null ? null : GameData.findGameDir(new File(path));
        if (dataDir == null) {
            // Fichiers disparus entre-temps : retour au lanceur qui redemandera le dossier.
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }

        new Thread(() -> {
            try {
                GameInstaller.LaunchConfig config = GameInstaller.prepare(this, dataDir,
                        step -> runOnUiThread(() -> message.setText(step)));
                runOnUiThread(() -> startGame(config));
            } catch (Exception e) {
                Log.e(TAG, "Preparation", e);
                runOnUiThread(() -> message.setText(getString(R.string.game_error, e.getMessage())));
            }
        }, "ut2004-prepare").start();
    }

    private void startGame(GameInstaller.LaunchConfig config) {
        if (isFinishing()) return;
        runtime = new GameRuntime(this, config);
        try {
            audio = new AudioBridge(runtime.audioFifo());
            audio.start();
            lanNetwork = new LanNetwork(this);
            InputBridge.debug = false;
            input = new InputBridge(menu -> runOnUiThread(() -> {
                if (input == null || finishing) return;
                input.setMenuMode(menu);
                updateInputModeButton();
            }));

            controllerMonitor = new ControllerMonitor(this, input::resetController);

            Point screen = screenSize();
            // Hauteur de rendu choisie (largeur au format de l'ecran, donc 16:9 sur un ecran
            // 16:9) ; l'image est ensuite agrandie a la taille de l'ecran.
            int height = Math.min(GameData.getRenderHeight(this), screen.y);
            int width = Math.round(height * (float) screen.x / screen.y) & ~1;
            input.setPointerBounds(width, height);

            runtime.framebufferFile().delete();
            FramebufferView view = new FramebufferView(this, runtime.framebufferFile());
            view.setOnTouchListener((v, event) -> input.onTouch(event, width, height, v.getWidth(), v.getHeight()));
            root.addView(view, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            root.addView(new MenuCursorView(this, input), 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            availableLanguages = GameData.detectLanguages(config.gameRoot, config.patchDir);
            String language = GameData.getLanguage(this, availableLanguages);
            updateInputModeButton();
            GameInstaller.prepareConfig(config, width, height, language);
            boolean retry = getIntent().getBooleanExtra(EXTRA_SOFTWARE_RETRY, false);
            String renderer = GameData.getRenderer(this);
            boolean gpu = !retry && runtime.gpuAvailable() && !"software".equals(renderer)
                    && ("turnip".equals(renderer) || !GameData.isGpuFailed(this));
            Log.i(TAG, "Rendu " + (gpu ? "GPU (Turnip + Zink)" : "logiciel") + ", " + width + "x" + height);
            java.lang.Process process = runtime.start(width, height, input.port(), gpu);
            if (!resumed) {
                input.setPaused(true);
                audio.setPaused(true);
            }
            message.setText(R.string.game_starting);
            message.postDelayed(() -> message.setVisibility(View.GONE), 4000);
            watch(process, gpu);
            addFpsCounter();
            if (retry) {
                // Le rendu logiciel tient la ou le GPU echouait : on le retient pour cet appareil
                message.postDelayed(() -> {
                    if (process.isAlive()) GameData.setGpuFailed(this, true);
                }, 15_000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lancement", e);
            message.setText(getString(R.string.game_error, e.getMessage()));
        }
    }

    /** Ferme l'ecran quand le jeu se termine ; affiche le journal s'il plante au demarrage. */
    private void watch(java.lang.Process process, boolean gpu) {
        long startedAt = System.currentTimeMillis();
        Thread watcher = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (finishing) return;
            boolean early = System.currentTimeMillis() - startedAt < 10_000;
            String tail = runtime.logTail(25);
            Log.i(TAG, "Moteur termine (code " + code + ")\n" + tail);
            runOnUiThread(() -> {
                if (early && code != 0 && gpu) {
                    // Nouvel essai en rendu logiciel ; le GPU n'est desactive que si ce rendu
                    // fonctionne (sinon l'echec ne vient pas du GPU)
                    Log.w(TAG, "Echec au demarrage en rendu GPU, essai en rendu logiciel");
                    finishing = true;
                    runtime.stop();
                    Intent retry = new Intent(getIntent()).putExtra(EXTRA_SOFTWARE_RETRY, true);
                    finish();
                    startActivity(retry);
                } else if (early && code != 0) {
                    message.setVisibility(View.VISIBLE);
                    message.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    message.setTextSize(12);
                    message.setText(getString(R.string.game_exited, code, tail));
                } else {
                    finish();
                }
            });
        }, "ut2004-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Petit rouage en surimpression : retour aux reglages (langue, resolution, dossier). */
    private void addSettingsButton() {
        android.widget.ImageButton gear = new android.widget.ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setBackgroundColor(0x00000000);
        gear.setAlpha(0.45f);
        gear.setFocusable(false);
        gear.setContentDescription(getString(R.string.settings));
        int size = Math.round(40 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.setMargins(0, size / 4, size / 4, 0);
        gear.setOnClickListener(v -> openSettings());
        root.addView(gear, lp);
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, LauncherActivity.class).setAction(LauncherActivity.ACTION_SETTINGS)
                .putExtra(LauncherActivity.EXTRA_RETURN_TO_GAME, true), 101);
    }

    /** Explicit mode: UT2004 also captures the mouse in some menu screens. */
    private void addInputModeButton() {
        inputMode = new Button(this);
        inputMode.setText(R.string.input_mode_menu);
        inputMode.setTextSize(11);
        inputMode.setAlpha(1f);
        inputMode.setTextColor(0xccffffff);
        inputMode.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0x55333333);
        background.setCornerRadius(6 * getResources().getDisplayMetrics().density);
        inputMode.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33ffffff), background, null));
        inputMode.setStateListAnimator(null);
        inputMode.setElevation(0);
        inputMode.setFocusable(false);
        inputMode.setAllCaps(false);
        inputMode.setMinimumHeight(0);
        inputMode.setMinHeight(0);
        inputMode.setMinWidth(0);
        inputMode.setMinimumWidth(0);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        inputMode.setPadding(gap, gap / 2, gap, gap / 2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, gap, 0, 0);
        root.addView(inputMode, lp);
        inputMode.setOnClickListener(v -> toggleInputMode());
    }

    private void toggleInputMode() {
        if (input == null) return;
        input.setMenuMode(!input.isMenuMode());
        updateInputModeButton();
    }

    private void updateInputModeButton() {
        if (inputMode == null) return;
        String language = availableLanguages == null ? "frt" : GameData.getLanguage(this, availableLanguages);
        String tag;
        switch (language) {
            case "frt": tag = "fr"; break;
            case "det": tag = "de"; break;
            case "est": tag = "es"; break;
            case "itt": tag = "it"; break;
            case "ptt": tag = "pt"; break;
            case "nlt": tag = "nl"; break;
            case "plt": tag = "pl"; break;
            case "het": tag = "hu"; break;
            case "rut": tag = "ru"; break;
            case "elt": tag = "el"; break;
            case "kot": tag = "ko"; break;
            case "ctt": tag = "zh-Hant"; break;
            case "smt": tag = "zh-Hans"; break;
            case "tmt": tag = "th"; break;
            default: tag = "en";
        }
        android.content.res.Configuration localized = new android.content.res.Configuration(getResources().getConfiguration());
        localized.setLocale(java.util.Locale.forLanguageTag(tag));
        inputMode.setText(createConfigurationContext(localized).getString(
                input == null || input.isMenuMode() ? R.string.input_mode_menu : R.string.input_mode_game));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == Activity.RESULT_FIRST_USER) recreate();
    }

    private void addFpsCounter() {
        TextView fps = new TextView(this);
        fpsCounter = fps;
        fps.setVisibility(GameData.showFps(this) ? View.VISIBLE : View.GONE);
        fps.setTextColor(0xffffffff);
        fps.setBackgroundColor(0x99000000);
        fps.setTextSize(12);
        fps.setPadding(12, 6, 12, 6);
        fps.setFocusable(false);
        root.addView(fps, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        File stats = new File(runtime.logFile().getParentFile(), "render-fps.txt");
        fps.post(new Runnable() {
            @Override public void run() {
                if (finishing || isFinishing() || isDestroyed()) return;
                String value = "—";
                try {
                    String reading = new String(java.nio.file.Files.readAllBytes(stats.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (reading.matches("[0-9]+\\.[0-9]")) value = reading;
                } catch (Exception ignored) { }
                fps.setText(value + " FPS");
                fps.postDelayed(this, 1000);
            }
        });
    }

    private void openGameKeyboard() {
        EditText text = new EditText(this);
        text.setSingleLine(true);
        text.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.input_text_title).setView(text)
                .setPositiveButton(R.string.input_text_send, (d, which) -> input.sendText(text.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null).create();
        text.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                input.sendText(text.getText().toString());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            text.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = ControllerInput.key(event);
        if (input != null && input.isMenuMode() && keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) openGameKeyboard();
            return true;
        }
        if (input != null && keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleInputMode();
            return true;
        }
        boolean system = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_HOME;
        if (!system && input != null && input.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (input != null && input.onMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    /** Appli en arriere-plan ou ecran verrouille : jeu fige et son coupe. */
    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
        if (input != null) input.setPaused(true);
        if (audio != null) audio.setPaused(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateInputModeButton();
        if (fpsCounter != null) fpsCounter.setVisibility(GameData.showFps(this) ? View.VISIBLE : View.GONE);
        resumed = true;
        if (audio != null) audio.setPaused(false);
        if (input != null) input.setPaused(false);
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (runtime != null) runtime.stop();
        if (audio != null) audio.stop();
        if (lanNetwork != null) lanNetwork.close();
        if (controllerMonitor != null) controllerMonitor.close();
        if (input != null) input.close();
        super.onDestroy();
    }

    /** Resolution reelle de l'ecran. */
    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            size.set(bounds.width(), bounds.height());
        } else {
            getWindowManager().getDefaultDisplay().getRealSize(size);
        }
        if (size.x < size.y) size.set(size.y, size.x); // toujours en paysage
        return size;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
