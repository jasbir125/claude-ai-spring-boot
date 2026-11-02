package com.example.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrderCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequest_hasNoViolations() {
        assertThat(validator.validate(request("EUR", line("SKU-1", 2, "10.50")))).isEmpty();
        assertThat(validator.validate(request("JPY", line("SKU-1", 1, "1200")))).isEmpty();
        assertThat(validator.validate(request("BHD", line("SKU-1", 1, "1.005")))).isEmpty();
    }

    @Test
    void invalidCurrency_isReportedOnCurrency() {
        assertThat(violations(request("eur", line("SKU-1", 1, "1")))).containsEntry("currency", "CurrencyCode");
        assertThat(violations(request("XAU", line("SKU-1", 1, "1")))).containsEntry("currency", "CurrencyCode");
    }

    @Test
    void tooManyDecimals_forCurrency_isReportedOnTheLine() {
        Map<String, String> violations = violations(request("JPY", line("A", 1, "100"), line("B", 1, "9.99")));

        assertThat(violations).containsExactly(Map.entry("lines[1].unitPrice", "MinorUnits"));
    }

    @Test
    void lineConstraints() {
        Map<String, String> violations = violations(request("EUR",
                line(" ", 0, "-1"), new OrderLineRequest("A", 1001, new BigDecimal("1234567890"))));

        assertThat(violations).containsEntry("lines[0].sku", "NotBlank")
                .containsEntry("lines[0].quantity", "Min")
                .containsEntry("lines[0].unitPrice", "DecimalMin")
                .containsEntry("lines[1].quantity", "Max")
                .containsEntry("lines[1].unitPrice", "Digits");
    }

    @Test
    void missingFields_andNoLines() {
        assertThat(violations(new OrderCreateRequest(null, null, null)))
                .containsKeys("userId", "currency", "lines");
        assertThat(violations(new OrderCreateRequest(1L, "EUR", List.of()))).containsEntry("lines", "Size");
        assertThat(violations(new OrderCreateRequest(0L, "EUR", List.of(line("A", 1, "1")))))
                .containsEntry("userId", "Positive");
    }

    private Map<String, String> violations(OrderCreateRequest request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(),
                OrderCreateRequestValidationTest::constraintName,
                (a, b) -> a + "," + b));
    }

    private static String constraintName(ConstraintViolation<?> v) {
        return v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }

    private static OrderCreateRequest request(String currency, OrderLineRequest... lines) {
        return new OrderCreateRequest(42L, currency, Arrays.asList(lines));
    }

    private static OrderLineRequest line(String sku, int quantity, String unitPrice) {
        return new OrderLineRequest(sku, quantity, new BigDecimal(unitPrice));
    }
}
