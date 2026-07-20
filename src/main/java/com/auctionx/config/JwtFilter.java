package com.auctionx.config;

import com.auctionx.service.JwtService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    // Paths that don't need a token
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/validate-join",
            "/ws",
            "/uploads"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip JWT check for public endpoints
        boolean isPublic = PUBLIC_PATHS.stream()
                .anyMatch(path::startsWith);
        if (isPublic) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isTokenValid(token)) {
                String email  = jwtService.extractEmail(token);
                Long   userId = jwtService.extractUserId(token);
                String name   = jwtService.extractName(token);

                // Set auth in security context
                var auth = new UsernamePasswordAuthenticationToken(
                        email, null, List.of()
                );
                auth.setDetails(
                        new WebAuthenticationDetailsSource()
                                .buildDetails(request));
                SecurityContextHolder.getContext()
                        .setAuthentication(auth);

                // Pass userId to controllers via request attribute
                request.setAttribute("userId", userId);
                request.setAttribute("userName", name);
                request.setAttribute("userEmail", email);
            }
        }

        chain.doFilter(request, response);
    }
}