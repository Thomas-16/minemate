package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.task.Reasons;

/** The result of checking a {@link ToolCall} against the registry schema. */
public sealed interface ToolValidation {

    /** The call names a real tool and every argument is present, typed, and in range. */
    record Accepted(ToolDescriptor tool, ToolArguments arguments) implements ToolValidation {
    }

    /**
     * The call was refused before any task could be submitted.
     *
     * @param reason a machine-readable {@link Reasons} token
     * @param detail the offending name or value, for owner-facing text and logs
     */
    record Rejected(String reason, String detail) implements ToolValidation {
    }
}
