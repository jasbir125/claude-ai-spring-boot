package com.example.order.dto;

import com.example.order.model.Money;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

public class MinorUnitsValidator implements ConstraintValidator<MinorUnits, OrderCreateRequest> {

    @Override
    public boolean isValid(OrderCreateRequest request, ConstraintValidatorContext context) {
        Optional<Currency> currency = request == null ? Optional.empty() : Money.currency(request.currency());
        List<OrderLineRequest> lines = request == null ? null : request.lines();
        if (currency.isEmpty() || lines == null) {
            return true;   // reported by @CurrencyCode / @NotNull on the fields
        }
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        for (int i = 0; i < lines.size(); i++) {
            OrderLineRequest line = lines.get(i);
            if (line != null && line.unitPrice() != null && !Money.fitsMinorUnits(line.unitPrice(), currency.get())) {
                valid = false;
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("lines")
                        .addPropertyNode("unitPrice").inIterable().atIndex(i)
                        .addConstraintViolation();
            }
        }
        return valid;
    }
}
