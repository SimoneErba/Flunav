package com.flunav.backend.models.input;

import com.flunav.backend.domain.Role;

public record CreateUser(String username, String password, Role role) {
}