package com.authplatform.auth.service;

import com.authplatform.auth.dto.SessionResponse;
import com.authplatform.auth.entity.Session;
import com.authplatform.auth.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public Session createSession(Long userId, String refreshToken, Instant expiresAt,
                                  String ipAddress, String userAgent) {
        String deviceName = parseDeviceName(userAgent);
        String deviceType = parseDeviceType(userAgent);
        Session session = new Session(userId, refreshToken, deviceName, deviceType, ipAddress, userAgent, expiresAt);
        return sessionRepository.save(session);
    }

    /** Called on every refresh-token rotation: the session survives, its token and expiry move forward. */
    @Transactional
    public Optional<Session> updateLastUsed(String oldRefreshToken, String newRefreshToken, Instant newExpiresAt) {
        return sessionRepository.findByRefreshToken(oldRefreshToken).map(session -> {
            session.setRefreshToken(newRefreshToken);
            session.setExpiresAt(newExpiresAt);
            session.setLastUsed(Instant.now());
            return sessionRepository.save(session);
        });
    }

    /** Checked on every authenticated request via JwtAuthenticationFilter — see decisions.md #15. */
    @Transactional(readOnly = true)
    public boolean isSessionActive(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> !session.isRevoked() && session.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    @Transactional
    public void deleteByRefreshToken(String refreshToken) {
        sessionRepository.deleteByRefreshToken(refreshToken);
    }

    @Transactional
    public void revokeAndDeleteAllForUser(Long userId) {
        sessionRepository.revokeAllByUser(userId);
        sessionRepository.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listActiveSessions(Long userId) {
        return sessionRepository.findActiveSessions(userId, Instant.now()).stream()
                .map(s -> new SessionResponse(s.getDeviceName(), s.getIpAddress(), s.getLastUsed()))
                .toList();
    }

    private String parseDeviceType(String userAgent) {
        if (userAgent == null) {
            return "Unknown";
        }
        if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
            return "Mobile";
        }
        if (userAgent.contains("iPad") || userAgent.contains("Tablet")) {
            return "Tablet";
        }
        return "Desktop";
    }

    private String parseDeviceName(String userAgent) {
        if (userAgent == null) {
            return "Unknown";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "Unknown";
    }
}
