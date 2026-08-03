package com.authplatform.auth.service;

import com.authplatform.auth.dto.UserResponse;
import com.authplatform.auth.entity.User;
import com.authplatform.auth.exception.UserNotFoundException;
import com.authplatform.auth.mapper.UserMapper;
import com.authplatform.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

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
}
