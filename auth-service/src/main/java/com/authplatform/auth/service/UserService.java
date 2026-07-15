package com.authplatform.auth.service;

import com.authplatform.auth.dto.RegisterRequest;
import com.authplatform.auth.dto.RegisterResponse;
import com.authplatform.auth.dto.UserResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.UserAlreadyExistsException;
import com.authplatform.auth.exception.UserNotFoundException;
import com.authplatform.auth.mapper.UserMapper;
import com.authplatform.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return userMapper.toResponse(user);
    }

    @Transactional
    public RegisterResponse registerUser(RegisterRequest request) {
        log.info("Registration attempt for email={}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException(request.email());
        }

        User user = userMapper.toEntity(request);
        User saved = userRepository.save(user);

        log.info("User registered successfully id={} email={}", saved.getId(), saved.getEmail());
        return RegisterResponse.of("Registration Successful");
    }
}
