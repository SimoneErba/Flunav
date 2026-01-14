package com.flunav.backend.models.response;

import com.flunav.backend.domain.Role;

public record UserResponse(String username, Role role) {
}