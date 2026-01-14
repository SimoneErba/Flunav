package com.flunav.backend.controllers;

import com.flunav.backend.domain.User;
import com.flunav.backend.models.input.CreateUser;
import com.flunav.backend.models.response.UserResponse;
import com.flunav.backend.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUser request) {
        User newUser = new User(request.username(), request.password(), request.role());
        User createdUser = userService.createUser(newUser);
        return ResponseEntity.ok(new UserResponse(createdUser.getUsername(), createdUser.getRole()));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers().stream()
                .map(u -> new UserResponse(u.getUsername(), u.getRole()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @DeleteMapping("/{username}")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable String username) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth.getName().equals(username)) {
            return ResponseEntity.badRequest().build();
        }

        userService.deleteUser(username);
        return ResponseEntity.noContent().build();
    }
}