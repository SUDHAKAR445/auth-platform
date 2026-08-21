package com.authplatform.auth.exception;

public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("Invalid or expired reset token");
    }
}
