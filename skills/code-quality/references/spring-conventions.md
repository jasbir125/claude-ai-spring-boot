# Spring Boot Convention Checks

The rules are in `conventions` (Mandatory Rules, Quality Bar); cite them instead of repeating them. This file lists what to look for, and old APIs to replace.

## Where Each Rule Lives
| Topic | Rule |
|-------|------|
| Lombok first, constructor injection, record DTOs, Jakarta, `RestClient`, Testcontainers with `@ServiceConnection` | `conventions` → Mandatory Rules |
| Exception handler and Problem Details, `PagedModel`, `@Version`, `@Valid`, transactions, health probes | `conventions` → Quality Bar |
| Layering (controller → service → repository) | `spring-boot` → Architecture Patterns |

## What to Look For
- Boilerplate Lombok would remove: hand-written getters, constructors that only assign fields, `LoggerFactory.getLogger(...)`.
- `@Data`, `@EqualsAndHashCode`, or `@ToString` on an entity; Lombok annotations on a record.
- Lombok on the class path but not in `annotationProcessorPaths` (on JDK 23+ nothing gets generated).
- `@Autowired` on a field.
- A controller returning `Page`/`PageImpl`, or `@EnableSpringDataWebSupport` on the application class (it breaks `@WebMvcTest`).
- A controller calling a repository, or a service handing an entity to the web layer.

## Old APIs to Replace
| Found | Replace with |
|-------|--------------|
| `WebSecurityConfigurerAdapter` | a `SecurityFilterChain` bean |
| `RestTemplate`, or `WebClient` used for blocking calls | `RestClient` / `@HttpExchange` (keep `WebClient` for reactive code) |
| `@MockBean`, `@SpyBean` (gone in Spring Boot 4) | `@MockitoBean`, `@MockitoSpyBean` |
| `javax.persistence`, `javax.validation` | `jakarta.*` |
| Spring Boot 3.x or older parent in a new project | Spring Boot 4.x |
| `@DynamicPropertySource` for containers | `@ServiceConnection` |
