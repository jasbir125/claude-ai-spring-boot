package com.example.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
        @NotBlank @Email @Size(min = 1, max = 254) String email,
        @NotBlank @Size(min = 1, max = 100) String displayName) {
}
