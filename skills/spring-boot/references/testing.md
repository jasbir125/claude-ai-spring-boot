# Testing: Unit, Slice, Integration

Which test to write for which behaviour, with examples from `examples/user-service` and `examples/order-service`. Every behaviour gets a positive test and the relevant negative ones (validation, not found, conflict, forbidden, failure of a dependency).

## Which Test Where

| Level | Annotation | Starts | Use it for |
|-------|-----------|--------|-----------|
| Unit | `@ExtendWith(MockitoExtension.class)` | nothing | business rules in services, entities, mappers, parsers |
| Web slice | `@WebMvcTest(Controller.class)` + `@Import(SecurityConfig.class)` | MVC + security, service mocked | status codes, validation, Problem Details bodies, permissions |
| Integration (`*IT`) | `@SpringBootTest` + Testcontainers | whole app + real PostgreSQL / Kafka | persistence, migrations, transactions, messaging end to end |
| Architecture | ArchUnit `@AnalyzeClasses` | nothing | layering rules, "only the relay uses KafkaTemplate", no field injection |
| Contract | plain JUnit + JSON example files | nothing | published events match their documented examples |

Unit and slice tests are named `*Test` (Surefire, `./mvnw test`); integration tests `*IT` (Failsafe, `./mvnw verify`).

## Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository users;
    @Mock UserEventPublisher events;
    @InjectMocks UserService service;

    @Test
    void register_duplicateEmail_throws_andWritesNothing() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new UserCreateRequest("ADA@example.com", "Ada")))
                .isInstanceOf(EmailAlreadyUsedException.class);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }
}
```

- Inject time through a `Clock` and use `Clock.fixed(...)` in tests.
- Set generated fields (`id`, `version`) on entities with `ReflectionTestUtils.setField` instead of adding setters for tests.
- Assert outcomes (returned value, saved state, event published or not), not the order of internal calls.

## Web Slice Tests

```java
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UserService userService;

    @Test
    void register_invalidBody_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_users.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "displayName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].code").value("email"));

        verifyNoInteractions(userService);
    }

    @Test
    void readScope_cannotWrite_returns403() throws Exception {
        mvc.perform(post("/api/v1/users/1/deactivate")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_users.read"))))
                .andExpect(status().isForbidden());
    }
}
```

- `@MockitoBean` replaces `@MockBean`, which was removed in Spring Boot 4.
- Cover per endpoint: success, invalid input (`400` with the `errors` codes), not found, conflict, `401` without a token, `403` with the wrong scope, and a `500` that does not leak the exception message.
- JSON bodies as text blocks keep tests independent of the object mapper.

## Integration Tests with Testcontainers

One shared configuration starts real infrastructure; `@ServiceConnection` wires the connection details, so no `@DynamicPropertySource` is needed:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka-native:4.2.2");
    }
}
```

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class UserApiIT {

    @Autowired RestTestClient client;
    @Autowired Flyway flyway;
    @MockitoBean JwtDecoder jwtDecoder;   // token checks are covered by their own test (see below)

    @Test
    void flyway_allMigrationsApplied() {
        assertThat(flyway.info().pending()).isEmpty();
    }
}
```

What integration tests must cover:
- every migration applies to the production database engine and none is pending;
- the main flow through HTTP, database, and broker (e.g. an event arrives on the topic, keyed and ordered);
- `409` from a real optimistic-lock or unique-constraint conflict;
- for consumers: a duplicate message is processed once, and a broken message lands in the dead-letter topic without blocking the next one (`messaging-patterns`, `kafka.md`);
- health probes are reachable without a token.

Wait for asynchronous results with Awaitility (`await().atMost(...).untilAsserted(...)`), never `Thread.sleep`. Use unique test data (random emails, increasing IDs) so tests do not depend on each other's state.

If Docker is not available the integration tests fail; they must never be skipped silently to make a build pass.

## Token Validation (tested with real tokens)

Integration and slice tests usually replace the `JwtDecoder` with a mock or use `jwt()`, so they **never** test the issuer, audience and expiry checks; removing the audience check would not fail the build. Extract the validator into a static method (e.g. `SecurityConfig.jwtValidator(issuer, audience)`) used by the `JwtDecoder` bean, and test it with tokens signed by a local RSA key: a valid token is accepted; wrong audience, wrong issuer and an expired token (beyond the default 60 s clock skew) are rejected. Verified against Spring Boot 4.1.2:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtValidationTest {

    private static final String ISSUER = "https://idp.example.com/realms/demo";
    private static final String AUDIENCE = "order-service";

    private final KeyPair keys = rsaKeys();
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
            new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).build())));
    private final NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();

    JwtValidationTest() {
        decoder.setJwtValidator(jwtValidator(ISSUER, AUDIENCE));
    }

    /** In the service this is a static method in SecurityConfig, used by its JwtDecoder bean. */
    static OAuth2TokenValidator<Jwt> jwtValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    @Test
    void validToken_isAccepted() {
        assertThat(decoder.decode(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300))).getSubject())
                .isEqualTo("client");
    }

    @Test
    void wrongAudience_wrongIssuer_expired_areRejected() {
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "other-service", Instant.now().plusSeconds(300))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(token("https://evil.example.com", AUDIENCE, Instant.now().plusSeconds(300))))
                .isInstanceOf(JwtValidationException.class);
        // beyond the default 60 s clock skew
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, AUDIENCE, Instant.now().minusSeconds(120))))
                .isInstanceOf(JwtValidationException.class);
    }

    private String token(String issuer, String audience, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject("client")
                .issuedAt(expiresAt.minusSeconds(600)).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static KeyPair rsaKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
```

## Testcontainers 2 and Jackson 3 (Spring Boot 4)

- Artifacts (versions from the Boot BOM): `org.testcontainers:testcontainers-junit-jupiter`, `testcontainers-postgresql`, `testcontainers-kafka`.
- Container classes moved to module packages and are no longer generic: `org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer`. On Spring Boot 3.x (Testcontainers 1.x) use `org.testcontainers:postgresql` and `PostgreSQLContainer<?>`.
- Boot 4 auto-configures Jackson 3 `tools.jackson.databind.json.JsonMapper`; inject that (or write JSON as text blocks), not `com.fasterxml.jackson.databind.ObjectMapper`.
- Working example: `examples/user-service/src/test/java/com/example/user/TestcontainersConfiguration.java`.
