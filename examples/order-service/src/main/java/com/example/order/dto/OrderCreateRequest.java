package com.example.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param userId   user-service ID of the ordering user; must be a known, ACTIVE user (otherwise 422)
 * @param currency ISO 4217 code shared by all lines
 */
@MinorUnits
public record OrderCreateRequest(
        @NotNull @Positive Long userId,
        @NotNull @CurrencyCode String currency,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid OrderLineRequest> lines) {
}
