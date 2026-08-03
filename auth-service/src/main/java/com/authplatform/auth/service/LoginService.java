package com.authplatform.auth.service;

import com.authplatform.auth.dto.LoginRequest;
import com.authplatform.auth.dto.LoginResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.entity.UserStatus;
import com.authplatform.auth.exception.InvalidCredentialsException;
import com.authplatform.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        log.info("Login attempt for email={}", request.email());

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Login failed for email={}: wrong password", request.email());
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("Login failed for email={}: account status={}", request.email(), user.getStatus());
            throw new InvalidCredentialsException();
        }

        log.info("Login successful for email={}", request.email());
        return LoginResponse.of("Login successful");
    }
}
