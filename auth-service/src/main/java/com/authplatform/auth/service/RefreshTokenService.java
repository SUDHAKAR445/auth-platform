package com.authplatform.auth.service;

import com.authplatform.auth.config.JwtProperties;
import com.authplatform.auth.dto.RefreshTokenRequest;
import com.authplatform.auth.dto.RefreshTokenResponse;
import com.authplatform.auth.entity.RefreshToken;
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

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                                JwtService jwtService, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public RefreshToken generateRefreshToken(User user, String deviceId) {
        Instant expiresAt = Instant.now().plusSeconds(jwtProperties.refreshExpiration());
        RefreshToken refreshToken = new RefreshToken(user.getId(), generateOpaqueToken(), expiresAt, deviceId);
        return refreshTokenRepository.save(refreshToken);
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

        String newAccessToken = jwtService.generateToken(user.getEmail());
        RefreshToken newToken = generateRefreshToken(user, oldToken.getDeviceId());
        revokeRefreshToken(oldToken);

        log.info("Refresh token rotated for userId={}", user.getId());
        return new RefreshTokenResponse(newAccessToken, newToken.getToken());
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
