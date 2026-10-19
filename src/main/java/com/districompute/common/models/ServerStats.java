package com.districompute.common.models;

import java.io.Serializable;

/**
 * Snapshot of server statistics sent to clients.
 */
public class ServerStats implements Serializable {

    private int totalTasks;
    private int completedTasks;
    private int failedTasks;
    private int pendingTasks;
    private int connectedClients;
    private int totalUsers;
    private int onlineSlaves;

    public ServerStats() {}

    public int getTotalTasks() { return totalTasks; }
    public void setTotalTasks(int totalTasks) { this.totalTasks = totalTasks; }

    public int getCompletedTasks() { return completedTasks; }
    public void setCompletedTasks(int completedTasks) { this.completedTasks = completedTasks; }

    public int getFailedTasks() { return failedTasks; }
    public void setFailedTasks(int failedTasks) { this.failedTasks = failedTasks; }

    public int getPendingTasks() { return pendingTasks; }
    public void setPendingTasks(int pendingTasks) { this.pendingTasks = pendingTasks; }

    public int getConnectedClients() { return connectedClients; }
    public void setConnectedClients(int connectedClients) { this.connectedClients = connectedClients; }

    public int getTotalUsers() { return totalUsers; }
    public void setTotalUsers(int totalUsers) { this.totalUsers = totalUsers; }

    public int getOnlineSlaves() { return onlineSlaves; }
    public void setOnlineSlaves(int onlineSlaves) { this.onlineSlaves = onlineSlaves; }
}
