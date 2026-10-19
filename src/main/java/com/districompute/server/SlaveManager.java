package com.districompute.server;

import com.districompute.common.models.SlaveNode;
import com.districompute.common.models.SlaveNode.Status;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;

import java.net.Socket;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages all connected Slave nodes.
 *
 * Responsibilities:
 * - Tracks which Slaves are online/offline/busy
 * - Assigns pre-defined names based on port (Mercury=9092, Venus=9093, Mars=9094)
 * - Provides load-balanced Slave selection
 * - Handles Slave disconnection and reconnection with name preservation
 * - Stores per-Slave logs for the tabbed console
 * - Notifies TaskExecutor when a Slave goes offline (for task reassignment)
 */
public class SlaveManager {

    private static volatile SlaveManager instance;

    private final AppLogger logger = AppLogger.getInstance();
    private final ServerConfig config = ServerConfig.getInstance();

    // Pre-defined slots: name -> SlaveNode
    private final Map<String, SlaveNode> slaveSlots = new LinkedHashMap<>();
    // Active connections: name -> SlaveConnection
    private final Map<String, SlaveConnection> connections = new ConcurrentHashMap<>();
    // Per-Slave log buffer: name -> list of log lines
    private final Map<String, List<String>> slaveLogs = new ConcurrentHashMap<>();
    // Grace period tracker: name -> disconnection time
    private final Map<String, Long> disconnectTimes = new ConcurrentHashMap<>();
    // Callback when a Slave goes offline (for task reassignment)
    private Runnable onSlaveOfflineCallback;

    // Uptime tracking: name -> connection timestamp
    private final Map<String, Long> connectTimes = new ConcurrentHashMap<>();

    private SlaveManager() {
        // Initialize pre-defined slots
        String[] names = ServerConfig.SLAVE_NAMES;
        int[] ports = ServerConfig.SLAVE_PORTS;
        for (int i = 0; i < names.length; i++) {
            SlaveNode node = new SlaveNode(names[i], ports[i]);
            slaveSlots.put(names[i], node);
            slaveLogs.put(names[i], new CopyOnWriteArrayList<>());
        }
    }

    public static SlaveManager getInstance() {
        if (instance == null) {
            synchronized (SlaveManager.class) {
                if (instance == null) instance = new SlaveManager();
            }
        }
        return instance;
    }

    /**
     * Called when a Slave connects to the Master's slave port.
     * Maps the connection to the correct pre-defined slot by port.
     */
    public synchronized SlaveNode onSlaveConnected(Socket socket, int slavePort,
                                                    String javaVersion, String osName) {
        // Find which slave this port belongs to
        String slaveName = null;
        int[] ports = ServerConfig.SLAVE_PORTS;
        String[] names = ServerConfig.SLAVE_NAMES;
        for (int i = 0; i < ports.length; i++) {
            if (ports[i] == slavePort) {
                slaveName = names[i];
                break;
            }
        }

        if (slaveName == null) {
            logger.error("SLAVE_UNKNOWN_PORT",
                    "Unknown slave port: " + slavePort, "SYSTEM", "SYSTEM");
            return null;
        }

        SlaveNode node = slaveSlots.get(slaveName);
        node.setHost(socket.getInetAddress().getHostAddress());
        node.setStatus(Status.ONLINE);
        node.setJavaVersion(javaVersion);
        node.setOsName(osName);
        node.setConnectedAt(LocalDateTime.now());
        node.setActiveTasks(0);
        node.setCurrentTaskId(null);
        node.setCurrentTaskTitle(null);

        connectTimes.put(slaveName, System.currentTimeMillis());
        disconnectTimes.remove(slaveName);

        logger.success("SLAVE_REGISTERED",
                slaveName + " registered — Java " + javaVersion + " on " + osName,
                "SYSTEM", "SYSTEM");

        return node;
    }

    /**
     * Called when a Slave disconnects.
     * Records disconnect time for grace period tracking.
     * Notifies TaskExecutor for task reassignment.
     */
    public void onSlaveDisconnected(String slaveName) {
        SlaveNode node = slaveSlots.get(slaveName);
        if (node == null) return;

        node.setStatus(Status.OFFLINE);
        node.setCurrentTaskId(null);
        node.setCurrentTaskTitle(null);
        node.setActiveTasks(0);

        connections.remove(slaveName);
        disconnectTimes.put(slaveName, System.currentTimeMillis());

        // Update uptime
        Long connectTime = connectTimes.remove(slaveName);
        if (connectTime != null) {
            node.setUptimeMs(System.currentTimeMillis() - connectTime);
        }

        logger.error("SLAVE_DISCONNECTED", slaveName + " went offline.", "SYSTEM", "SYSTEM");

        if (onSlaveOfflineCallback != null) {
            onSlaveOfflineCallback.run();
        }
    }

