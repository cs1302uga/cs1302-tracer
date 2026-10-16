package cs1302.tracer.internal.execution;

import org.apiguardian.api.API;

/** Controlled rejection of a representation that cannot be processed safely. */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class NestingException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a rejection with a machine-readable reason.
     *
     * @param reason Nesting or inline-cycle reason.
     */
    public NestingException(String reason) {
        super(reason);
    } // NestingException
} // NestingException
