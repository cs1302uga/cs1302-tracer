# Primitive binary metadata

Build the JAR, then capture the final state with binary metadata enabled:

```sh
./mvnw -B -ntp package
java -jar target/code-tracer-jar-with-dependencies.jar trace -i examples/primitive-bits/Main.java -f modern --include-primitive-bits
```

Use `-f pytutor` for the same metadata in Python Tutor attributes, or add `-a` to
observe changes chronologically. Omit `--include-primitive-bits` to retain the
existing output without metadata. The flag works with ordinary and envelope output.

The [encoding definitions and references](../../docs/reference/output.md#encoding-definitions-and-references)
provide normative specifications and informative explanations for the representations below.

Look for:

- `delta`: byte -1 is eight ones, not a sign-extended 32-bit int.
- `exact`: the 64-bit string retains a long beyond exact JavaScript-number precision.
- `negativeZero`: the float sign bit distinguishes -0.0 from +0.0.
- `fraction`: double 1.5 uses IEEE 754 binary64, not the integer encoding of 1.
- `ready`: `"1"` is the documented logical encoding; it does not imply a one-bit JVM allocation.
- `values`: `elementBinary` aligns with the three existing array elements.
- `boxed`: the local refers to a heap object; metadata describes the boxed payload there.
- `object.letter` and `minimum`: instance and static primitive fields also carry descriptors.

For Python Tutor, array metadata lives in `heap_attrs[id].elementBinary`; ordinary
object field metadata lives in `fieldBinary`, aligned with field order. Under
`--result-envelope --inspection FIELDS`, wrappers are ordinary objects and their
primitive fields carry metadata. See the [output reference](../../docs/reference/output.md#optional-primitive-binary-metadata)
for exact placement and floating-point limitations.
