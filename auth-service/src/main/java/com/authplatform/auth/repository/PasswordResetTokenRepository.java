package com.authplatform.auth.repository;

import com.authplatform.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Query("SELECT p FROM PasswordResetToken p WHERE p.userId = :userId AND p.used = false AND p.expiresAt > :now")
    Optional<PasswordResetToken> findActiveTokenByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE PasswordResetToken p SET p.used = true WHERE p.userId = :userId AND p.used = false")
    void invalidateTokensByUserId(@Param("userId") Long userId);
}
