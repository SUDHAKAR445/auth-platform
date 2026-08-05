package com.authplatform.auth.dto;

public record LoginResponse(String accessToken, String tokenType, String refreshToken, long expiresIn) {
}
