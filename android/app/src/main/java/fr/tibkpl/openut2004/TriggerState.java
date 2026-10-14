package fr.tibkpl.openut2004;

/** Merge the digital and analog reports of each trigger into one held action. */
final class TriggerState {
    private final boolean[] buttons = new boolean[2];
    private final boolean[] axes = new boolean[2];
    boolean button(int trigger, boolean down) {
        buttons[trigger] = down;
        return buttons[trigger] || axes[trigger];
    }
    boolean axis(int trigger, float value) {
        if (value > 0.5f) axes[trigger] = true;
        else if (value < 0.25f) axes[trigger] = false;
        return buttons[trigger] || axes[trigger];
    }
    void clear() {
        for (int trigger = 0; trigger < 2; trigger++) buttons[trigger] = axes[trigger] = false;
    }
}
