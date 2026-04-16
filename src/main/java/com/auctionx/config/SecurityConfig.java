package com.auctionx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Phase 1 & 2 — open (add JWT in Phase 3 if needed)
                        .requestMatchers(
                                "/api/**",
                                "/ws/**",
                                "/uploads/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}