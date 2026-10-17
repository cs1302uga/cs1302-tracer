# Example 24: Generic Integer Lists & Autoboxing (List<Integer> & ArrayList<Integer>)

This example demonstrates standard library generic lists (`List<Integer>` and `ArrayList<Integer>`), primitive autoboxing, in-place element mutation, and polymorphic method parameter passing.

## Concepts Illustrated

- **Interface vs. Implementation References**: Declaring variables with the interface type (`List<Integer> primes = new ArrayList<>();`) versus concrete class type (`ArrayList<Integer> scores = new ArrayList<>();`).
- **Autoboxing and Boxed Heap Primitives**: Adding primitive `int` values (`add(2)`, `add(10)`) converts them to `java.lang.Integer` references. Boxing can reuse cached wrapper instances; it does not guarantee a new object for each conversion.
- **In-Place Element Mutation**: Mutating existing list slots via `set(i, newValue)`.
- **Subtype Polymorphism via Method Parameters**: Passing an `ArrayList<Integer>` object into a helper method expecting interface type `List<Integer>`.
- **Generic Type Labels**: Source-declared variable and parameter types retain `Integer` arguments, while heap labels describe the runtime `ArrayList` implementation using available generic evidence.

## Files

- `cs1302/list/Driver.java`: Instantiates lists, populates elements, and mutates values via helper method.
