package com.districompute.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stores the current logged-in user's session state on the client side.
 * Singleton — one session per application instance.
 */
public class ClientSession {

    private static volatile ClientSession instance;

    private String userId;
    private String username;
    private String email;
    private String role;
    private boolean loggedIn = false;

    // Notifications list
    private final List<String[]> notifications = new ArrayList<>(); // [title, body, time, read]
    private int unreadCount = 0;

    private ClientSession() {}

    public static ClientSession getInstance() {
        if (instance == null) {
            synchronized (ClientSession.class) {
                if (instance == null) instance = new ClientSession();
            }
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    public void setFromMap(Map<String, Object> userMap) {
        this.userId = (String) userMap.get("id");
        this.username = (String) userMap.get("username");
        this.email = (String) userMap.getOrDefault("email", "");
        this.role = (String) userMap.getOrDefault("role", "STANDARD");
        this.loggedIn = true;
    }

    public void clear() {
        userId = null; username = null; email = null; role = null;
        loggedIn = false;
        notifications.clear();
        unreadCount = 0;
    }

    public void addNotification(String title, String body) {
        String time = java.time.format.DateTimeFormatter
                .ofPattern("HH:mm").format(java.time.LocalDateTime.now());
        notifications.add(0, new String[]{title, body, time, "false"});
        if (notifications.size() > 50) notifications.remove(notifications.size() - 1);
        unreadCount++;
    }

    public void markAllRead() {
        for (String[] n : notifications) n[3] = "true";
        unreadCount = 0;
    }

    public List<String[]> getNotifications() { return notifications; }
    public int getUnreadCount() { return unreadCount; }

    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public boolean isLoggedIn() { return loggedIn; }
    public boolean isAdmin() { return "ADMIN".equalsIgnoreCase(role); }

    public String getInitials() {
        if (username == null || username.isEmpty()) return "?";
        String[] parts = username.trim().split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].charAt(0) + "" + parts[1].charAt(0)).toUpperCase();
        }
        return username.substring(0, Math.min(2, username.length())).toUpperCase();
    }

    /**
     * Generates a consistent color for the user's avatar based on username.
     */
    public String getAvatarColor() {
        if (username == null) return "#4f8ef7";
        String[] colors = {
                "#4f8ef7", "#7c5cbf", "#2dd69b", "#f5a623",
                "#f5564a", "#00bcd4", "#e91e8c", "#8bc34a"
        };
        int idx = Math.abs(username.hashCode()) % colors.length;
        return colors[idx];
    }
}
