package com.authplatform.auth.service;

import com.authplatform.auth.dto.RegisterRequest;
import com.authplatform.auth.dto.RegisterResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.UserAlreadyExistsException;
import com.authplatform.auth.mapper.UserMapper;
import com.authplatform.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterResponse registerUser(RegisterRequest request) {
        log.info("Registration attempt for email={}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException(request.email());
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.password()));
        User saved = userRepository.save(user);

        log.info("User registered successfully id={} email={}", saved.getId(), saved.getEmail());
        return RegisterResponse.of("Registration Successful");
    }
}
