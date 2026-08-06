package com.authplatform.auth.controller;

import com.authplatform.auth.dto.RefreshTokenRequest;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.InvalidCredentialsException;
import com.authplatform.auth.repository.UserRepository;
import com.authplatform.auth.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {

    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;

    public LogoutController(RefreshTokenService refreshTokenService, UserRepository userRepository) {
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
    }

    // public — a client logging out may already have an expired access token,
    // holding a still-valid refresh token is sufficient proof of intent to log out
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // authenticated — "log out everywhere" needs a currently-valid access token to identify the caller
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(InvalidCredentialsException::new);
        refreshTokenService.logoutAll(user.getId());
        return ResponseEntity.noContent().build();
    }
}
