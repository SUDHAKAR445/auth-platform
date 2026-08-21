package com.authplatform.auth.controller;

import com.authplatform.auth.dto.ForgotPasswordRequest;
import com.authplatform.auth.dto.ForgotPasswordResponse;
import com.authplatform.auth.dto.ResetPasswordRequest;
import com.authplatform.auth.dto.ResetPasswordResponse;
import com.authplatform.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    // always returns the same generic message — see decisions.md for why
    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestPasswordReset(request.email());
        return ResponseEntity.ok(new ForgotPasswordResponse(
                "If an account exists, a password reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new ResetPasswordResponse(
                "Password reset successful. Please log in again."));
    }
}
