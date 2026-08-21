package com.authplatform.auth.dto;

import com.authplatform.auth.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank(message = "token is required")
        String token,

        @NotBlank(message = "newPassword is required")
        @Size(max = 255, message = "newPassword must not exceed 255 characters")
        @StrongPassword
        String newPassword
) {
}
