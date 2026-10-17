# Example 12: Static Helper Methods & Class Organization

This example demonstrates static utility classes and method invocation across files without instantiating objects.

## Concepts Illustrated

- **Static Methods**: Calling `Calculator.multiply(4, 3)`, which computes the product by repeated addition.
- **Stack Frame Separation**: Calling static methods creates distinct stack frames without `this` references.

## Files

- `cs1302/math/Calculator.java`: Static math utility methods.
- `cs1302/math/Driver.java`: Driver exercising `Calculator`.
