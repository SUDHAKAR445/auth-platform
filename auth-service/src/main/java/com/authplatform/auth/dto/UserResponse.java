package com.authplatform.auth.dto;

import com.authplatform.auth.entity.UserStatus;

import java.time.Instant;

public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        UserStatus status,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt
) {
}
