package com.authplatform.auth.service;

import com.authplatform.auth.config.JwtProperties;
import com.authplatform.auth.dto.RefreshTokenRequest;
import com.authplatform.auth.dto.RefreshTokenResponse;
import com.authplatform.auth.entity.RefreshToken;
import com.authplatform.auth.entity.Session;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.InvalidRefreshTokenException;
import com.authplatform.auth.repository.RefreshTokenRepository;
import com.authplatform.auth.repository.UserRepository;
import com.authplatform.auth.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SessionService sessionService;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                                JwtService jwtService, JwtProperties jwtProperties, SessionService sessionService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.sessionService = sessionService;
    }

    @Transactional
    public IssuedRefreshToken generateRefreshToken(User user, String deviceId, String ipAddress, String userAgent) {
        Instant expiresAt = Instant.now().plusSeconds(jwtProperties.refreshExpiration());
        RefreshToken refreshToken = new RefreshToken(user.getId(), generateOpaqueToken(), expiresAt, deviceId);
        RefreshToken saved = refreshTokenRepository.save(refreshToken);

        Session session = sessionService.createSession(user.getId(), saved.getToken(), expiresAt, ipAddress, userAgent);
        return new IssuedRefreshToken(saved, session.getId());
    }

    @Transactional(readOnly = true)
    public RefreshToken validateRefreshToken(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        return refreshToken;
    }

    @Transactional
    public void revokeRefreshToken(RefreshToken refreshToken) {
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public RefreshTokenResponse rotateRefreshToken(RefreshTokenRequest request) {
        RefreshToken oldToken = validateRefreshToken(request.refreshToken());

        User user = userRepository.findById(oldToken.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        Instant newExpiresAt = Instant.now().plusSeconds(jwtProperties.refreshExpiration());
        RefreshToken newToken = new RefreshToken(user.getId(), generateOpaqueToken(), newExpiresAt, oldToken.getDeviceId());
        refreshTokenRepository.save(newToken);
        revokeRefreshToken(oldToken);

        Session session = sessionService.updateLastUsed(oldToken.getToken(), newToken.getToken(), newExpiresAt)
                .orElseThrow(InvalidRefreshTokenException::new);
        String newAccessToken = jwtService.generateToken(user.getEmail(), session.getId());

        log.info("Refresh token rotated for userId={}", user.getId());
        return new RefreshTokenResponse(newAccessToken, newToken.getToken());
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        RefreshToken refreshToken = validateRefreshToken(refreshTokenValue);
        revokeRefreshToken(refreshToken);
        sessionService.deleteByRefreshToken(refreshTokenValue);
        log.info("Logout successful for userId={}", refreshToken.getUserId());
    }

    @Transactional
    public void logoutAll(Long userId) {
        refreshTokenRepository.findByUserId(userId)
                .forEach(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
        sessionService.revokeAndDeleteAllForUser(userId);
        log.info("Logout-all successful for userId={}", userId);
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
