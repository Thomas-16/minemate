package io.github.thomas16.minemate.planner;

/**
 * Why a standing order is being closed. Unlike the old plan disposition, this never describes an
 * action reply: work is a {@link Plan}, and a close is a close.
 */
public enum OrderCloseStatus {
    /** The order's work is done; for deliver-to-owner contracts the ledger must prove it. */
    COMPLETED,
    /** The player changed their mind: neither blocked nor unsupported, just no longer wanted. */
    CANCELLED,
    UNSUPPORTED,
    BLOCKED
}
