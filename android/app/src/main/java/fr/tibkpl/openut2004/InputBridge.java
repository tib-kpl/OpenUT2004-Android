package fr.tibkpl.openut2004;

import android.util.Log;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Convertit manette et clavier Android en clavier/souris pour le moteur, injectes dans
 * SDL et l'ecran X virtuel par libutbridge.so : le jeu et ses menus les recoivent comme un
 * vrai clavier et une vraie souris. Format des messages : voir runtime/utbridge/utbridge.c.
 *
 * Disposition (touches par defaut d'UT2004) : stick gauche = ZQSD/WASD, stick droit = souris,
 * R2 = tir (clic gauche), L2 = tir secondaire (clic droit), A = saut, B = retour (Echap),
 * X = utiliser, Y = translocateur, L1/R1 = arme precedente/suivante, Start = menu,
 * Select = scores, L3 = accroupi, R3 = meilleure arme, croix = fleches.
 */
final class InputBridge {

    private static final int MSG_KEY = 1;
    private static final int MSG_BUTTON = 2;
    private static final int MSG_MOTION = 3;
    private static final int MSG_PAUSE = 4;
    private static final int MSG_POSITION = 5;
    private static final int MSG_TEXT = 6;

    // Keysyms X11
    private static final int XK_BACKSPACE = 0xff08, XK_TAB = 0xff09, XK_RETURN = 0xff0d,
            XK_BACK_TAB = 0xfe20,
            XK_ESCAPE = 0xff1b, XK_LEFT = 0xff51, XK_UP = 0xff52, XK_RIGHT = 0xff53, XK_DOWN = 0xff54,
            XK_F1 = 0xffbe, XK_SHIFT_L = 0xffe1, XK_CONTROL_L = 0xffe3, XK_ALT_L = 0xffe9,
            XK_DELETE = 0xffff, XK_PAGE_UP = 0xff55, XK_PAGE_DOWN = 0xff56, XK_HOME = 0xff50, XK_END = 0xff57;

    // Boutons souris X11
    private static final int MOUSE_LEFT = 1, MOUSE_RIGHT = 3, WHEEL_UP = 4, WHEEL_DOWN = 5;

    /** Boutons de manette -> touche (keysym) ou bouton souris (valeur negative). */
    private static final SparseIntArray PAD = new SparseIntArray();
    /** Clavier physique -> keysym. */
    private static final SparseIntArray KEYS = new SparseIntArray();

    static {
        PAD.put(KeyEvent.KEYCODE_BUTTON_A, ' ');          // saut
        PAD.put(KeyEvent.KEYCODE_BUTTON_B, XK_ESCAPE);    // retour (menus) / menu (jeu)
        PAD.put(KeyEvent.KEYCODE_BUTTON_X, 'e');          // utiliser
        PAD.put(KeyEvent.KEYCODE_BUTTON_Y, 'q');          // translocateur
        PAD.put(KeyEvent.KEYCODE_BUTTON_START, XK_ESCAPE); // menu
        PAD.put(KeyEvent.KEYCODE_BUTTON_SELECT, XK_F1);   // scores
        PAD.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 'c');     // accroupi
        PAD.put(KeyEvent.KEYCODE_BUTTON_THUMBR, 'g');     // meilleure arme
        PAD.put(KeyEvent.KEYCODE_BUTTON_R2, -MOUSE_LEFT);  // tir
        PAD.put(KeyEvent.KEYCODE_BUTTON_L2, -MOUSE_RIGHT); // tir secondaire
        PAD.put(KeyEvent.KEYCODE_DPAD_UP, XK_UP);
        PAD.put(KeyEvent.KEYCODE_DPAD_DOWN, XK_DOWN);
        PAD.put(KeyEvent.KEYCODE_DPAD_LEFT, XK_LEFT);
        PAD.put(KeyEvent.KEYCODE_DPAD_RIGHT, XK_RIGHT);
        PAD.put(KeyEvent.KEYCODE_BUTTON_L1, -WHEEL_UP);    // arme precedente
        PAD.put(KeyEvent.KEYCODE_BUTTON_R1, -WHEEL_DOWN);  // arme suivante

        for (int i = 0; i < 26; i++) KEYS.put(KeyEvent.KEYCODE_A + i, 'a' + i);
        for (int i = 0; i <= 9; i++) KEYS.put(KeyEvent.KEYCODE_0 + i, '0' + i);
        for (int i = 0; i < 12; i++) KEYS.put(KeyEvent.KEYCODE_F1 + i, XK_F1 + i);
        KEYS.put(KeyEvent.KEYCODE_SPACE, ' ');
        KEYS.put(KeyEvent.KEYCODE_ENTER, XK_RETURN);
        KEYS.put(KeyEvent.KEYCODE_ESCAPE, XK_ESCAPE);
        KEYS.put(KeyEvent.KEYCODE_BACK, XK_ESCAPE);
        KEYS.put(KeyEvent.KEYCODE_DEL, XK_BACKSPACE);
        KEYS.put(KeyEvent.KEYCODE_FORWARD_DEL, XK_DELETE);
        KEYS.put(KeyEvent.KEYCODE_TAB, XK_TAB);
        KEYS.put(KeyEvent.KEYCODE_SHIFT_LEFT, XK_SHIFT_L);
        KEYS.put(KeyEvent.KEYCODE_SHIFT_RIGHT, XK_SHIFT_L);
        KEYS.put(KeyEvent.KEYCODE_CTRL_LEFT, XK_CONTROL_L);
        KEYS.put(KeyEvent.KEYCODE_CTRL_RIGHT, XK_CONTROL_L);
        KEYS.put(KeyEvent.KEYCODE_ALT_LEFT, XK_ALT_L);
        KEYS.put(KeyEvent.KEYCODE_DPAD_UP, XK_UP);
        KEYS.put(KeyEvent.KEYCODE_DPAD_DOWN, XK_DOWN);
        KEYS.put(KeyEvent.KEYCODE_DPAD_LEFT, XK_LEFT);
        KEYS.put(KeyEvent.KEYCODE_DPAD_RIGHT, XK_RIGHT);
        KEYS.put(KeyEvent.KEYCODE_PAGE_UP, XK_PAGE_UP);
        KEYS.put(KeyEvent.KEYCODE_PAGE_DOWN, XK_PAGE_DOWN);
        KEYS.put(KeyEvent.KEYCODE_MOVE_HOME, XK_HOME);
        KEYS.put(KeyEvent.KEYCODE_MOVE_END, XK_END);
        KEYS.put(KeyEvent.KEYCODE_MINUS, '-');
        KEYS.put(KeyEvent.KEYCODE_EQUALS, '=');
        KEYS.put(KeyEvent.KEYCODE_COMMA, ',');
        KEYS.put(KeyEvent.KEYCODE_PERIOD, '.');
        KEYS.put(KeyEvent.KEYCODE_SLASH, '/');
        KEYS.put(KeyEvent.KEYCODE_SEMICOLON, ';');
        KEYS.put(KeyEvent.KEYCODE_APOSTROPHE, '\'');
        KEYS.put(KeyEvent.KEYCODE_GRAVE, '`');
    }

    /** Vitesse du curseur au stick droit enfonce a fond (pixels par 1/120 s). */
    private static final float AIM_SPEED = 22f;
    private static final float STICK_DEADZONE = 0.15f;
    private static final float MOVE_THRESHOLD = 0.4f;

    private final ServerSocket server;
    interface ModeListener { void onModeChanged(boolean menu); }
    private final ModeListener modeListener;
    private volatile Socket activeSocket;
    private final Thread sender;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    private volatile byte[] lastPause;

    // Etat de la manette
    private volatile float aimX, aimY;
    private volatile boolean menuMode = true;
    private int pointerWidth = 1280, pointerHeight = 720;
    private volatile int pointerX = 640, pointerY = 360;
    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private int touchGesture;
    private final SparseIntArray heldPadKeys = new SparseIntArray();
    private final int[] heldMoveKeys = new int[4];
    private final boolean[] moveKeys = new boolean[4]; // w, s, a, d
    private final boolean[] triggers = new boolean[2]; // L2, R2 (axes analogiques)
    private final TriggerState triggerState = new TriggerState();
    private int hatX, hatY;

    static boolean debug;

    InputBridge(ModeListener modeListener) throws IOException {
        this.modeListener = modeListener;
        // IPv4 explicite : sur Android, getLoopbackAddress() renvoie ::1 (IPv6)
        server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        sender = new Thread(this::sendLoop, "ut2004-input");
        sender.setDaemon(true);
        sender.start();
        Thread aim = new Thread(this::aimLoop, "ut2004-aim");
        aim.setDaemon(true);
        aim.start();
    }

    int port() {
        return server.getLocalPort();
    }

    boolean isMenuMode() { return menuMode; }

    synchronized void setPointerBounds(int width, int height) {
        pointerWidth = width;
        pointerHeight = height;
        pointerX = width / 2;
        pointerY = height / 2;
    }

    float pointerXFraction() { return (float) pointerX / pointerWidth; }
    float pointerYFraction() { return (float) pointerY / pointerHeight; }

    private synchronized void updatePointer(int type, int value) {
        if (type == MSG_MOTION) {
            pointerX = Math.max(0, Math.min(pointerWidth - 1, pointerX + (short)value));
            pointerY = Math.max(0, Math.min(pointerHeight - 1, pointerY + (short)(value >>> 16)));
        } else if (type == MSG_POSITION) {
            pointerX = value & 0xffff;
            pointerY = (value >>> 16) & 0xffff;
        }
    }

    void sendText(String text) {
        text.codePoints().forEach(c -> send(MSG_TEXT, 0, c));
    }

    boolean onTouch(MotionEvent event, int width, int height, int viewWidth, int viewHeight) {
        if (!menuMode || viewWidth <= 0 || viewHeight <= 0) return false;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return true;
        if (action != MotionEvent.ACTION_CANCEL) {
            int x = Math.max(0, Math.min(width - 1, (int)(event.getX() * width / viewWidth)));
            int y = Math.max(0, Math.min(height - 1, (int)(event.getY() * height / viewHeight)));
            send(MSG_POSITION, 0, (x & 0xffff) | (y << 16));
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_CANCEL) touchGesture++;
        if (action == MotionEvent.ACTION_UP) {
            // UT updates its GUI cursor after draining SDL events. Let it process the
            // position before clicking, otherwise the old control receives the click.
            int gesture = touchGesture;
            touchHandler.postDelayed(() -> {
                if (!closed && menuMode && gesture == touchGesture) click(MOUSE_LEFT);
            }, 100);
        }
        return true;
    }

    void setMenuMode(boolean enabled) {
        touchGesture++;
        if (hatX != 0) send(MSG_KEY, hatKey(hatX, true), 0);
        if (hatY != 0) send(MSG_KEY, hatKey(hatY, false), 0);
        hatX = hatY = 0;
        // Release the old bindings before changing what the sticks/buttons mean.
        aimX = aimY = 0;
        for (int i = 0; i < moveKeys.length; i++) {
            if (moveKeys[i]) send(MSG_KEY, heldMoveKeys[i], 0);
            moveKeys[i] = false;
        }
        for (int i = 0; i < heldPadKeys.size(); i++) {
            int mapped = heldPadKeys.valueAt(i);
            if (mapped > 0) send(MSG_KEY, mapped, 0);
            else if (mapped == -MOUSE_LEFT || mapped == -MOUSE_RIGHT) send(MSG_BUTTON, -mapped, 0);
        }
        heldPadKeys.clear();
        menuMode = enabled;
    }

    void resetController() {
        setMenuMode(menuMode);
        triggerState.clear();
        for (int trigger = 0; trigger < 2; trigger++) setTrigger(trigger, false);
    }

    void close() {
        closed = true;
        touchHandler.removeCallbacksAndMessages(null);
        sender.interrupt();
        try { if (activeSocket != null) activeSocket.close(); } catch (IOException ignored) { }
        try {
            server.close();
        } catch (IOException ignored) {
            // deja fermee
        }
    }

    /** Fige (true) ou relance (false) la boucle du jeu. */
    void setPaused(boolean paused) {
        if (paused) {
            resetController();
        }
        send(MSG_PAUSE, 0, paused ? 1 : 0);
    }

    /** @return true si la touche a ete transmise au jeu. */
    boolean onKey(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        boolean down = action == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() > 0) return true;
        int keyCode = ControllerInput.key(event);

        if (isGamepad(event.getSource()) || ControllerInput.isController(event.getDevice()) || ControllerInput.isButton(keyCode)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                int trigger = keyCode == KeyEvent.KEYCODE_BUTTON_R2 ? 1 : 0;
                setTrigger(trigger, triggerState.button(trigger, down));
                return true;
            }
            int mapped = PAD.get(keyCode, 0);
            if (menuMode && keyCode == KeyEvent.KEYCODE_DPAD_UP) mapped = XK_BACK_TAB;
            if (menuMode && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) mapped = XK_TAB;
            if (menuMode && keyCode == KeyEvent.KEYCODE_BUTTON_A) mapped = XK_RETURN;
            if (menuMode && keyCode == KeyEvent.KEYCODE_BUTTON_X) mapped = XK_TAB;
            if (!down && heldPadKeys.indexOfKey(keyCode) >= 0) {
                mapped = heldPadKeys.get(keyCode);
                heldPadKeys.delete(keyCode);
            } else if (down && mapped > 0) {
                heldPadKeys.put(keyCode, mapped);
            }
            if (mapped != 0) {
                if (mapped == -WHEEL_UP || mapped == -WHEEL_DOWN) {
                    if (down) click(-mapped); // molette : un cran par appui
                } else if (mapped < 0) {
                    send(MSG_BUTTON, -mapped, down ? 1 : 0);
                } else {
                    send(MSG_KEY, mapped, down ? 1 : 0);
                }
                return true;
            }
        }
        int keysym = KEYS.get(keyCode, 0);
        if (keysym != 0) {
            send(MSG_KEY, keysym, down ? 1 : 0);
            return true;
        }
        return false;
    }

    /** Sticks, gachettes analogiques et croix "hat". */
    boolean onMotion(MotionEvent event) {
        if ((!isGamepad(event.getSource()) && !ControllerInput.isController(event.getDevice()))
                || event.getAction() != MotionEvent.ACTION_MOVE) return false;

        // Stick gauche -> W/S/A/D
        float lx = deadzone(event, MotionEvent.AXIS_X);
        float ly = deadzone(event, MotionEvent.AXIS_Y);
        setMoveKey(0, menuMode ? XK_UP : 'w', ly < -MOVE_THRESHOLD);
        setMoveKey(1, menuMode ? XK_DOWN : 's', ly > MOVE_THRESHOLD);
        setMoveKey(2, menuMode ? XK_LEFT : 'a', lx < -MOVE_THRESHOLD);
        setMoveKey(3, menuMode ? XK_RIGHT : 'd', lx > MOVE_THRESHOLD);

        // Stick droit -> souris (deplacement continu, voir aimLoop)
        aimX = deadzone(event, ControllerInput.rightAxis(event, true));
        aimY = deadzone(event, ControllerInput.rightAxis(event, false));

        // Gachettes : fusion des axes et des touches, sans actions en double.
        analogTrigger(0, Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)));
        analogTrigger(1, Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)));

        // Croix "hat" -> fleches
        int newHatX = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        int newHatY = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (newHatX != hatX) {
            if (hatX != 0) send(MSG_KEY, hatKey(hatX, true), 0);
            if (newHatX != 0) send(MSG_KEY, hatKey(newHatX, true), 1);
            hatX = newHatX;
        }
        if (newHatY != hatY) {
            if (hatY != 0) send(MSG_KEY, hatKey(hatY, false), 0);
            if (newHatY != 0) send(MSG_KEY, hatKey(newHatY, false), 1);
            hatY = newHatY;
        }
        return true;
    }

    private static float deadzone(MotionEvent event, int axis) {
        float v = event.getAxisValue(axis);
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, event.getSource());
        float flat = range == null ? STICK_DEADZONE : Math.max(STICK_DEADZONE, range.getFlat());
        return Math.abs(v) < flat ? 0f : Math.max(-1f, Math.min(1f, v));
    }

    private int hatKey(int direction, boolean horizontal) {
        if (menuMode && !horizontal) return direction < 0 ? XK_BACK_TAB : XK_TAB;
        return horizontal ? (direction < 0 ? XK_LEFT : XK_RIGHT) : (direction < 0 ? XK_UP : XK_DOWN);
    }

    private void setMoveKey(int index, int keysym, boolean pressed) {
        if (moveKeys[index] == pressed) return;
        moveKeys[index] = pressed;
        if (pressed) heldMoveKeys[index] = keysym;
        send(MSG_KEY, heldMoveKeys[index], pressed ? 1 : 0);
    }

    private void analogTrigger(int trigger, float value) {
        setTrigger(trigger, triggerState.axis(trigger, value));
    }

    /** trigger 0 = L2 (clic droit), 1 = R2 (clic gauche). */
    private void setTrigger(int trigger, boolean pressed) {
        if (triggers[trigger] == pressed) return;
        triggers[trigger] = pressed;
        send(MSG_BUTTON, trigger == 1 ? MOUSE_LEFT : MOUSE_RIGHT, pressed ? 1 : 0);
    }

    private void click(int button) {
        send(MSG_BUTTON, button, 1);
        send(MSG_BUTTON, button, 0);
    }

    /** Deplace la souris selon le stick droit, 120 fois par seconde (courbe quadratique). */
    private void aimLoop() {
        float accX = 0f, accY = 0f;
        while (!closed) {
            float x = aimX, y = aimY;
            if (x != 0f || y != 0f) {
                float speed = menuMode ? 6f : AIM_SPEED;
                accX += Math.signum(x) * x * x * speed;
                accY += Math.signum(y) * y * y * speed;
                int dx = (int) accX, dy = (int) accY;
                accX -= dx;
                accY -= dy;
                if (dx != 0 || dy != 0) send(MSG_MOTION, 0, (dx & 0xffff) | (dy << 16));
            } else {
                accX = accY = 0f;
            }
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** Thread d'envoi (pas de reseau sur le thread UI) : un client a la fois, reconnexion possible. */
    private void sendLoop() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                activeSocket = socket;
                socket.setTcpNoDelay(true);
                Thread reader = new Thread(() -> readLoop(socket), "ut2004-mode");
                reader.setDaemon(true);
                reader.start();
                OutputStream out = socket.getOutputStream();
                byte[] pending = lastPause;
                queue.clear();
                if (pending != null) queue.offer(pending); // etat de pause courant
                while (!closed) {
                    out.write(queue.take());
                    out.flush();
                }
            } catch (IOException e) {
                // jeu deconnecte : on attend une nouvelle connexion
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readLoop(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            byte[] message = new byte[8];
            while (!closed) {
                int offset = 0;
                while (offset < message.length) {
                    int count = in.read(message, offset, message.length - offset);
                    if (count < 0) return;
                    offset += count;
                }
                if (message[0] == 7 && modeListener != null) modeListener.onModeChanged(message[4] != 0);
            }
        } catch (IOException ignored) { }
    }

    private void send(int type, int code, int value) {
        if (type == MSG_MOTION || type == MSG_POSITION) updatePointer(type, value);
        if (debug && type != MSG_MOTION) Log.d("OpenUT2004Input", "type=" + type + " code=" + code + " value=" + value);
        byte[] msg = {
                (byte) type, 0, (byte) code, (byte) (code >> 8),
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24),
        };
        if (type == MSG_PAUSE) lastPause = msg;
        queue.offer(msg);
    }
}
