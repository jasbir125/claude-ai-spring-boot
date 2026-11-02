package com.example.order.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param issuerUri OIDC issuer whose signing keys validate access tokens (e.g. a Keycloak realm)
 * @param audience  required value in the token's {@code aud} claim
 */
@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(@NotBlank String issuerUri, @NotBlank String audience) {
}
