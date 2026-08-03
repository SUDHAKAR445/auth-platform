package com.authplatform.auth.dto;

public record LoginResponse(String message) {

    public static LoginResponse of(String message) {
        return new LoginResponse(message);
    }
}
