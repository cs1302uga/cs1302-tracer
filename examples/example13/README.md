# Example 13: Encapsulation & Mutable State

This example demonstrates encapsulation using private instance variables, getter methods, and methods that update account state.

## Concepts Illustrated

- **Encapsulation**: Private fields `owner` and `balance` protected by methods.
- **Mutator Methods**: `deposit` and `withdraw` modifying heap object fields in place.
- **Return Values**: `withdraw` reports whether the balance was sufficient; this driver ignores that boolean result and prints balances through getter methods.

## Files

- `cs1302/account/Account.java`: Bank account class whose `withdraw` method checks whether the requested amount exceeds the balance. It is a tracing example, not a complete account-validation model.
- `cs1302/account/Driver.java`: Driver creating accounts and performing transactions.
