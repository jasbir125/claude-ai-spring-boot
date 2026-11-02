package com.example.user.config;

import java.util.Collection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server: every API call needs a JWT from the configured issuer with this service as audience.
 * Reads need scope {@code users.read}, writes {@code users.write}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppSecurityProperties.class)
public class SecurityConfig {

    static final String READ = "SCOPE_users.read";
    static final String WRITE = "SCOPE_users.write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // bearer tokens only, no cookies/sessions: CSRF protection is not applicable
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // scraped from inside the cluster; the ingress must not route /actuator/**
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/**").hasAuthority(READ)
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasAuthority(WRITE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Validates signature, expiry, issuer and audience. Lazy: the issuer is contacted on the first token. */
    @Bean
    JwtDecoder jwtDecoder(AppSecurityProperties props) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(props.issuerUri());
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(props.issuerUri()),
                    new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                            aud -> aud != null && aud.contains(props.audience()))));
            return decoder;
        });
    }
}
