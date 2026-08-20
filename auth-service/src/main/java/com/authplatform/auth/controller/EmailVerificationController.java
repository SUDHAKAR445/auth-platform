package com.authplatform.auth.controller;

import com.authplatform.auth.dto.ResendVerificationRequest;
import com.authplatform.auth.dto.ResendVerificationResponse;
import com.authplatform.auth.dto.VerifyEmailResponse;
import com.authplatform.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/verify")
    public ResponseEntity<VerifyEmailResponse> verify(@RequestParam String token) {
        emailVerificationService.verifyEmail(token);
        return ResponseEntity.ok(new VerifyEmailResponse("Email verified successfully"));
    }

    // always returns the same generic response, regardless of whether the email exists
    // or is already verified — see decisions.md for why (same reasoning as login)
    @PostMapping("/resend-verification")
    public ResponseEntity<ResendVerificationResponse> resend(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resendVerification(request.email());
        return ResponseEntity.ok(new ResendVerificationResponse(
                "If an account exists for this email and isn't verified yet, a verification email has been sent."));
    }
}
