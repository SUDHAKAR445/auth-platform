package com.authplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "verification")
public record VerificationProperties(long tokenExpiration) {
}
