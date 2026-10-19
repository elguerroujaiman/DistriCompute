package com.districompute.common.models;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represents a user in the DistriCompute system.
 * Built using the Builder pattern for clean object construction.
 */
public class User implements Serializable {

    public enum Role {
        ADMIN, STANDARD
    }

    private String id;
    private String username;
    private String passwordHash;
    private String email;
    private Role role;
    private LocalDateTime createdAt;
    private boolean active;

    // Private constructor — use Builder
    private User() {}

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isActive() { return active; }
    public boolean isAdmin() { return role == Role.ADMIN; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setActive(boolean active) { this.active = active; }
    public void setRole(Role role) { this.role = role; }

    /**
     * Returns initials from username for avatar display (max 2 chars).
     */
    public String getInitials() {
        if (username == null || username.isEmpty()) return "?";
        String[] parts = username.trim().split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].charAt(0) + "" + parts[1].charAt(0)).toUpperCase();
        }
        return username.substring(0, Math.min(2, username.length())).toUpperCase();
    }

    @Override
    public String toString() {
        return "User{id='" + id + "', username='" + username + "', role=" + role + "}";
    }

    // -------------------------------------------------------------------------
    // Builder Pattern
    // -------------------------------------------------------------------------

    public static class Builder {
        private final User user = new User();

        public Builder id(String id) { user.id = id; return this; }
        public Builder username(String username) { user.username = username; return this; }
        public Builder passwordHash(String passwordHash) { user.passwordHash = passwordHash; return this; }
        public Builder email(String email) { user.email = email; return this; }
        public Builder role(Role role) { user.role = role; return this; }
        public Builder createdAt(LocalDateTime createdAt) { user.createdAt = createdAt; return this; }
        public Builder active(boolean active) { user.active = active; return this; }

        public User build() {
            if (user.id == null || user.username == null || user.role == null) {
                throw new IllegalStateException("User requires id, username, and role.");
            }
            if (user.createdAt == null) user.createdAt = LocalDateTime.now();
            if (!user.active) user.active = true;
            return user;
        }
    }
}
