package fr.tibkpl.openut2004;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import java.util.HashSet;

/** Clear held input when a controller is connected, removed or reconfigured. */
final class ControllerMonitor implements InputManager.InputDeviceListener {
    private final InputManager manager;
    private final Runnable reset;
    private final HashSet<Integer> devices = new HashSet<>();
    ControllerMonitor(Context context, Runnable reset) {
        this.reset = reset;
        manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        for (int id : InputDevice.getDeviceIds()) {
            if (ControllerInput.isController(InputDevice.getDevice(id))) devices.add(id);
        }
        manager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
    }
    @Override public void onInputDeviceAdded(int id) {
        if (ControllerInput.isController(InputDevice.getDevice(id))) {
            devices.add(id);
            reset.run();
        }
    }
    @Override public void onInputDeviceRemoved(int id) {
        if (devices.remove(id)) reset.run();
    }
    @Override public void onInputDeviceChanged(int id) {
        boolean known = devices.contains(id);
        boolean controller = ControllerInput.isController(InputDevice.getDevice(id));
        if (controller) devices.add(id); else devices.remove(id);
        if (known || controller) reset.run();
    }
    void close() { manager.unregisterInputDeviceListener(this); }
}
