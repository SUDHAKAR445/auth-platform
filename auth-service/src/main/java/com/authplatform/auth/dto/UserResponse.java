package com.authplatform.auth.dto;

import java.time.Instant;

public record UserResponse(
        Long id,
        String username,
        String email,
        String firstName,
        String lastName,
        Instant createdAt,
        Instant updatedAt
) {
}
