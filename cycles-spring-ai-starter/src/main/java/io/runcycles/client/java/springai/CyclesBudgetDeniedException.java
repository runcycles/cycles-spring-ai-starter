package io.runcycles.client.java.springai;

/**
 * Thrown when the Cycles server denies a Spring AI ChatClient invocation
 * (e.g. budget exhausted or scope-cap exceeded).
 *
 * <p>The exception carries the Cycles {@code reasonCode} and {@code scopePath} from
 * the denial response so callers can log them, present them to users, or branch on
 * specific failure modes (rate limit vs hard cap, etc.).
 *
 * <p>This is a {@link RuntimeException} so it does not appear in checked-exception
 * signatures; Spring AI advisor chains do not declare checked exceptions.
 */
public class CyclesBudgetDeniedException extends RuntimeException {

    /** Reason code from the Cycles denial (e.g. BUDGET_EXCEEDED). */
    private final String reasonCode;

    /** Scope path that triggered the denial. */
    private final String scopePath;

    /**
     * Constructs a denial exception with reason code and scope path.
     *
     * @param message    human-readable message.
     * @param reasonCode Cycles reason code (e.g. {@code BUDGET_EXCEEDED}), may be null.
     * @param scopePath  budget scope path that was over-limit, may be null.
     */
    public CyclesBudgetDeniedException(String message, String reasonCode, String scopePath) {
        super(message);
        this.reasonCode = reasonCode;
        this.scopePath = scopePath;
    }

    /**
     * Returns the Cycles reason code from the denial response.
     *
     * @return the reason code, or null if not provided.
     */
    public String getReasonCode() { return reasonCode; }

    /**
     * Returns the scope path that triggered the denial.
     *
     * @return the scope path, or null if not provided.
     */
    public String getScopePath() { return scopePath; }
}
