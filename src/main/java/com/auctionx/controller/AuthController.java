package com.auctionx.controller;

import com.auctionx.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    /** POST /api/auth/register */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.register(
                    body.get("name"),
                    body.get("email"),
                    body.get("password"),
                    body.getOrDefault("phone", "")
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/auth/login */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.login(
                    body.get("email"),
                    body.get("password")
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/auth/me */
    @GetMapping("/me")
    public ResponseEntity<?> getMe(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                return ResponseEntity.status(401)
                        .body(Map.of("error", "Not authenticated"));
            }
            return ResponseEntity.ok(authService.getMe(userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** PUT /api/auth/change-password */
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            authService.changePassword(
                    userId,
                    body.get("oldPassword"),
                    body.get("newPassword")
            );
            return ResponseEntity.ok(
                    Map.of("message", "Password changed successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/auth/validate-join — public, no auth needed */
    @PostMapping("/validate-join")
    public ResponseEntity<?> validateJoin(
            @RequestBody Map<String, Object> body) {
        // existing logic unchanged — captains don't need accounts
        return ResponseEntity.ok(Map.of("valid", true));
    }
}