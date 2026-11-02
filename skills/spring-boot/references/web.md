# Web Layer: Controllers, DTOs, Errors

Patterns for the HTTP edge of a service. Examples come from `examples/user-service`.

## Controllers Stay Thin

A controller maps HTTP to a service call and back. Nothing else.

```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public PagedModel<UserResponse> list(
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(userService.findAll(status, pageable));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return userService.findById(id);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public UserResponse deactivate(@PathVariable Long id) {
        return userService.deactivate(id);
    }
}
```

Status codes to use:

| Situation | Status |
|-----------|--------|
| Created a resource | `201` with a `Location` header pointing at it |
| Read or changed and returning the result | `200` |
| Removed, nothing to return | `204` |
| Input breaks validation rules | `400` with one entry per broken field |
| Missing or invalid token / token lacks the permission | `401` / `403` (from Spring Security) |
| Resource does not exist | `404` |
| Duplicate value, or the client edited an outdated version | `409` |
| Valid request that the current state does not allow | `422` |
| Anything unexpected | `500` with a generic message |

Actions that are not plain create/update/delete (deactivate, cancel) are `POST` on a sub-resource (`/{id}/cancel`), never a `GET` that changes state.

## Paged Lists

- Return `org.springframework.data.web.PagedModel`. Its JSON is stable: `{ "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }`.
- Never return `Page` or `PageImpl`: their JSON is an implementation detail and Spring Data warns about it.
- Cap the page size with `spring.data.web.pageable.max-page-size`.
- A `sort` on an unknown property throws `PropertyReferenceException` (package `org.springframework.data.core` in Spring Data 4): map it to `400`. Restrict sorting to indexed columns on large tables.
- List items may be a smaller summary record than the detail view, so a page never loads child collections (see `data.md`, N+1).

## Request and Response DTOs

DTOs are Java records named `<Resource>CreateRequest`, `<Resource>UpdateRequest`, and `<Resource>Response`. Validation lives on the request record:

```java
public record UserCreateRequest(
        @NotBlank @Email @Size(min = 1, max = 254) String email,
        @NotBlank @Size(min = 1, max = 100) String displayName) {
}

/** Full replacement of the editable fields; version = the value the client last read. */
public record UserUpdateRequest(
        @NotBlank @Email @Size(min = 1, max = 254) String email,
        @NotBlank @Size(min = 1, max = 100) String displayName,
        @NotNull @PositiveOrZero Long version) {
}

public record UserResponse(Long id, String email, String displayName, UserStatus status,
                           Instant createdAt, Instant updatedAt, Long version) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getStatus(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getVersion());
    }
}
```

Guidelines:
- Give every limit a matching database `CHECK` or column size, so the API and the table agree.
- Normalise input in one place (the entity factory), e.g. trim and lower-case emails, and compare normalised values.
- Responses include `version`, so a client can send it back on update and get `409` instead of overwriting someone else's change.
- Money is `BigDecimal` plus an ISO 4217 currency code; never `double`.
- Keep regular expressions simple and portable (no inline flags or possessive quantifiers), because generated clients may reuse them.

## Exception Handling

One `@RestControllerAdvice` extends `ResponseEntityExceptionHandler`, so Spring's own errors (bad JSON, wrong parameter type, missing body) are already Problem Details (RFC 9457). It adds:
- an `errors` list for validation failures, with a stable `code` per field;
- one handler per domain exception;
- a catch-all that logs the cause and returns a generic message.

```java
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvalidField(String field, String code, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> invalid = new ArrayList<>();
        for (var fieldError : ex.getFieldErrors()) {
            invalid.add(new InvalidField(fieldError.getField(), errorCode(fieldError.getCode()),
                    fieldError.getDefaultMessage()));
        }
        ex.getBody().setProperty("errors", invalid);
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail notFound(UserNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "User not found", ex.getMessage());
    }

    @ExceptionHandler({VersionConflictException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail versionConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Version conflict", "Reload the resource and try again");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);                       // full detail in the log only
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private static String errorCode(String constraint) {
        return switch (constraint == null ? "" : constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> "required";
            case "Email" -> "email";
            case "Size" -> "size";
            default -> "invalid";
        };
    }
}
```

A validation error then looks like:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/v1/users",
  "errors": [ { "field": "email", "code": "email", "message": "must be a well-formed email address" } ]
}
```

Rules:
- The `code` values are a small fixed set that clients may rely on; the `message` is a human fallback.
- A unique constraint that the API reports as `409` is matched **by constraint name** (e.g. `uk_users_email`) in a `DataIntegrityViolationException` handler, which covers two clients registering the same value at the same moment.
- Never put `ex.getMessage()` of an unexpected exception, SQL, or class names into a response.

## Calling Other Services

Synchronous calls use a declarative `@HttpExchange` interface backed by `RestClient`, always with timeouts:

```java
@HttpExchange("/api/v1/stock")
public interface StockClient {

    @GetExchange("/{sku}")
    StockResponse get(@PathVariable String sku);
}

@Configuration(proxyBeanMethods = false)
class StockClientConfig {

    @Bean
    StockClient stockClient(RestClient.Builder builder, StockClientProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        RestClient client = builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(StockClient.class);
    }
}
```

Retry only operations that are safe to repeat, add a circuit breaker for remote dependencies, and translate a failing dependency into `503` Problem Details without passing its error text through (`conventions`, "Outbound HTTP"). Prefer events over synchronous calls between services when the caller does not need an answer immediately (`architecture`).
