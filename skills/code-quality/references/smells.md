# Code Smells

Hints, not violations. Word each one as "possible …", quote the lines, and suggest one fix. Only report what the diff shows; ignore anything a formatter or linter covers.

| Smell | What it looks like | Usual fix |
|-------|-------------------|-----------|
| Unclear name | `process(x)`, `data2`, `handle(String s)` | A name that says what and why (`cancelOpenOrdersOf(userId)`) |
| Method doing too much | many screens long, deep nesting, several responsibilities | Split into well-named steps |
| Copy-paste | the same check or mapping in several places | One shared method, validator, or record |
| Unexplained number | `86400`, `3`, `0.07` in logic | A named constant or a configuration property |
| Logic in the wrong class | a method that mostly reads another object's fields | Move it to that object |
| Plain types for domain ideas | strings and longs passed around with the same checks each time | A small value record (`Money`, `Email`) |
| Too many parameters | five or more arguments | A parameter record |
| Built for a future that never came | an interface, factory, or flag with a single use | Remove the extra layer until a second use exists |
| Reaching through objects | `order.getCustomer().getAddress().getCity()` | Ask the owning object for what you need |
| Leftovers | unused methods, commented-out code, stale TODOs | Delete them |
