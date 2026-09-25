package cs1302.tracer.execution;

/** Controlled rejection of a representation that cannot be processed safely. */
public final class NestingException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a rejection with a machine-readable reason.
     * @param reason Nesting or inline-cycle reason.
     */
    public NestingException(String reason) {
        super(reason);
    } // NestingException
} // NestingException
