package io.github.thomas16.minemate.task;

/** Controlled failure discovered while materializing a deferred PlayerEngine task. */
public final class GoalStartException extends RuntimeException {
    private final TaskTerminalResult result;

    public GoalStartException(TaskTerminalResult result) {
        super(result.reason());
        this.result = result;
    }

    public TaskTerminalResult result() {
        return result;
    }
}
