# Example 28: Empty Strings and Zero-Length Array Instances

This example demonstrates the heap layout and visualization of empty string instances (`""`) and zero-length array instances (`new T[0]`) alongside non-empty counterparts in Java.

## Concepts Illustrated

- **Empty String Object (`""`)**: An instantiated `java.lang.String` object representing a zero-length character sequence. The trace preserves its empty string value and object identity.
- **Zero-Length Primitive Array (`new int[0]`)**: A valid array instance created with length `0`. The trace preserves the array object with no elements.
- **Zero-Length Reference Array (`new String[0]`)**: A zero-length array of object references. The trace preserves its array type and object identity.
- **Non-Empty Comparisons**: Non-empty strings and populated arrays provide comparisons for consuming visualizers.

## Files

- `cs1302/empty/Driver.java`: Main driver class demonstrating empty strings, zero-length primitive arrays, and zero-length reference arrays.
