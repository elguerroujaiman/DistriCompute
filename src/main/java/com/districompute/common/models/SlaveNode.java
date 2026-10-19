package com.districompute.common.models;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Slave node in the DistriCompute V2 distributed system.
 * Tracks connection status, workload, and execution history.
 */
public class SlaveNode implements Serializable {

    public enum Status {
        ONLINE, BUSY, OFFLINE
    }

    private String name;           // Mercury, Venus, Mars
    private int port;              // 9092, 9093, 9094
    private String host;
    private Status status;
    private String javaVersion;
    private String osName;
    private String currentTaskId;
    private String currentTaskTitle;
    private int activeTasks;
    private int completedTasks;
    private int failedTasks;
    private LocalDateTime connectedAt;
    private long uptimeMs;
    private List<Integer> recentLoadHistory = new ArrayList<>(); // last 10 load values

    public SlaveNode() {}

    public SlaveNode(String name, int port) {
        this.name = name;
        this.port = port;
        this.status = Status.OFFLINE;
        this.activeTasks = 0;
        this.completedTasks = 0;
        this.failedTasks = 0;
    }

    // Getters
    public String getName() { return name; }
    public int getPort() { return port; }
    public String getHost() { return host; }
    public Status getStatus() { return status; }
    public String getJavaVersion() { return javaVersion; }
    public String getOsName() { return osName; }
    public String getCurrentTaskId() { return currentTaskId; }
    public String getCurrentTaskTitle() { return currentTaskTitle; }
    public int getActiveTasks() { return activeTasks; }
    public int getCompletedTasks() { return completedTasks; }
    public int getFailedTasks() { return failedTasks; }
    public LocalDateTime getConnectedAt() { return connectedAt; }
    public long getUptimeMs() { return uptimeMs; }
    public List<Integer> getRecentLoadHistory() { return recentLoadHistory; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setPort(int port) { this.port = port; }
    public void setHost(String host) { this.host = host; }
    public void setStatus(Status status) { this.status = status; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public void setOsName(String osName) { this.osName = osName; }
    public void setCurrentTaskId(String currentTaskId) { this.currentTaskId = currentTaskId; }
    public void setCurrentTaskTitle(String currentTaskTitle) { this.currentTaskTitle = currentTaskTitle; }
    public void setActiveTasks(int activeTasks) { this.activeTasks = activeTasks; }
    public void setCompletedTasks(int completedTasks) { this.completedTasks = completedTasks; }
    public void setFailedTasks(int failedTasks) { this.failedTasks = failedTasks; }
    public void setConnectedAt(LocalDateTime connectedAt) { this.connectedAt = connectedAt; }
    public void setUptimeMs(long uptimeMs) { this.uptimeMs = uptimeMs; }
    public void setRecentLoadHistory(List<Integer> history) { this.recentLoadHistory = history; }

    public void incrementCompleted() { this.completedTasks++; }
    public void incrementFailed() { this.failedTasks++; }

    public void recordLoad(int load) {
        recentLoadHistory.add(load);
        if (recentLoadHistory.size() > 10) {
            recentLoadHistory.remove(0);
        }
    }

    public String getFormattedUptime() {
        long seconds = uptimeMs / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }
}
