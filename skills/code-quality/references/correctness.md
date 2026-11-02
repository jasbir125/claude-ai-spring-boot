# Correctness Checks

General Java pitfalls. They are not project rules, so they are listed only here.

## Missing values
- A chain of getters where any step may be `null`.
- `Optional.get()` without a check; `orElseThrow(...)` says what went wrong.
- A method that returns `null` where an `Optional` or an empty collection would be clearer.

## Exceptions
- An empty `catch`, or one that only logs and carries on as if nothing happened.
- Catching `Exception` or `Throwable` where a specific type is meant.
- Wrapping without the cause: `new IllegalStateException(e.getMessage())` loses the stack trace.

```java
// loses where it came from
} catch (IOException e) {
    throw new IllegalStateException(e.getMessage());
}

// keeps it
} catch (IOException e) {
    throw new ImportFailedException("Could not read " + file, e);
}
```

## Resources
- Streams, readers, or connections closed by hand instead of try-with-resources.

## Transactions
- Several writes that must succeed together but run without `@Transactional`; reads without `readOnly = true` (`conventions`, Quality Bar).
- A `@Transactional` or `@Async` method called from the same class (`this.method()`), which skips the Spring proxy so the annotation has no effect.

## Lost updates and lazy data
The rules are in `conventions` and `spring-boot` → `references/data.md`. Look for:
- An update endpoint that ignores the client's `version`, or code that catches `OptimisticLockingFailureException` inside the transaction (it is thrown at commit, too late to catch there).
- Lazy collections read after the transaction ended, or DTOs built outside the service method while `open-in-view` is off.
