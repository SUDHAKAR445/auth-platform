package com.authplatform.auth.service;

import org.springframework.stereotype.Service;

// stub for now — prints to console. A real provider (SES, SendGrid, etc.) replaces this later.
@Service
public class EmailService {

    public void sendVerificationEmail(String toEmail, String token) {
        String verificationLink = "http://localhost:8081/api/v1/auth/verify?token=" + token;
        System.out.println("=== Verification Email ===");
        System.out.println("To: " + toEmail);
        System.out.println("Subject: Verify your email address");
        System.out.println("Link: " + verificationLink);
        System.out.println("===========================");
    }

    // the raw token is emailed here and never persisted — see decisions.md #22
    public void sendPasswordResetEmail(String toEmail, String rawToken) {
        String resetLink = "http://localhost:8081/api/v1/auth/reset-password?token=" + rawToken;
        System.out.println("=== Password Reset Email ===");
        System.out.println("To: " + toEmail);
        System.out.println("Subject: Reset your password");
        System.out.println("Link: " + resetLink);
        System.out.println("This link expires in 1 hour and can only be used once.");
        System.out.println("=============================");
    }
}
