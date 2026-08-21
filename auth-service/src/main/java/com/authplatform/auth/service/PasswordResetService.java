package com.authplatform.auth.service;

import com.authplatform.auth.config.PasswordResetProperties;
import com.authplatform.auth.entity.PasswordResetToken;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.InvalidPasswordResetTokenException;
import com.authplatform.auth.repository.PasswordResetTokenRepository;
import com.authplatform.auth.repository.UserRepository;
import com.authplatform.auth.security.OpaqueTokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final OpaqueTokenGenerator opaqueTokenGenerator;
    private final PasswordResetProperties passwordResetProperties;

    public PasswordResetService(PasswordResetTokenRepository passwordResetTokenRepository, UserRepository userRepository,
                                 PasswordEncoder passwordEncoder, EmailService emailService,
                                 RefreshTokenService refreshTokenService, OpaqueTokenGenerator opaqueTokenGenerator,
                                 PasswordResetProperties passwordResetProperties) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.refreshTokenService = refreshTokenService;
        this.opaqueTokenGenerator = opaqueTokenGenerator;
        this.passwordResetProperties = passwordResetProperties;
    }

    // deliberately silent on "not found" — same enumeration-safety reasoning as
    // login (decisions.md #3) and resend-verification: the controller always
    // returns the same generic message regardless of what happens here
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for unknown email={}", email);
            return;
        }

        User user = maybeUser.get();
        invalidateExistingTokens(user.getId());

        String rawToken = opaqueTokenGenerator.generate();
        String tokenHash = opaqueTokenGenerator.hash(rawToken);
        Instant expiresAt = Instant.now().plusSeconds(passwordResetProperties.tokenExpiration());
        passwordResetTokenRepository.save(new PasswordResetToken(user.getId(), tokenHash, expiresAt));

        emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
        log.info("Password reset token issued for userId={}", user.getId());
    }

    @Transactional(readOnly = true)
    public PasswordResetToken validateResetToken(String rawToken) {
        String tokenHash = opaqueTokenGenerator.hash(rawToken);
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        if (token.isUsed() || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidPasswordResetTokenException();
        }

        return token;
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = validateResetToken(rawToken);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidPasswordResetTokenException::new);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        refreshTokenService.logoutAll(user.getId());

        log.info("Password reset completed for userId={}", user.getId());
    }

    @Transactional
    public void invalidateExistingTokens(Long userId) {
        passwordResetTokenRepository.invalidateTokensByUserId(userId);
    }
}
