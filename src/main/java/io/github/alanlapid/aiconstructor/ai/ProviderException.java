package io.github.alanlapid.aiconstructor.ai;

/** A failed provider call, classified so the generator knows whether to retry, skip the model or give up. */
public final class ProviderException extends Exception {

    public enum Kind {
        /** Temporary: overloaded, rate limited, network error, truncated answer. Try again / next model. */
        RETRY,
        /** This model cannot be used (unknown, not available for this key). Skip it. */
        SKIP_MODEL,
        /** Configuration problem (bad key, bad request). Retrying will not help. */
        FATAL
    }

    /** Short reason key for players (see lang reason-*), independent of the technical message. */
    public enum Reason {
        OVERLOADED, QUOTA, UNAVAILABLE, NETWORK, INVALID, OTHER
    }

    private final Kind kind;
    private final Reason reason;

    public ProviderException(Kind kind, Reason reason, String message) {
        super(message);
        this.kind = kind;
        this.reason = reason;
    }

    public Kind kind() {
        return kind;
    }

    public Reason reason() {
        return reason;
    }

    /** Maps an HTTP status from any provider to the right retry behaviour. */
    public static ProviderException fromStatus(int status, String detail) {
        return switch (status) {
            case 401, 403 -> new ProviderException(Kind.FATAL, Reason.OTHER,
                    "The API key is invalid or not allowed (HTTP " + status + "): " + detail);
            case 400, 422 -> new ProviderException(Kind.FATAL, Reason.OTHER, "Request rejected (HTTP " + status + "): " + detail);
            case 404 -> new ProviderException(Kind.SKIP_MODEL, Reason.UNAVAILABLE, "Model not found (HTTP 404): " + detail);
            case 408, 409, 429 -> new ProviderException(Kind.RETRY, status == 429 ? Reason.QUOTA : Reason.OVERLOADED,
                    "HTTP " + status + ": " + detail);
            default -> status >= 500
                    ? new ProviderException(Kind.RETRY, Reason.OVERLOADED, "HTTP " + status + ": " + detail)
                    : new ProviderException(Kind.FATAL, Reason.OTHER, "HTTP " + status + ": " + detail);
        };
    }
}
