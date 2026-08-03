package com.authplatform.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 8;

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true; // @NotBlank already reports absence
        }

        context.disableDefaultConstraintViolation();
        boolean valid = true;

        if (password.length() < MIN_LENGTH) {
            violation(context, "password must be at least " + MIN_LENGTH + " characters long");
            valid = false;
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            violation(context, "password must contain at least one uppercase letter");
            valid = false;
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            violation(context, "password must contain at least one lowercase letter");
            valid = false;
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            violation(context, "password must contain at least one number");
            valid = false;
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            violation(context, "password must contain at least one special character");
            valid = false;
        }

        return valid;
    }

    private void violation(ConstraintValidatorContext context, String message) {
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}
