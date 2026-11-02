package com.example.order.config;

import java.util.regex.Pattern;

/** Names shared by the HTTP filter, logging, and event envelopes for the business correlation ID. */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Accept only safe values from clients (prevents log injection and unbounded header values). */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private Correlation() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}
