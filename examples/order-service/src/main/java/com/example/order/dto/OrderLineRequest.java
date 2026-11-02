package com.example.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * @param unitPrice price of one unit in the order's currency; decimals are limited by the currency
 *                  ({@link MinorUnits}); limits keep every order total below 10^14
 */
public record OrderLineRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotNull @Min(1) @Max(1000) Integer quantity,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 4) BigDecimal unitPrice) {
}
