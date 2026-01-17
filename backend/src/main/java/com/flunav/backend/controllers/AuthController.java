package com.flunav.backend.controllers;

import com.flunav.backend.domain.RefreshToken;
import com.flunav.backend.domain.User;
import com.flunav.backend.services.RefreshTokenService;
import com.flunav.backend.services.UserService;
import com.flunav.backend.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    public AuthController(UserService userService, PasswordEncoder passwordEncoder, JwtUtils jwtUtils, RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String refreshToken, String role) {
    }

    public record TokenRefreshRequest(String refreshToken) {
    }

    public record TokenRefreshResponse(String accessToken, String refreshToken) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = userService.getUserByUsername(request.username());

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (passwordEncoder.matches(request.password(), user.getPassword())) {
                String token = jwtUtils.generateToken(user.getUsername(), user.getRole());
                RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getUsername());
                return ResponseEntity.ok(new LoginResponse(token, refreshToken.getToken(), user.getRole().name()));
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid credentials");
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshtoken(@RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.refreshToken();

        return refreshTokenService.findByToken(requestRefreshToken)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUsername)
                .map(username -> {
                    Optional<User> userOpt = userService.getUserByUsername(username);
                    if (userOpt.isPresent()) {
                        User user = userOpt.get();
                        String token = jwtUtils.generateToken(user.getUsername(), user.getRole());
                        // Rotate refresh token
                        RefreshToken newRefreshToken = refreshTokenService.createRefreshToken(username);
                        return ResponseEntity.ok(new TokenRefreshResponse(token, newRefreshToken.getToken()));
                    }
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
                })
                .orElseThrow(() -> new RuntimeException("Refresh token is not in database!"));
    }
}