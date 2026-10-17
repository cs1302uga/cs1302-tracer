package cs1302.tracer.internal.model;

import org.apiguardian.api.API;

/**
 * Binary encoding of a captured primitive value, independent of guest memory layout.
 *
 * <p>The <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md#encoding-definitions-and-references">
 * encoding reference</a> defines the descriptor contract and distinguishes normative specifications
 * from informative explanations. Java primitive representations are specified by
 * <a href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2">JLS §4.2</a>.
 * A {@code char} describes one UTF-16 code unit, including an isolated surrogate. Boolean
 * {@code "0"}/{@code "1"} strings are a tracer convention and make no JVM storage-size claim.</p>
 *
 * @param type Primitive Java type.
 * @param encoding Interpretation of the bits.
 * @param bits Fixed-width binary digits, most-significant first.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record PrimitiveBinary(String type, String encoding, String bits) {} // PrimitiveBinary
