# Example 15: Reference Aliasing & Mutability

This example demonstrates reference aliasing, where multiple reference variables refer to the same object on the heap.

## Concepts Illustrated

- **Reference Aliasing**: `Person friend = alice;` copies the reference value, so both variables refer to the same `Person` object.
- **Heap Mutation**: Calling `friend.haveBirthday()` updates that object's `age`, which is also observable through `alice`.
- **Array Mutation**: Assigning `scores[0] = 100` changes an array component.

## Files

- `cs1302/aliasing/Person.java`: Person class with mutable `age`.
- `cs1302/aliasing/Driver.java`: Demonstrates reference aliasing and mutation.
