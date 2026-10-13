package fr.tibkpl.openut2004;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Normalize Android controller layouts without depending on a model name. */
final class ControllerInput {
    private ControllerInput() {}
    static int key(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device != null && ControllerSettings.prefs != null) {
            String entry = ControllerSettings.prefix(device) + "key." + event.getKeyCode();
            if (ControllerSettings.prefs.contains(entry)) return ControllerSettings.prefs.getInt(entry, event.getKeyCode());
        }
        return key(event.getKeyCode());
    }
    static int key(int code) {
        int[] generic = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR};
        int index = code - KeyEvent.KEYCODE_BUTTON_1;
        return index >= 0 && index < generic.length ? generic[index] : code;
    }
    static boolean isController(InputDevice device) {
        return device != null && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
    static boolean isButton(int code) {
        return code >= KeyEvent.KEYCODE_BUTTON_A && code <= KeyEvent.KEYCODE_BUTTON_MODE;
    }
    static int rightAxis(MotionEvent event, boolean horizontal) {
        int primary = horizontal ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RZ;
        int alternate = horizontal ? MotionEvent.AXIS_RX : MotionEvent.AXIS_RY;
        InputDevice device = event.getDevice();
        if (device != null && ControllerSettings.prefs != null) {
            int layout = ControllerSettings.prefs.getInt(ControllerSettings.prefix(device) + "axes", 0);
            if (layout == 1) return primary;
            if (layout == 2) return alternate;
        }
        if (device != null && device.getMotionRange(primary) == null
                && device.getMotionRange(alternate) != null) return alternate;
        return primary;
    }
}
