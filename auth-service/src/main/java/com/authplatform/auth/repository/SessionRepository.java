package com.authplatform.auth.repository;

import com.authplatform.auth.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findByUserId(Long userId);

    Optional<Session> findByRefreshToken(String refreshToken);

    void deleteByRefreshToken(String refreshToken);

    void deleteByUserId(Long userId);

    @Query("SELECT s FROM Session s WHERE s.userId = :userId AND s.revoked = false AND s.expiresAt > :now")
    List<Session> findActiveSessions(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Session s SET s.revoked = true WHERE s.userId = :userId")
    void revokeAllByUser(@Param("userId") Long userId);
}
