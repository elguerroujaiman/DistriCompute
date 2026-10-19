package com.districompute.common.utils;

import com.districompute.common.models.LogEntry;
import com.districompute.common.models.LogEntry.Level;
import com.districompute.dao.LogDAO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application logger — Singleton pattern.
 * Records all significant operations and persists them via LogDAO.
 * Also maintains an in-memory list for the live server console panel.
 *
 * Thread-safe via CopyOnWriteArrayList for the live log buffer.
 */
public class AppLogger {

    private static volatile AppLogger instance;
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LogDAO logDAO;
    private final List<LogEntry> liveBuffer = new CopyOnWriteArrayList<>();
    private final List<LogListener> listeners = new CopyOnWriteArrayList<>();
    private static final int MAX_LIVE_BUFFER = 500;

    private AppLogger() {}

    public static AppLogger getInstance() {
        if (instance == null) {
            synchronized (AppLogger.class) {
                if (instance == null) {
                    instance = new AppLogger();
                }
            }
        }
        return instance;
    }

    /**
     * Sets the LogDAO (called after DAOFactory is initialized).
     */
    public void setLogDAO(LogDAO logDAO) {
        this.logDAO = logDAO;
    }

    public void info(String action, String details, String userId, String username) {
        log(Level.INFO, action, details, userId, username);
    }

    public void success(String action, String details, String userId, String username) {
        log(Level.SUCCESS, action, details, userId, username);
    }

    public void warning(String action, String details, String userId, String username) {
        log(Level.WARNING, action, details, userId, username);
    }

    public void error(String action, String details, String userId, String username) {
        log(Level.ERROR, action, details, userId, username);
    }

    public void system(String action, String details) {
        log(Level.INFO, action, details, "SYSTEM", "SYSTEM");
    }

    private void log(Level level, String action, String details, String userId, String username) {
        LogEntry entry = new LogEntry(level, action, details, userId, username);

        // Persist
        if (logDAO != null) {
            try { logDAO.save(entry); } catch (Exception ignored) {}
        }

        // Live buffer (capped)
        if (liveBuffer.size() >= MAX_LIVE_BUFFER) {
            liveBuffer.remove(0);
        }
        liveBuffer.add(entry);

        // Console output
        System.out.printf("[%s] [%s] [%s] %s - %s%n",
                entry.getTimestamp().format(DISPLAY_FORMAT), level, username, action, details);

        // Notify UI listeners
        for (LogListener listener : listeners) {
            try { listener.onNewLog(entry); } catch (Exception ignored) {}
        }
    }

    public List<LogEntry> getLiveBuffer() {
        return new ArrayList<>(liveBuffer);
    }

    public void addListener(LogListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    /**
     * Listener interface for real-time log updates (e.g., server console panel).
     */
    public interface LogListener {
        void onNewLog(LogEntry entry);
    }
}
