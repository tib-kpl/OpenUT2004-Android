package fr.tibkpl.openut2004;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;

/** Per-device Android mappings; no device names are hard coded. */
final class ControllerSettings {
    private ControllerSettings() {}
    static SharedPreferences prefs;
    static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("controllers", Context.MODE_PRIVATE);
    }
    static String prefix(InputDevice device) { return device.getDescriptor() + "."; }
    static void show(Activity activity) {
        init(activity);
        ArrayList<InputDevice> devices = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (ControllerInput.isController(device)) { devices.add(device); names.add(device.getName()); }
        }
        if (devices.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("Manette")
                    .setMessage("Connectez une manette puis ouvrez de nouveau cet écran.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Manette détectée")
                .setItems(names.toArray(new String[0]), (dialog, index) -> configure(activity, devices.get(index)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private static void configure(Activity activity, InputDevice device) {
        String prefix = prefix(device);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, padding, padding, padding);
        TextView help = new TextView(activity);
        help.setText("Choisissez un bouton à configurer, puis appuyez sur la touche correspondante de cette manette. Les réglages sont conservés pour ce périphérique. La croix analogique (hat) est reconnue automatiquement.");
        rows.addView(help);
        String[] labels = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start", "L3", "R3", "Croix haut", "Croix bas", "Croix gauche", "Croix droite"};
        if (device.getVendorId() == 0x054c) {
            labels[0] = "Croix (A)"; labels[1] = "Cercle (B)";
            labels[2] = "Carre (X)"; labels[3] = "Triangle (Y)";
            labels[8] = "Share / Create"; labels[9] = "Options";
        } else if (device.getVendorId() == 0x045e) {
            labels[4] = "LB"; labels[5] = "RB"; labels[6] = "LT"; labels[7] = "RT";
            labels[8] = "View"; labels[9] = "Menu";
        } else if (device.getVendorId() == 0x057e) {
            labels[0] = "B (position A)"; labels[1] = "A (position B)";
            labels[2] = "Y (position X)"; labels[3] = "X (position Y)";
            labels[6] = "ZL"; labels[7] = "ZR";
        }
        int[] logical = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i]; int target = logical[i];
            Button button = new Button(activity);
            button.setText(label + " : configurer");
            button.setOnClickListener(view -> {
                AlertDialog capture = new AlertDialog.Builder(activity).setTitle(label)
                        .setMessage("Appuyez sur le bouton de " + device.getName())
                        .setNegativeButton(android.R.string.cancel, null).create();
                capture.setOnKeyListener((dialog, code, event) -> {
                    if (event.getDevice() == null || !prefix.equals(prefix(event.getDevice()))) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        SharedPreferences.Editor edit = prefs.edit();
                        for (String key : prefs.getAll().keySet()) {
                            if (key.startsWith(prefix + "key.") && prefs.getInt(key, -1) == target) edit.remove(key);
                        }
                        edit.putInt(prefix + "key." + code, target).apply();
                        button.setText(label + " : " + KeyEvent.keyCodeToString(code));
                        dialog.dismiss();
                    }
                    return true;
                });
                capture.show();
            });
            rows.addView(button);
        }
        TextView axisLabel = new TextView(activity); axisLabel.setText("Axes du stick droit"); rows.addView(axisLabel);
        Spinner axes = new Spinner(activity);
        axes.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Automatique", "Z / RZ", "RX / RY"}));
        axes.setSelection(prefs.getInt(prefix + "axes", 0)); rows.addView(axes);
        ScrollView scroll = new ScrollView(activity); scroll.addView(rows);
        new AlertDialog.Builder(activity).setTitle(device.getName()).setView(scroll)
                .setPositiveButton("Appliquer", (dialog, which) -> prefs.edit().putInt(prefix + "axes", axes.getSelectedItemPosition()).apply())
                .setNeutralButton("Réinitialiser", (dialog, which) -> {
                    SharedPreferences.Editor edit = prefs.edit();
                    for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix)) edit.remove(key);
                    edit.apply();
                }).setNegativeButton("Fermer", null).show();
    }
}
