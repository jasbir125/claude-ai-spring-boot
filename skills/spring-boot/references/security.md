# Security: Resource Server, Tokens, Permissions

How a service protects its API. The default is an **OAuth2 resource server**: an identity provider (Keycloak, Entra ID, Okta, Auth0…) issues JWT access tokens; the service checks them and decides access by scope or role. The service never handles passwords. Example: `examples/user-service`, `SecurityConfig`.

## The Filter Chain

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppSecurityProperties.class)
public class SecurityConfig {

    static final String READ = "SCOPE_users.read";
    static final String WRITE = "SCOPE_users.write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)          // bearer tokens only: no cookies, no CSRF risk
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()   // keep /actuator off the public ingress
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/**").hasAuthority(READ)
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasAuthority(WRITE)
                        .anyRequest().denyAll())                // closed by default
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Rules:
- **Deny by default.** Every path is listed; anything else is `denyAll()`.
- **Stateless:** no HTTP session, and CSRF protection is off only because no cookies are used. A browser app with session cookies needs CSRF protection.
- Health probes stay public so the platform can check them; other actuator endpoints are not routed through the public ingress.
- Authorities come from the token's `scope` claim as `SCOPE_<name>` by default. For roles from a custom claim, add a `JwtAuthenticationConverter`.
- **Object-level checks** ("may this caller see order 42?") belong in the service, comparing the token subject with the owner. A scope alone only says "may read orders".

## Validating Tokens

Signature, expiry, issuer, **and audience** must all be checked. Spring checks signature and expiry; issuer and audience are added explicitly. Build the decoder lazily so the service starts even when the identity provider is not reachable yet, and keep the validator in a static method so it can be tested (`testing.md`, "Token validation").

```java
@Bean
JwtDecoder jwtDecoder(AppSecurityProperties props) {
    return new SupplierJwtDecoder(() -> {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(props.issuerUri());   // fetches keys on first use
        decoder.setJwtValidator(jwtValidator(props.issuerUri(), props.audience()));
        return decoder;
    });
}

static OAuth2TokenValidator<Jwt> jwtValidator(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
}
```

```java
/** app.security.issuer-uri and app.security.audience, both required. */
@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(@NotBlank String issuerUri, @NotBlank String audience) {
}
```

In `application.yml`: `issuer-uri: ${OAUTH2_ISSUER_URI:http://localhost:8180/realms/demo}` with a local default, and the production value from the environment.

## Service-to-Service Calls

- The calling service gets its own client in the identity provider and uses the **client-credentials** flow; tokens carry the target service as audience and only the scopes it needs.
- Never forward a user's token to a third party; never share one client secret between services.
- On Kubernetes, mesh mTLS can replace tokens for transport identity, but authorization still checks scopes or roles.

## Secrets and Configuration

- No secret in source, `application.yml`, images, or logs. Values come from environment variables or mounted files (Kubernetes Secrets, a vault).
- Local demo values (Compose passwords, Keycloak test clients) are allowed only when they are clearly labelled local-only and overridable (`${VAR:-local-value}`).
- Kafka in shared environments uses `SASL_SSL` with per-service credentials and topic ACLs (produce only to owned topics, consume only from subscribed ones).

## Error Responses

- `401`/`403` come from Spring Security without a body by default; that is acceptable, or add an `AuthenticationEntryPoint` that writes Problem Details.
- Error bodies never echo tokens, internal IDs of other tenants, stack traces, or SQL.
- Log the authenticated subject and correlation ID for audit; never log the token itself.

## Checklist

- [ ] All paths listed, `anyRequest().denyAll()` last
- [ ] Issuer **and** audience validated; tested with real signed tokens
- [ ] Scopes per HTTP method; object-level checks in services where data belongs to a user
- [ ] Health probes public, other actuator endpoints internal only
- [ ] No secrets in the repository; local demo secrets labelled and overridable
