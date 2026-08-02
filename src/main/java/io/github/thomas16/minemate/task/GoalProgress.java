package io.github.thomas16.minemate.task;

/** An immutable, pre-rendered snapshot of progress toward a goal. */
public record GoalProgress(int have, int want, double advance, String text) {

    public boolean complete() {
        return want > 0 && have >= want;
    }

    public boolean openEnded() {
        return want < 0;
    }

    public static GoalProgress counted(int have, int want, String text) {
        return new GoalProgress(have, want, have, text);
    }

    public static GoalProgress approach(double distance, double radius, String text) {
        return new GoalProgress(distance <= radius ? 1 : 0, 1, -distance, text);
    }

    public static GoalProgress openEnded(String text) {
        return new GoalProgress(0, -1, 0.0, text);
    }
}
