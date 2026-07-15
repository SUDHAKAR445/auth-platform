package com.authplatform.auth.dto;

public record RegisterResponse(String message) {

    public static RegisterResponse of(String message) {
        return new RegisterResponse(message);
    }
}
