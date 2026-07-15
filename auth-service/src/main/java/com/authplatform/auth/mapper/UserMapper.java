package com.authplatform.auth.mapper;

import com.authplatform.auth.dto.RegisterRequest;
import com.authplatform.auth.dto.UserResponse;
import com.authplatform.auth.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toEntity(RegisterRequest request) {
        return new User(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password()
        );
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
