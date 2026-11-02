package com.example.order.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level check of {@link OrderCreateRequest}: every unit price has at most as many decimals as the order's
 * currency allows (EUR 2, JPY 0, BHD 3). Violations are reported on {@code lines[i].unitPrice}.
 */
@Documented
@Constraint(validatedBy = MinorUnitsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MinorUnits {

    String message() default "has more decimals than the currency allows";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
