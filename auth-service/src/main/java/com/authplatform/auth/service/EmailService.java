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
}
