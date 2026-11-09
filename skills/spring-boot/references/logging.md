# Logging

How every service logs. The rules are in `conventions` (Lombok `@Slf4j`, correlation in logs, no secrets). This file shows the setup and **where each layer logs**. The working setup code is in `examples/user-service` and `examples/order-service` (`config/Correlation`, `config/CorrelationIdFilter`, the `logging:` block of `application.yml`).

> Property names below are the Spring Boot 3.4+ ones. The examples run on Boot 4.1 and use `logging.pattern.correlation` and `logging.include-application-name`. **`logging.structured.format.console` and the tracing artifact names are not exercised by the examples; check them against the Boot 4 reference before relying on them.**

## What Every Service Has

| Piece | Where | Purpose |
|-------|-------|---------|
| `@Slf4j` | every class that logs | Lombok creates `log`; never `LoggerFactory.getLogger` by hand |
| `Correlation` | `config/` | constants `HEADER = "X-Correlation-Id"`, `MDC_KEY = "correlationId"`, and `isValid(...)` |
| `CorrelationIdFilter` | `config/` | puts the business correlation ID in the MDC for every HTTP request and echoes it in the response |
| `logging:` block | `application.yml` | prints the application name and `correlationId` on every line |
| Listener MDC | `messaging/` | puts the event's `correlationId` in the MDC while a message is handled (`messaging-patterns` → `references/kafka.md`) |

Copy `Correlation` and `CorrelationIdFilter` from `examples/user-service` into each new service and change only the package.

## Where to Log

Every layer that does real work has `@Slf4j` and logs the points below. Log identifiers (`orderId`, `eventId`), never payloads or personal data.

| Place | `INFO` | `WARN` | `ERROR` |
|-------|--------|--------|---------|
| **Controller** | nothing (keep it thin; the access log belongs to the gateway) | – | – |
| **Service** (business methods that change state) | one line after the change commits: `log.info("Order placed orderId={} customerId={}", id, customerId)` | a business rule refused the request in a way worth noticing: `log.warn("Order rejected orderId={} reason={}", ...)` | – (let the exception go up; the advice logs it once) |
| **`@RestControllerAdvice`** | – | domain and validation errors that matter (conflicts, rejected state changes) | unexpected exceptions, with the stack trace: `log.error("Unexpected error", ex)` |
| **Outbox writer** | – | – | – (it runs inside the business transaction; a failure surfaces as that transaction's exception) |
| **Outbox relay** | batch sent: `log.info("Outbox batch sent count={}", n)` (skip when 0) | one event failed to send: `log.warn("Outbox publish failed eventId={} attempts={}", id, attempts, ex)` | an event reached the attempt limit: `log.error(...)` and a metric |
| **Kafka listener** | message handled: `log.info("Handled eventType={} eventId={}", ...)` | duplicate ignored: `log.warn("Duplicate eventId={} ignored", id)` at `INFO` or `DEBUG` if duplicates are normal | – (the error handler logs) |
| **Kafka error handler / DLT** | – | a retry attempt, with `eventId` | message sent to a dead-letter topic: `log.error("Sent to DLT topic={} eventId={} correlationId={}", ...)` plus a metric; no payload |
| **Outbound HTTP client** | – | retry, timeout, or open circuit breaker: `log.warn("Call to {} failed status={}", target, status)` | the call failed after all retries and the caller returns `503`: `log.error(...)` once |
| **Scheduled job** | start and end with counts | skipped because another replica holds the lock (`DEBUG`) | the run failed |
| **Startup / config** | – | a risky setting in use (for example topics auto-created) | – |

Rules of thumb:

- **One failure, one `ERROR` line.** Log an exception where it is handled, passing it as the last argument (`log.error("...", ex)`). Do not log and rethrow at every layer.
- Expected client errors (400, 404, 409) are not `ERROR`. The response already tells the caller.
- Use placeholders, not concatenation: `log.info("... orderId={}", id)`.
- Do not wrap simple reads in `INFO` lines; use `DEBUG` or nothing.
- `ERROR` should mean "someone may need to look". If an alert would be noise, use `WARN`.

### In code

```java
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    @Transactional
    public OrderResponse place(OrderCreateRequest request) {
        Order order = orders.saveAndFlush(Order.place(request.customerId(), request.lines()));
        events.placed(order);                                       // outbox, same transaction
        log.info("Order placed orderId={} customerId={}", order.getId(), order.getCustomerId());
        return OrderResponse.from(order);
    }
}
```

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);                          // full detail in the log only
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
}
```

The `INFO` line is written inside the transaction, so it may be printed and then the commit fails. For a line that must mean "committed", log after the transaction (in the controller or an `@TransactionalEventListener(phase = AFTER_COMMIT)`), or word the line as an attempt. Keep it simple: most services accept the in-transaction line.

## Correlation Constants and Filter

```java
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Accept only safe values from clients (prevents log injection and unbounded header values). */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private Correlation() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}
```

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HEADER);
        String correlationId = Correlation.isValid(incoming) ? incoming : UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, correlationId);
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);   // threads are reused; a leftover value would leak into the next request
        }
    }
}
```

