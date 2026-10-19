package com.districompute.common.models;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represents a server activity log entry.
 */
public class LogEntry implements Serializable {

    public enum Level {
        INFO, WARNING, ERROR, SUCCESS
    }

    private String id;
    private Level level;
    private String action;
    private String details;
    private String userId;
    private String username;
    private LocalDateTime timestamp;

    public LogEntry() {}

    public LogEntry(Level level, String action, String details, String userId, String username) {
        this.id = java.util.UUID.randomUUID().toString();
        this.level = level;
        this.action = action;
        this.details = details;
        this.userId = userId;
        this.username = username;
        this.timestamp = LocalDateTime.now();
    }

    public String getId() { return id; }
    public Level getLevel() { return level; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public void setId(String id) { this.id = id; }
    public void setLevel(Level level) { this.level = level; }
    public void setAction(String action) { this.action = action; }
    public void setDetails(String details) { this.details = details; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setUsername(String username) { this.username = username; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return String.format("[%s] [%s] %s - %s (by %s)", timestamp, level, action, details, username);
    }
}
