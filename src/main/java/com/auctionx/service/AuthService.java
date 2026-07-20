package com.auctionx.service;

import com.auctionx.model.User;
import com.auctionx.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService     jwtService;
    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    // ── Register ──────────────────────────────────────────────────
    public Map<String, Object> register(String name,
                                        String email,
                                        String password,
                                        String phone) {
        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException(
                    "Email already registered. Please login.");
        }

        User user = User.builder()
                .name(name)
                .email(email.toLowerCase().trim())
                .password(passwordEncoder.encode(password))
                .phone(phone)
                .role("ORGANIZER")
                .build();

        User saved = userRepository.save(user);

        String token = jwtService.generateToken(
                saved.getId(), saved.getEmail(), saved.getName());

        log.info("New user registered: {} ({})", name, email);

        return buildResponse(saved, token);
    }

    // ── Login ─────────────────────────────────────────────────────
    public Map<String, Object> login(String email, String password) {
        User user = userRepository.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() ->
                        new RuntimeException(
                                "No account found with this email"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Incorrect password");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new RuntimeException("Account is deactivated");
        }

        // Update last login
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtService.generateToken(
                user.getId(), user.getEmail(), user.getName());

        log.info("User logged in: {}", email);

        return buildResponse(user, token);
    }

    // ── Get current user from token ───────────────────────────────
    public Map<String, Object> getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new RuntimeException("User not found"));
        return Map.of(
                "id",    user.getId(),
                "name",  user.getName(),
                "email", user.getEmail(),
                "phone", user.getPhone() != null ? user.getPhone() : "",
                "role",  user.getRole()
        );
    }

    // ── Change password ───────────────────────────────────────────
    public void changePassword(Long userId,
                               String oldPassword,
                               String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new RuntimeException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    // ── Private ───────────────────────────────────────────────────
    private Map<String, Object> buildResponse(User user, String token) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token",  token);
        resp.put("userId", user.getId());
        resp.put("name",   user.getName());
        resp.put("email",  user.getEmail());
        resp.put("role",   user.getRole());
        return resp;
    }
}