package com.authplatform.auth.service;

import com.authplatform.auth.dto.RegisterRequest;
import com.authplatform.auth.dto.RegisterResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.entity.VerificationToken;
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
    private final EmailVerificationService emailVerificationService;
    private final EmailService emailService;

    public RegistrationService(UserRepository userRepository, UserMapper userMapper, PasswordEncoder passwordEncoder,
                                EmailVerificationService emailVerificationService, EmailService emailService) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.emailService = emailService;
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

        VerificationToken token = emailVerificationService.generateVerificationToken(saved);
        emailService.sendVerificationEmail(saved.getEmail(), token.getToken());

        log.info("User registered successfully id={} email={} status=PENDING", saved.getId(), saved.getEmail());
        return RegisterResponse.of("Registration successful. Please verify your email to activate your account.");
    }
}
