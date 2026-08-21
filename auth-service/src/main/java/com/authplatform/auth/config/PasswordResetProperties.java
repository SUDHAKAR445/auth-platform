package com.authplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "password-reset")
public record PasswordResetProperties(long tokenExpiration) {
}
