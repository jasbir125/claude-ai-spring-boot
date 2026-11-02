package com.example.order.exception;

import lombok.Getter;

/** Business rule: only users that user-service reported as ACTIVE may place orders (HTTP 422). */
@Getter
public class UserCannotOrderException extends RuntimeException {

    /** Stable, documented codes returned as {@code reason} in the Problem Details. */
    public enum Reason {
        /** No event about this user has been received (yet). */
        USER_UNKNOWN,
        /** user-service deactivated the user. */
        USER_NOT_ACTIVE
    }

    private final Long userId;
    private final Reason reason;

    public UserCannotOrderException(Long userId, Reason reason) {
        super(switch (reason) {
            case USER_UNKNOWN -> "User " + userId + " is not known to order-service";
            case USER_NOT_ACTIVE -> "User " + userId + " is not active";
        });
        this.userId = userId;
        this.reason = reason;
    }
}
