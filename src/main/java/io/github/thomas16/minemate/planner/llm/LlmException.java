package io.github.thomas16.minemate.planner.llm;

import java.util.Set;

/**
 * A typed provider failure. Carries enough to decide whether retrying could possibly help, and
 * nothing that could contain a credential.
 */
public final class LlmException extends RuntimeException {

    /** Nothing in here is fixed by asking again; retrying only burns the request budget. */
    private static final Set<String> TERMINAL_CODES = Set.of(
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded");

    /** Not an HTTP failure: the transport answered, but the body made no sense. */
    public static final int NO_STATUS = 0;

    private final int status;
    private final String code;

    public LlmException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code == null ? "" : code;
    }

    public static LlmException malformed(String message) {
        return new LlmException(NO_STATUS, "malformed_response", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        if (TERMINAL_CODES.contains(code)) {
            return false;
        }
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    @Override
    public String getMessage() {
        String detail = super.getMessage() == null ? "" : super.getMessage();
        if (status == NO_STATUS) {
            return detail;
        }
        return code.isEmpty() ? status + ": " + detail : status + " " + code + ": " + detail;
    }
}
