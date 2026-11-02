package com.example.order.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error is RFC 9457 Problem Details; nothing internal (stack traces, SQL, exception messages) leaks. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** One broken field: {@code code} is a fixed value clients can rely on, {@code message} is readable text for people. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvalidField(String field, String code, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> invalid = new ArrayList<>();
        for (var fieldError : ex.getFieldErrors()) {
            invalid.add(new InvalidField(fieldError.getField(), errorCode(fieldError.getCode()),
                    fieldError.getDefaultMessage()));
        }
        ex.getBody().setProperty("errors", invalid);
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage());
    }

    @ExceptionHandler(UserCannotOrderException.class)
    ProblemDetail userCannotOrder(UserCannotOrderException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "User cannot place orders", ex.getMessage());
        problem.setProperty("reason", ex.getReason().name());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail versionConflict(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Version conflict",
                "The order was modified concurrently; reload it and retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(DataIntegrityViolationException ex) {
        log.error("Unmapped data integrity violation", ex);
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with the current state");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail unknownSortProperty(PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Unknown sort property '" + ex.getPropertyName() + "'");
    }

    /** Catch-all: log the cause, never return it. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private static String errorCode(String constraint) {
        if (constraint == null) {
            return "invalid";
        }
        return switch (constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> "required";
            case "Size" -> "size";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive" -> "range";
            case "Digits" -> "digits";
            case "CurrencyCode" -> "currency";
            case "MinorUnits" -> "scale";
            default -> "invalid";
        };
    }
}
