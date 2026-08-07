package io.github.thomas16.minemate.planner;

/**
 * Why a model request was made. Origins have different deterministic semantics: owner speech may
 * be conversation, while the continuation and repair forms belong to one pending response attempt
 * for an open order and must produce work or a close, never small talk.
 */
public enum RequestOrigin {
    /** A fresh owner utterance; today via {@code /companion plan}, later via chat. */
    OWNER_CHAT,
    /** A replan trigger continuing the captured standing order. */
    AUTONOMOUS_REPLAN,
    /** The free follow-up after a contract arrived without world calls. */
    CONTRACT_CONTINUATION,
    /** The one bounded repair after a rejected reply. */
    REPAIR
}
