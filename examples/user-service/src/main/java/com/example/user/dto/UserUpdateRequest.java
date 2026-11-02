package com.example.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** PUT replaces the editable fields; {@code version} is the value from the last read (409 on mismatch). */
public record UserUpdateRequest(
        @NotBlank @Email @Size(min = 1, max = 254) String email,
        @NotBlank @Size(min = 1, max = 100) String displayName,
        @NotNull @PositiveOrZero Long version) {
}
