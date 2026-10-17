# Example 30: Non-Numeric Primitive Wrapper Classes

This example demonstrates the heap layout and visualization of non-numeric primitive wrapper class types in Java: `Boolean` and `Character`.

## Concepts Illustrated

- **Non-Numeric Autoboxing**: Automatic conversion from boolean and character primitive literals to their corresponding `java.lang.Boolean` and `java.lang.Character` wrapper objects.
- **Heap Object Representation**: Trusted Python Tutor output represents each wrapper as an `INSTANCE` tuple with a `value` field and `boolean` or `char` type metadata. Modern output uses `kind: "box"` with the wrapper type and primitive `value`. `FIELDS` inspection instead exposes the wrapper's captured fields.
- **Object Reference Array (`Object[]`)**: An array of `Object` references pointing to instances of `Boolean` and `Character`, illustrating polymorphic reference containment.

## Files

- `cs1302/wrappers/other/Driver.java`: Main driver demonstrating non-numeric wrapper instances and array references.
