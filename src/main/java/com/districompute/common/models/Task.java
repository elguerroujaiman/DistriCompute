package com.districompute.common.models;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a computation task submitted to DistriCompute.
 * Built using the Builder pattern.
 */
public class Task implements Serializable {

    public enum Priority {
        LOW, MEDIUM, HIGH
    }

    public enum Status {
        PENDING,
        RECEIVED,
        VALIDATING,
        DISTRIBUTING,
        EXECUTING,
        MERGING,
        FINALIZING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private String id;
    private String title;
    private String description;
    private Priority priority;
    private Status status;
    private String submittedByUserId;
    private String submittedByUsername;
    private String assignedToUserId;
    private String assignedToUsername;
    private LocalDate deadline;
    private String batchId;
    private String jarFileName;
    private byte[] jarData;
    private String splitterFileName;
    private byte[] splitterData;
    private int requestedSlaves;
    private List<String> segmentArgs;
    private List<String> segmentResults;
    private List<String> assignedSlaves;
    private int segmentRetryCount;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long executionTimeMs;
    private String resultText;
    private String errorMessage;
    private int queuePosition;

    // Private constructor — use Builder
    private Task() {}

    // Getters
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Priority getPriority() { return priority; }
    public Status getStatus() { return status; }
    public String getSubmittedByUserId() { return submittedByUserId; }
    public String getSubmittedByUsername() { return submittedByUsername; }
    public String getAssignedToUserId() { return assignedToUserId; }
    public String getAssignedToUsername() { return assignedToUsername; }
    public LocalDate getDeadline() { return deadline; }
    public String getBatchId() { return batchId; }
    public String getJarFileName() { return jarFileName; }
    public byte[] getJarData() { return jarData; }
    public String getSplitterFileName() { return splitterFileName; }
    public byte[] getSplitterData() { return splitterData; }
    public int getRequestedSlaves() { return requestedSlaves; }
    public List<String> getSegmentArgs() { return segmentArgs; }
    public List<String> getSegmentResults() { return segmentResults; }
    public List<String> getAssignedSlaves() { return assignedSlaves; }
    public int getSegmentRetryCount() { return segmentRetryCount; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public String getResultText() { return resultText; }
    public String getErrorMessage() { return errorMessage; }
    public int getQueuePosition() { return queuePosition; }

    // Setters for mutable state
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public void setAssignedToUserId(String assignedToUserId) { this.assignedToUserId = assignedToUserId; }
    public void setAssignedToUsername(String assignedToUsername) { this.assignedToUsername = assignedToUsername; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public void setStatus(Status status) { this.status = status; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public void setResultText(String resultText) { this.resultText = resultText; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
    public void setJarData(byte[] jarData) { this.jarData = jarData; }
    public void setSplitterData(byte[] splitterData) { this.splitterData = splitterData; }
    public void setSegmentArgs(List<String> segmentArgs) { this.segmentArgs = segmentArgs; }
    public void setSegmentResults(List<String> segmentResults) { this.segmentResults = segmentResults; }
    public void setAssignedSlaves(List<String> assignedSlaves) { this.assignedSlaves = assignedSlaves; }
    public void setSegmentRetryCount(int segmentRetryCount) { this.segmentRetryCount = segmentRetryCount; }

    /**
     * Returns a formatted execution time string.
     */
    public String getFormattedExecutionTime() {
        if (executionTimeMs <= 0) return "-";
        if (executionTimeMs < 1000) return executionTimeMs + " ms";
        return String.format("%.2f s", executionTimeMs / 1000.0);
    }

    /**
     * Returns true if the task is in a terminal state.
     */
    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    /**
     * Returns true if the task can be cancelled.
     */
    public boolean isCancellable() {
        return status == Status.PENDING || status == Status.RECEIVED;
    }

    /**
     * Returns true if the task can be edited (only while PENDING).
     */
    public boolean isEditable() {
        return status == Status.PENDING;
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', title='" + title + "', status=" + status + ", priority=" + priority + "}";
    }

    // -------------------------------------------------------------------------
    // Builder Pattern
    // -------------------------------------------------------------------------

    public static class Builder {
        private final Task task = new Task();

        public Builder id(String id) { task.id = id; return this; }
        public Builder title(String title) { task.title = title; return this; }
        public Builder description(String description) { task.description = description; return this; }
        public Builder priority(Priority priority) { task.priority = priority; return this; }
        public Builder status(Status status) { task.status = status; return this; }
        public Builder submittedByUserId(String userId) { task.submittedByUserId = userId; return this; }
        public Builder submittedByUsername(String username) { task.submittedByUsername = username; return this; }
        public Builder assignedToUserId(String userId) { task.assignedToUserId = userId; return this; }
        public Builder assignedToUsername(String username) { task.assignedToUsername = username; return this; }
        public Builder deadline(LocalDate deadline) { task.deadline = deadline; return this; }
        public Builder batchId(String batchId) { task.batchId = batchId; return this; }
        public Builder jarFileName(String jarFileName) { task.jarFileName = jarFileName; return this; }
        public Builder jarData(byte[] jarData) { task.jarData = jarData; return this; }
        public Builder splitterFileName(String name) { task.splitterFileName = name; return this; }
        public Builder splitterData(byte[] data) { task.splitterData = data; return this; }
        public Builder requestedSlaves(int n) { task.requestedSlaves = n; return this; }
        public Builder submittedAt(LocalDateTime submittedAt) { task.submittedAt = submittedAt; return this; }

        public Task build() {
            if (task.id == null || task.title == null || task.priority == null) {
                throw new IllegalStateException("Task requires id, title, and priority.");
            }
            if (task.status == null) task.status = Status.PENDING;
            if (task.submittedAt == null) task.submittedAt = LocalDateTime.now();
            return task;
        }
    }
}
