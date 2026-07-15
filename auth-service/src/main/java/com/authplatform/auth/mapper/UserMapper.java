package com.authplatform.auth.mapper;

import com.authplatform.auth.dto.CreateUserRequest;
import com.authplatform.auth.dto.UserResponse;
import com.authplatform.auth.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toEntity(CreateUserRequest request) {
        return new User(
                request.username(),
                request.email(),
                request.firstName(),
                request.lastName()
        );
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
