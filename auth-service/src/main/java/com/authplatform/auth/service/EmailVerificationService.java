package com.authplatform.auth.service;

import com.authplatform.auth.config.VerificationProperties;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.entity.UserStatus;
import com.authplatform.auth.entity.VerificationToken;
import com.authplatform.auth.exception.InvalidVerificationTokenException;
import com.authplatform.auth.repository.UserRepository;
import com.authplatform.auth.repository.VerificationTokenRepository;
import com.authplatform.auth.security.OpaqueTokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final VerificationTokenRepository verificationTokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final VerificationProperties verificationProperties;
    private final OpaqueTokenGenerator opaqueTokenGenerator;

    public EmailVerificationService(VerificationTokenRepository verificationTokenRepository, UserRepository userRepository,
                                     EmailService emailService, VerificationProperties verificationProperties,
                                     OpaqueTokenGenerator opaqueTokenGenerator) {
        this.verificationTokenRepository = verificationTokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.verificationProperties = verificationProperties;
        this.opaqueTokenGenerator = opaqueTokenGenerator;
    }

    @Transactional
    public VerificationToken generateVerificationToken(User user) {
        Instant expiresAt = Instant.now().plusSeconds(verificationProperties.tokenExpiration());
        VerificationToken token = new VerificationToken(user.getId(), opaqueTokenGenerator.generate(), expiresAt);
        return verificationTokenRepository.save(token);
    }

    @Transactional
    public void verifyEmail(String tokenValue) {
        VerificationToken token = verificationTokenRepository.findByToken(tokenValue)
                .orElseThrow(InvalidVerificationTokenException::new);

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidVerificationTokenException();
        }
        if (token.isUsed()) {
            throw new InvalidVerificationTokenException();
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidVerificationTokenException::new);

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        token.setUsed(true);
        verificationTokenRepository.save(token);
        verificationTokenRepository.delete(token);

        log.info("Email verified for userId={}", user.getId());
    }

    // deliberately silent on "not found" and "already verified" — same enumeration-safety
    // reasoning as login (see decisions.md #3): the controller always returns a generic message
    @Transactional
    public void resendVerification(String email) {
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            log.info("Resend-verification requested for unknown email={}", email);
            return;
        }

        User user = maybeUser.get();
        if (user.isEmailVerified()) {
            log.info("Resend-verification requested for already-verified email={}", email);
            return;
        }

        VerificationToken token = generateVerificationToken(user);
        emailService.sendVerificationEmail(user.getEmail(), token.getToken());
        log.info("Verification email resent for userId={}", user.getId());
    }

    @Transactional
    public void cleanupExpiredTokens() {
        verificationTokenRepository.deleteExpired(Instant.now());
    }
}
