package com.authplatform.auth.controller;

import com.authplatform.auth.dto.SessionResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.InvalidCredentialsException;
import com.authplatform.auth.repository.UserRepository;
import com.authplatform.auth.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final UserRepository userRepository;

    public SessionController(SessionService sessionService, UserRepository userRepository) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> getActiveSessions(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(InvalidCredentialsException::new);
        return ResponseEntity.ok(sessionService.listActiveSessions(user.getId()));
    }
}
