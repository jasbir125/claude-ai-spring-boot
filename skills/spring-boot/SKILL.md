---
name: spring-boot
description: Building Spring Boot 4.x services on Java 21+ - REST controllers and DTOs, Problem Details errors, JPA with Flyway, OAuth2 resource-server security, and tests with Testcontainers. Use when writing or changing controllers, entities, repositories, services, security configuration, application.yml, or tests in a Spring Boot project. Messaging and system design are covered by messaging-patterns and architecture.
---

# Spring Boot Skill

How to write the code of a single Spring Boot 4.x service. The mandatory rules are in `conventions`; this skill shows how to apply them, with examples taken from `examples/user-service` (built and tested with Spring Boot 4.1.2 and Java 25).

## How to Work

1. **Read before writing.** Look at `pom.xml`, `application.yml`, the package layout, and one existing controller, entity, and test. Copy their style.
2. **Agree the shape first** for anything bigger than a small fix: resources, endpoints, tables, events, and error cases.
3. **Build in layers:** migration → entity → repository → service → DTOs → controller → exception handler → tests.
4. **Prove it:** run the narrowest test, then `./mvnw verify`. Report what ran.

## Architecture Patterns

A request travels through thin layers, each with one job:

```
HTTP ─▶ Controller ─▶ Service ─▶ Repository ─▶ database
         │  DTOs in/out  │ rules + @Transactional
         └─ never sees entities or repositories
```

| Layer | Holds | Must not |
|-------|-------|----------|
| `controller` | HTTP mapping, `@Valid`, status codes, `Location` header | contain business rules, call repositories, return entities |
| `service` | business rules, transactions, calls to publishers | know about HTTP types (`ResponseEntity`, `HttpServletRequest`) |
| `repository` | Spring Data interfaces and queries | contain business decisions |
| `model` | JPA entities with behaviour (`register`, `deactivate`) | expose setters for fields that have rules |
| `dto` | request/response records | contain logic beyond a `from(entity)` mapper |
| `config`, `exception` | beans, typed properties, `@RestControllerAdvice` | — |

Larger domains may use one package per business module instead (see `architecture`).

## A Complete Slice in Brief

```java
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository users;
    private final UserEventPublisher events;

    @Transactional
    public UserResponse register(UserCreateRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        User user = users.saveAndFlush(User.register(email, request.displayName()));  // id + version assigned
        events.registered(user);                                                     // outbox, same transaction
        return UserResponse.from(user);
    }
}
```

```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<UserResponse> register(@Valid @RequestBody UserCreateRequest request) {
        UserResponse user = userService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(user.id()).toUri();
        return ResponseEntity.created(location).body(user);
    }
}
```

## Default `application.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true
  mvc:
    problemdetails:
      enabled: true
  lifecycle:
    timeout-per-shutdown-phase: 30s
  data:
    web:
      pageable:
        max-page-size: 100
  jpa:
    hibernate:
      ddl-auto: validate        # Flyway owns the schema
    open-in-view: false
server:
  shutdown: graceful
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,db
```

Every environment-specific value is a placeholder with a safe local default, e.g. `url: ${DB_URL:jdbc:postgresql://localhost:15432/users}`. Groups of related settings become a `@ConfigurationProperties` record with `@Validated`.

## Reference Guide

| Topic | File | Read it when |
|-------|------|--------------|
| Controllers, DTOs, validation, paging, Problem Details | `references/web.md` | Adding or changing an endpoint or error |
| Entities, repositories, Flyway, transactions, locking | `references/data.md` | Touching tables, entities, or queries |
| Resource server, JWT validation, scopes, actuator access | `references/security.md` | Touching security or auth |
| Unit, slice, Testcontainers tests, token tests | `references/testing.md` | Writing or fixing tests |
| `@Slf4j`, where to log, correlation ID filter, JSON logs | `references/logging.md` | Adding logs, setting up a new service, or debugging with logs |

Related skills: `messaging-patterns` (events, outbox, consumers), `architecture` (boundaries, sagas, ADRs). Working templates: `examples/user-service` and `examples/order-service`.

## Always

- Constructor injection via `@RequiredArgsConstructor` and `private final` fields
- `@Valid` on every request body; Bean Validation on DTO records
- Read-only transactions by default on services, `@Transactional` on methods that change data
- Typed configuration, no secrets in source or `application.yml`
- One `@RestControllerAdvice` that turns every error into Problem Details
- Permit `/actuator/health/**` so liveness and readiness probes work behind security

## Never

- `@Autowired` on fields, or `@Data` on entities
- Entities in controller signatures or responses
- `ddl-auto: update`/`create` outside tests
- `Page`/`PageImpl` as a response body (use `PagedModel`)
- Exception messages or stack traces in API responses
- A database transaction kept open while calling another service or a broker
