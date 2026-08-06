package com.authplatform.auth.service;

import com.authplatform.auth.entity.RefreshToken;

/** Internal transport type — carries the session id alongside its refresh token so callers can mint an access token that embeds it. */
public record IssuedRefreshToken(RefreshToken refreshToken, Long sessionId) {
}
