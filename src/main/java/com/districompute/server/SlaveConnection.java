package com.districompute.server;

import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.models.SlaveNode;
import com.districompute.common.models.Task;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;

import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Master-side connection handler for a single Slave node.
 *
 * Responsibilities:
 * - Receives heartbeats and detects Slave crashes
 * - Sends task execution requests to the Slave
 * - Receives task results back from the Slave
 * - Routes Slave log entries to AppLogger
 *
 * Uses BlockingQueue to route responses (same pattern as ServerConnection)
 * so the heartbeat listener and sendRequest() don't race on the stream.
 */
public class SlaveConnection implements Runnable {

    private final Socket socket;
    private final SlaveNode slaveNode;
    private final SlaveManager slaveManager;
    private final AppLogger logger = AppLogger.getInstance();
    private final ServerConfig config = ServerConfig.getInstance();

    private ObjectOutputStream out;
    private ObjectInputStream in;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final BlockingQueue<NetworkMessage> responseQueue = new LinkedBlockingQueue<>(1);

    private volatile long lastHeartbeatTime;
    private Thread listenerThread;
    private Thread heartbeatMonitorThread;

    // Callback invoked when the Slave sends a task result
    private Consumer<NetworkMessage> taskResultCallback;

    public SlaveConnection(Socket socket, SlaveNode slaveNode, SlaveManager slaveManager) {
        this.socket = socket;
        this.slaveNode = slaveNode;
        this.slaveManager = slaveManager;
    }

    public SlaveConnection(Socket socket, SlaveNode slaveNode, SlaveManager slaveManager,
                           ObjectOutputStream out, ObjectInputStream in) {
        this.socket = socket;
        this.slaveNode = slaveNode;
        this.slaveManager = slaveManager;
        this.out = out;
        this.in = in;
    }

    public void setTaskResultCallback(Consumer<NetworkMessage> callback) {
        this.taskResultCallback = callback;
    }

    @Override
    public void run() {
        try {
            // Only create streams if not already provided by handleSlaveHandshake
            if (out == null) {
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
            }
            if (in == null) {
                in = new ObjectInputStream(socket.getInputStream());
            }
            connected.set(true);
            lastHeartbeatTime = System.currentTimeMillis();

            startListenerThread();
            startHeartbeatMonitor();

            // Send registration acknowledgement
            sendMessage(NetworkMessage.success(Type.SLAVE_REGISTER_ACK, slaveNode.getName()));

            logger.success("SLAVE_CONNECTED",
                    slaveNode.getName() + " connected from " + socket.getInetAddress().getHostAddress(),
                    "SYSTEM", "SYSTEM");

        } catch (IOException e) {
            logger.error("SLAVE_CONNECT_ERROR",
                    "Failed to set up streams for " + slaveNode.getName() + ": " + e.getMessage(),
                    "SYSTEM", "SYSTEM");
            handleDisconnect();
        }
    }

    /**
     * Sends a task to this Slave for execution.
     * Blocks until the Slave acknowledges or timeout.
     */
    public NetworkMessage sendTask(Task task) {
        try {
            responseQueue.clear();
            sendMessage(new NetworkMessage(Type.SLAVE_EXECUTE_TASK, task));
            NetworkMessage response = responseQueue.poll(
                    config.getExecutionTimeoutSeconds() + 30L, TimeUnit.SECONDS);
            if (response == null) {
                return NetworkMessage.error(Type.ERROR, "Slave " + slaveNode.getName() + " timed out.");
            }
            return response;
        } catch (Exception e) {
            return NetworkMessage.error(Type.ERROR, "Send failed: " + e.getMessage());
        }
    }

    /**
     * Sends a cancel request to this Slave for the given task.
     */
    public void cancelTask(String taskId) {
        try {
            sendMessage(new NetworkMessage(Type.SLAVE_CANCEL_TASK, taskId));
        } catch (Exception ignored) {}
    }

    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            while (connected.get() && !socket.isClosed()) {
                try {
                    Object obj = in.readObject();
                    if (!(obj instanceof NetworkMessage msg)) continue;

                    switch (msg.getType()) {
                        case SLAVE_HEARTBEAT -> {
                            lastHeartbeatTime = System.currentTimeMillis();
                            sendMessage(NetworkMessage.of(Type.SLAVE_HEARTBEAT_ACK));
                        }
                        case SLAVE_TASK_RESULT -> {
                            // Put result in queue for sendTask() to pick up
                            responseQueue.offer(msg, 2, TimeUnit.SECONDS);
                            // Also notify callback for async handling
                            if (taskResultCallback != null) taskResultCallback.accept(msg);
                        }
                        case SLAVE_LOG -> {
                            @SuppressWarnings("unchecked")
                            Map<String, String> logData = (Map<String, String>) msg.getPayload();
                            if (logData != null) {
                                slaveManager.recordSlaveLog(slaveNode.getName(),
                                        logData.getOrDefault("level", "INFO"),
                                        logData.getOrDefault("action", ""),
                                        logData.getOrDefault("details", ""));
                            }
                        }
                        default -> responseQueue.offer(msg, 2, TimeUnit.SECONDS);
                    }
                } catch (EOFException | java.net.SocketException e) {
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException | ClassNotFoundException e) {
                    if (connected.get()) {
                        logger.error("SLAVE_LISTENER_ERROR",
                                slaveNode.getName() + " listener error: " + e.getMessage(),
                                "SYSTEM", "SYSTEM");
                    }
                    break;
                }
            }
            handleDisconnect();
        }, "slave-listener-" + slaveNode.getName());
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void startHeartbeatMonitor() {
        heartbeatMonitorThread = new Thread(() -> {
            int timeoutMs = config.getHeartbeatTimeoutSeconds() * 1000;
            while (connected.get()) {
                try {
                    Thread.sleep(1000);
                    long elapsed = System.currentTimeMillis() - lastHeartbeatTime;
                    if (elapsed > timeoutMs) {
                        logger.error("SLAVE_HEARTBEAT_TIMEOUT",
                                slaveNode.getName() + " missed heartbeats — declaring offline.",
                                "SYSTEM", "SYSTEM");
                        handleDisconnect();
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "slave-heartbeat-monitor-" + slaveNode.getName());
        heartbeatMonitorThread.setDaemon(true);
        heartbeatMonitorThread.start();
    }

    private synchronized void sendMessage(NetworkMessage msg) throws IOException {
        out.writeObject(msg);
        out.flush();
        out.reset();
    }

    private void handleDisconnect() {
        if (!connected.compareAndSet(true, false)) return; // already handled
        try { socket.close(); } catch (IOException ignored) {}
        slaveManager.onSlaveDisconnected(slaveNode.getName());
    }

    public boolean isConnected() {
        return connected.get() && !socket.isClosed();
    }

    public SlaveNode getSlaveNode() {
        return slaveNode;
    }
}
