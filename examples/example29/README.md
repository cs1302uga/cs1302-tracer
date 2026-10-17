# Example 29: Numeric Primitive Wrapper Classes

This example demonstrates the heap layout and visualization of all six numeric primitive wrapper class types in Java: `Byte`, `Short`, `Integer`, `Long`, `Float`, and `Double`.

## Concepts Illustrated

- **Numeric Autoboxing**: Automatic conversion from primitive numeric literals to their corresponding `java.lang.Number` wrapper objects.
- **Heap Object Representation**: Trusted Python Tutor output represents each numeric wrapper as an `INSTANCE` tuple with a `value` field and primitive type metadata. Modern output uses `kind: "box"` with the wrapper type and primitive `value`. `FIELDS` inspection instead exposes the wrapper's captured fields.
- **Polymorphic Number Array (`Number[]`)**: An array of `Number` references pointing to instances of distinct numeric wrapper classes, illustrating common superclass reference polymorphism.

## Files

- `cs1302/wrappers/numeric/Driver.java`: Main driver demonstrating numeric wrapper instances and array references.