- The value is validated before use. A client-supplied header with newlines or unlimited length is a log-injection and log-flooding risk, so an invalid value is replaced with a new UUID.
- The outbox writer reads the same MDC key when it builds the event envelope, so one ID follows a request into Kafka and into the next service.
- The filter runs first, so security and error-handling lines also carry the ID.
- The MDC does not cross threads. `@Async` and scheduled code that needs the ID must copy it explicitly.

## `application.yml`

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{correlationId:-}] "
  include-application-name: false      # the pattern above already prints it
```

A line then looks like `... INFO [user-service,3f2a...] --- [nio-8080-exec-1] c.e.u.service.UserService : ...`.

### Structured (JSON) logs for production

Log aggregators parse JSON better than text. Turn it on per environment, not in the default file, so local logs stay readable:

```yaml
# application-prod.yml, or LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs in the environment
logging:
  structured:
    format:
      console: ecs        # or logstash, gelf
```

MDC values, including `correlationId`, appear as fields in structured output. Verify the field name in your Boot version's output.

### Trace IDs

`correlationId` is a business flow ID that you control. A trace ID comes from distributed tracing and shows timing across calls. Both are useful. Add tracing only when the platform has a collector; the plugin does not add an observability stack by itself (`CLAUDE.md` → Logging and observability). With tracing on, Boot puts `traceId` and `spanId` in the MDC and the pattern can print them next to `correlationId`.

## Never Log

- Passwords, tokens, `Authorization` headers, API keys, client secrets (`references/security.md`)
- Personal data: email addresses, names, phone numbers, addresses, payment details. Log the internal ID instead.
- Full request bodies or event payloads
- Raw client input that has not been validated (`Correlation.isValid` shows the idea)

## Tests

- `CorrelationIdFilter`: a valid header is echoed, a missing header yields a generated UUID, an invalid header (spaces, newlines, too long) is replaced, and the MDC is empty after the request.
- An integration test checks that an `X-Correlation-Id` sent to the API appears in the event envelope written to the outbox (`OrderApiIT` in `order-service`).
- Do not assert on log text. If a line matters (a dead-letter `ERROR`), assert the metric or the visible effect.

## Review Signals

- A class creates its own logger, or uses `System.out` or `e.printStackTrace()`
- Secrets, tokens, or personal data in a log call, including `toString()` of an entity or request DTO
- `log.error(ex.getMessage())` without the exception, or the same exception logged in several layers
- String concatenation in a log call
- MDC set with no `finally { MDC.remove(...) }`
- A listener or `@Async` method that needs the correlation ID but does not set it
- A state-changing service method, relay failure, or DLT hand-off with no log line
