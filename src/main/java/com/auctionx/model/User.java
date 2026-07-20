package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;   // bcrypt hashed

    @Column(nullable = false)
    private String name;

    private String phone;


    @Column(name = "role")
    private String role;

    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private Boolean       isActive;

    public enum UserRole {
        ORGANIZER
    }

    public void setRole(UserRole r) {
        this.role = r != null ? r.name() : "ORGANIZER";
    }
    public UserRole getRoleEnum() {
        try { return UserRole.valueOf(this.role); }
        catch (Exception e) { return UserRole.ORGANIZER; }
    }
    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.isActive  = true;
        this.role      = "ORGANIZER";  // plain string
    }
}