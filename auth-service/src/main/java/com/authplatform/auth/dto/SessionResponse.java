package com.authplatform.auth.dto;

import java.time.Instant;

public record SessionResponse(String device, String ip, Instant lastUsed) {
}
