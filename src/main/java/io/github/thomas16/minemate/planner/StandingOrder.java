package io.github.thomas16.minemate.planner;

/**
 * The owner order a companion is still working on, and how much planning budget it has left.
 *
 * <p>This is what makes replanning bounded. A plan finishing arms a replan trigger, and without a
 * standing order to charge the call against there would be nothing to stop the companion planning
 * about its own last plan indefinitely. Server thread only, like everything else the executor
 * touches.
 */
public final class StandingOrder {

    private final String requestText;
    private final long openedAtGameTime;
    private int replansUsed;

    public StandingOrder(String requestText, long openedAtGameTime) {
        this.requestText = requestText == null ? "" : requestText;
        this.openedAtGameTime = openedAtGameTime;
    }

    public String requestText() {
        return requestText;
    }

    public long openedAtGameTime() {
        return openedAtGameTime;
    }

    /** Replans spent so far; the owner's own request is not charged against the budget. */
    public int replansUsed() {
        return replansUsed;
    }

    public boolean canReplan(int max) {
        return replansUsed < max;
    }

    public void consumeReplan() {
        replansUsed++;
    }

    @Override
    public String toString() {
        return "\"" + requestText + "\" (" + replansUsed + " replans)";
    }
}