    /**
     * Registers an active SlaveConnection.
     */
    public void registerConnection(String slaveName, SlaveConnection conn) {
        connections.put(slaveName, conn);
    }

    /**
     * Returns the best available Slave for a new task using load-based selection.
     * Priority order if tied: Mercury > Venus > Mars.
     * Returns null if no Slaves are online.
     */
    public synchronized SlaveNode getBestAvailableSlave() {
        SlaveNode best = null;
        int lowestLoad = Integer.MAX_VALUE;

        String[] names = ServerConfig.SLAVE_NAMES;
        for (String name : names) {
            SlaveNode node = slaveSlots.get(name);
            if (node.getStatus() == Status.OFFLINE) continue;
            int load = node.getActiveTasks();
            if (load < lowestLoad) {
                lowestLoad = load;
                best = node;
            }
        }
        return best;
    }

    /**
     * Returns N available Slaves for a segmented task.
     * Returns fewer if not enough are online.
     */
    public synchronized List<SlaveNode> getAvailableSlaves(int count) {
        List<SlaveNode> available = new ArrayList<>();
        String[] names = ServerConfig.SLAVE_NAMES;
        for (String name : names) {
            if (available.size() >= count) break;
            SlaveNode node = slaveSlots.get(name);
            if (node.getStatus() != Status.OFFLINE) {
                available.add(node);
            }
        }
        return available;
    }

    /**
     * Returns count of currently online Slaves.
     */
    public int getOnlineSlaveCount() {
        return (int) slaveSlots.values().stream()
                .filter(s -> s.getStatus() != Status.OFFLINE)
                .count();
    }

    /**
     * Returns the SlaveConnection for a given Slave name.
     */
    public SlaveConnection getConnection(String slaveName) {
        return connections.get(slaveName);
    }

    /**
     * Marks a Slave as busy (executing a task).
     */
    public void markSlaveActive(String slaveName, String taskId, String taskTitle) {
        SlaveNode node = slaveSlots.get(slaveName);
        if (node == null) return;
        node.setStatus(Status.BUSY);
        node.setCurrentTaskId(taskId);
        node.setCurrentTaskTitle(taskTitle);
        node.setActiveTasks(node.getActiveTasks() + 1);
        node.recordLoad(node.getActiveTasks());
    }

    /**
     * Marks a Slave as idle after a task completes.
     */
    public void markSlaveIdle(String slaveName, boolean success) {
        SlaveNode node = slaveSlots.get(slaveName);
        if (node == null) return;
        int active = Math.max(0, node.getActiveTasks() - 1);
        node.setActiveTasks(active);
        if (active == 0) {
            node.setStatus(Status.ONLINE);
            node.setCurrentTaskId(null);
            node.setCurrentTaskTitle(null);
        }
        if (success) node.incrementCompleted();
        else node.incrementFailed();
        node.recordLoad(active);
    }

    /**
     * Records a log entry for a specific Slave.
     * Kept in memory buffer (max 500 per Slave).
     */
    public void recordSlaveLog(String slaveName, String level, String action, String details) {
        List<String> logs = slaveLogs.get(slaveName);
        if (logs == null) return;

        String prefix = switch (level.toUpperCase()) {
            case "SUCCESS" -> "✓";
            case "ERROR" -> "✗";
            case "WARNING" -> "⚠";
            default -> "•";
        };

        String timestamp = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String line = String.format("%s [%s] %s: %s", prefix, timestamp, action, details);
        logs.add(line);

        // Keep buffer bounded
        while (logs.size() > 500) logs.remove(0);
    }

    /**
     * Returns log lines for a specific Slave.
     */
    public List<String> getSlaveLog(String slaveName) {
        List<String> logs = slaveLogs.get(slaveName);
        return logs != null ? new ArrayList<>(logs) : new ArrayList<>();
    }

    /**
     * Returns all Slave nodes (both online and offline).
     */
    public List<SlaveNode> getAllSlaves() {
        // Update uptime before returning
        for (Map.Entry<String, SlaveNode> entry : slaveSlots.entrySet()) {
            Long connectTime = connectTimes.get(entry.getKey());
            if (connectTime != null) {
                entry.getValue().setUptimeMs(System.currentTimeMillis() - connectTime);
            }
        }
        return new ArrayList<>(slaveSlots.values());
    }

    public void setOnSlaveOfflineCallback(Runnable callback) {
        this.onSlaveOfflineCallback = callback;
    }

    public SlaveNode getSlaveByName(String name) {
        return slaveSlots.get(name);
    }
}
