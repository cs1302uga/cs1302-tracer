# Example 19: Custom Generic Classes & Type Resolution

This example demonstrates custom generic classes with parameterized types and Tracer's AST static type resolution (Chapter 10: Generics).

## Concepts Illustrated

- **Custom Generic Classes**: Parameterized generic class `Pair<K, V>`.
- **Type Arguments**: Instantiating `Pair<String, Integer>` and `Pair<Integer, Boolean>`.
- **Reified Metadata**: Available source declarations and runtime type evidence inform generic labels in heap metadata. These labels do not make erased generic arguments available through Java reflection.

## Files

- `cs1302/generics/Pair.java`: Generic pair implementation.
- `cs1302/generics/Driver.java`: Driver instantiating multiple specialized generic pairs.
