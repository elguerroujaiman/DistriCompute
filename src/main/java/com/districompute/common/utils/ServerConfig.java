package com.districompute.common.utils;

/**
 * Server configuration — Singleton pattern.
 * Provides a single, globally accessible configuration instance.
 *
 * Thread-safe via double-checked locking.
 */
public class ServerConfig {

    private static volatile ServerConfig instance;

    private int port = 9090;
    private int slavePort = 9091;
    private int maxQueueSize = 20;
    private int maxTasksPerUser = 5;
    private int maxFilesPerSubmission = 5;
    private String dataDirectory = "data";
    private String storageType = "JSON";
    private int executionTimeoutSeconds = 120;
    private int heartbeatIntervalSeconds = 3;
    private int heartbeatTimeoutSeconds = 9;
    private int maxSegmentRetries = 3;
    private int slaveReconnectGraceSeconds = 30;
    private boolean unifiedPort = false;

    // Pre-defined slave configuration: name -> port
    public static final String[] SLAVE_NAMES = {"Mercury", "Venus", "Mars"};
    public static final int[] SLAVE_PORTS = {9092, 9093, 9094};

    private ServerConfig() {}

    /**
     * Returns the single ServerConfig instance (thread-safe).
     */
    public static ServerConfig getInstance() {
        if (instance == null) {
            synchronized (ServerConfig.class) {
                if (instance == null) {
                    instance = new ServerConfig();
                }
            }
        }
        return instance;
    }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getSlavePort() { return slavePort; }
    public void setSlavePort(int slavePort) { this.slavePort = slavePort; }

    public int getMaxQueueSize() { return maxQueueSize; }
    public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }

    public int getMaxTasksPerUser() { return maxTasksPerUser; }
    public void setMaxTasksPerUser(int maxTasksPerUser) { this.maxTasksPerUser = maxTasksPerUser; }

    public int getMaxFilesPerSubmission() { return maxFilesPerSubmission; }
    public void setMaxFilesPerSubmission(int maxFilesPerSubmission) { this.maxFilesPerSubmission = maxFilesPerSubmission; }

    public String getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(String dataDirectory) { this.dataDirectory = dataDirectory; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(int executionTimeoutSeconds) { this.executionTimeoutSeconds = executionTimeoutSeconds; }

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public int getMaxSegmentRetries() { return maxSegmentRetries; }
    public int getSlaveReconnectGraceSeconds() { return slaveReconnectGraceSeconds; }

    public boolean isUnifiedPort() { return unifiedPort; }
    public void setUnifiedPort(boolean unifiedPort) { this.unifiedPort = unifiedPort; }
}
