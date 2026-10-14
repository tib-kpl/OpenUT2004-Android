package fr.tibkpl.openut2004;

/** Run with javac/java; no Android runtime or external test dependency required. */
public final class TriggerStateTest {
    private static void expect(boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError("Unexpected trigger state");
    }
    public static void main(String[] args) {
        TriggerState state = new TriggerState();
        // Analog-only Xbox-style trigger: hysteresis avoids jitter around the press threshold.
        expect(state.axis(0, 0), false);
        expect(state.axis(0, 0.6f), true);
        expect(state.axis(0, 0.4f), true);
        expect(state.axis(0, 0.2f), false);
        // Digital-only controller.
        expect(state.button(1, true), true);
        expect(state.axis(1, 0), true);
        expect(state.button(1, false), false);
        // PlayStation-style: full-press key release must not cancel a still-held analog trigger.
        expect(state.axis(0, 0.7f), true);
        expect(state.button(0, true), true);
        expect(state.button(0, false), true);
        expect(state.axis(0, 0.3f), true);
        expect(state.axis(0, 0), false);
        // Independent triggers, including mixed digital/analog input.
        expect(state.button(0, true), true);
        expect(state.axis(1, 0.8f), true);
        expect(state.button(0, false), false);
        expect(state.axis(1, 0.3f), true);
        // A disconnected device must not leave state behind when switching to another controller.
        state.clear();
        expect(state.axis(1, 0.3f), false);
        expect(state.axis(0, 0), false);
        expect(state.button(1, true), true);
        expect(state.button(1, false), false);
        System.out.println("TriggerState: analog, digital, mixed, independent and reconnect cases passed");
    }
}
