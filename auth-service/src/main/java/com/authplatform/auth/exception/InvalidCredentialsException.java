package com.authplatform.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        // deliberately generic — never reveal whether the email exists,
        // the password was wrong, or the account is disabled
        super("Invalid email or password");
    }
}
