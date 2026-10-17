# Example 0: Record Instances & Constructors

This example demonstrates how Java 16+ records are instantiated and rendered on the heap.

## Concepts Illustrated

- **Java Records**: Concise syntax for data carriers with final component fields. A record does not make objects referenced by its components immutable.
- **Heap Object Representation**: An instance of `Person` containing components `name` (String reference) and `age` (primitive int).
- **Constructor Invocation**: Step-by-step frame initialization in `main`.

## Files

- `Driver.java`: Instantiates `Person alice = new Person("Alice", 42)`.
