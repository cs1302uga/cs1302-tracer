package cs1302.tracer.internal.model;

import org.apiguardian.api.API;

/**
 * Binary encoding of a captured primitive value, independent of guest memory layout.
 *
 * @param type Primitive Java type.
 * @param encoding Interpretation of the bits.
 * @param bits Fixed-width binary digits, most-significant first.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record PrimitiveBinary(String type, String encoding, String bits) {} // PrimitiveBinary
