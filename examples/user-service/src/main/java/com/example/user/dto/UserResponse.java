package com.example.user.dto;

import com.example.user.model.User;
import com.example.user.model.UserStatus;
import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getVersion());
    }
}
