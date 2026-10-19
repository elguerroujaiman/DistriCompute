package com.districompute.server;

import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.models.Task;
import com.districompute.common.utils.ServerConfig;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * DistriCompute Slave Server (V2).
 *
 * A standalone process that connects to the Master.
 * Pre-defined names and ports:
 *   Mercury → port 9092
 *   Venus   → port 9093
 *   Mars    → port 9094
 *
 * Usage (dual-port mode — connects to Master's slave listener port 9091):
 *   java -cp DistriComputeV2.jar com.districompute.server.SlaveServer &lt;masterHost&gt; &lt;slavePort&gt;
 *
 * Usage (unified-port mode — connects to Master's single port 9090):
 *   java -cp DistriComputeV2.jar com.districompute.server.SlaveServer &lt;masterHost&gt; &lt;slavePort&gt; --unified
 *
 * Example (all on localhost, dual mode):
 *   java -cp ... com.districompute.server.SlaveServer localhost 9092
 *
 * Example (remote via ngrok, unified mode):
 *   java -cp ... com.districompute.server.SlaveServer 0.tcp.ngrok.io 9092 --unified --master-port 12345
 */
public class SlaveServer {

    private final String masterHost;
    private final int slavePort;
    private int masterListenerPort;
    private boolean unifiedMode = false;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService taskPool = Executors.newFixedThreadPool(2);
    private Thread heartbeatThread;
    private Thread listenerThread;

    private String assignedName = "Unknown";

    public SlaveServer(String masterHost, int slavePort) {
        this.masterHost = masterHost;
        this.slavePort = slavePort;
        this.masterListenerPort = ServerConfig.getInstance().getSlavePort(); // default dual-port slave listener
    }

    public void setUnifiedMode(boolean unified) {
        this.unifiedMode = unified;
        if (unified) {
            this.masterListenerPort = ServerConfig.getInstance().getPort(); // connect to the single unified port
        }
    }

    public void setMasterListenerPort(int port) {
        this.masterListenerPort = port;
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        System.out.println("DistriCompute Slave starting on designated port " + slavePort);
        System.out.println("Connecting to Master at " + masterHost + ":" + masterListenerPort +
                (unifiedMode ? " (unified mode)" : " (dual-port mode)"));
        connectLoop();
    }

    private void connectLoop() {
        int retryDelay = 3000;
        while (running.get()) {
            try {
                connect();
                while (connected.get() && running.get()) {
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                System.out.println("[" + assignedName + "] Disconnected: " + e.getMessage() +
                        " — retrying in " + (retryDelay / 1000) + "s...");
            }

            if (running.get()) {
                try { Thread.sleep(retryDelay); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void connect() throws Exception {
        socket = new Socket(masterHost, masterListenerPort);
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        // Register with Master
        Map<String, String> reg = new LinkedHashMap<>();
        reg.put("slavePort", String.valueOf(slavePort));
        reg.put("javaVersion", System.getProperty("java.version"));
        reg.put("osName", System.getProperty("os.name"));
        out.writeObject(new NetworkMessage(Type.SLAVE_REGISTER, reg));
        out.flush();

        // Wait for ACK
        Object obj = in.readObject();
        if (obj instanceof NetworkMessage ack) {
            if (ack.getType() == Type.SLAVE_REGISTER_ACK && ack.isSuccess()) {
                assignedName = (String) ack.getPayload();
                connected.set(true);
                System.out.println("[" + assignedName + "] Registered with Master successfully.");
                startHeartbeat();
                listen();
            } else {
                String err = ack.getMessage() != null ? ack.getMessage() : "Unknown error";
                throw new IOException("Registration rejected: " + err);
            }
        }
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            int interval = ServerConfig.getInstance().getHeartbeatIntervalSeconds() * 1000;
            while (connected.get() && running.get()) {
                try {
                    Thread.sleep(interval);
                    synchronized (out) {
                        out.writeObject(NetworkMessage.of(Type.SLAVE_HEARTBEAT));
                        out.flush();
                        out.reset();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    System.out.println("[" + assignedName + "] Heartbeat failed: " + e.getMessage());
                    connected.set(false);
                    break;
                }
            }
        }, "slave-heartbeat-" + slavePort);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void listen() {
        listenerThread = new Thread(() -> {
            while (connected.get() && running.get()) {
                try {
                    Object obj = in.readObject();
                    if (!(obj instanceof NetworkMessage msg)) continue;

                    switch (msg.getType()) {
                        case SLAVE_EXECUTE_TASK -> {
                            Task task = (Task) msg.getPayload();
                            taskPool.submit(() -> executeTask(task));
                        }
                        case SLAVE_HEARTBEAT_ACK -> { /* ignore */ }
                        case SLAVE_CANCEL_TASK -> {
                            String taskId = (String) msg.getPayload();
                            System.out.println("[" + assignedName + "] Cancel request for " + taskId);
                        }
                        default -> System.out.println("[" + assignedName + "] Unknown: " + msg.getType());
                    }
                } catch (EOFException | java.net.SocketException e) {
                    connected.set(false);
                    break;
                } catch (Exception e) {
                    System.out.println("[" + assignedName + "] Listener error: " + e.getMessage());
                    connected.set(false);
                    break;
                }
            }
        }, "slave-listener-" + slavePort);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void executeTask(Task task) {
        String taskTitle = task.getTitle();
        sendLog("INFO", "TASK_RECEIVED", "Received task: " + taskTitle);

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("slave_" + assignedName + "_" + task.getId());
            Path jarPath = tempDir.resolve("task.jar");
            Files.write(jarPath, task.getJarData());

            // Parse transport field: mainClass|args
            String transport = task.getErrorMessage();
            String mainClass;
            String args;
            if (transport != null && transport.contains("|")) {
                String[] parts = transport.split("\\|", 2);
                mainClass = parts[0];
                args = parts[1];
            } else {
                mainClass = getMainClass(jarPath);
                args = "";
            }

            if (mainClass == null) {
                sendResult(task, false, null, "No Main-Class in JAR manifest.");
                return;
            }

            sendLog("INFO", "EXECUTING", "Running: " + mainClass + " with args: [" + args + "]");

            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add("-cp");
            cmd.add(jarPath.toAbsolutePath().toString());
            cmd.add(mainClass);
            if (!args.isBlank()) {
                for (String a : args.split("\\s+")) cmd.add(a);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());

            long start = System.currentTimeMillis();
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int timeoutSec = ServerConfig.getInstance().getExecutionTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                sendLog("ERROR", "TIMEOUT", "Task timed out after " + timeoutSec + "s");
                sendResult(task, false, null, "Execution timed out after " + timeoutSec + " seconds.");
                return;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                sendLog("ERROR", "EXIT_CODE", "Process exited with code " + exitCode);
                sendResult(task, false, null, "Process exited with code " + exitCode + ": " + output);
                return;
            }

            String result = output.toString().trim();
            sendLog("SUCCESS", "COMPLETED", "Task done in " + elapsed + "ms, output length: " + result.length());
            sendResult(task, true, result, null);

        } catch (Exception e) {
            sendLog("ERROR", "EXECUTION_ERROR", "Error: " + e.getMessage());
            sendResult(task, false, null, "Slave execution error: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir).sorted(Comparator.reverseOrder())
                            .map(Path::toFile).forEach(File::delete);
                } catch (Exception ignored) {}
            }
        }
    }

    private void sendResult(Task task, boolean success, String output, String error) {
        try {
            NetworkMessage result;
            if (success) {
                result = NetworkMessage.success(Type.SLAVE_TASK_RESULT, output);
            } else {
                result = NetworkMessage.error(Type.SLAVE_TASK_RESULT, error);
            }
            synchronized (out) {
                out.writeObject(result);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            System.out.println("[" + assignedName + "] Failed to send result: " + e.getMessage());
            connected.set(false);
        }
    }

    private void sendLog(String level, String action, String details) {
        try {
            Map<String, String> logData = new LinkedHashMap<>();
            logData.put("level", level);
            logData.put("action", action);
            logData.put("details", details);
            synchronized (out) {
                out.writeObject(new NetworkMessage(Type.SLAVE_LOG, logData));
                out.flush();
                out.reset();
            }
        } catch (IOException ignored) {}

        String prefix = switch (level) {
            case "SUCCESS" -> "✓";
            case "ERROR"   -> "✗";
            case "WARNING" -> "⚠";
            default        -> "•";
        };
        System.out.println("[" + assignedName + "] " + prefix + " " + action + ": " + details);
    }

    private String getMainClass(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return null;
            return manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }

    private void shutdown() {
        running.set(false);
        connected.set(false);
        taskPool.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        System.out.println("[" + assignedName + "] Slave shut down.");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: SlaveServer <masterHost> <slavePort> [--unified] [--master-port <port>]");
            System.out.println("  Slave ports: Mercury=9092, Venus=9093, Mars=9094");
            System.exit(1);
        }

        String masterHost = args[0];
        int slavePort = Integer.parseInt(args[1]);

        SlaveServer slave = new SlaveServer(masterHost, slavePort);

        // Parse optional flags
        for (int i = 2; i < args.length; i++) {
            if ("--unified".equalsIgnoreCase(args[i])) {
                slave.setUnifiedMode(true);
            } else if ("--master-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                slave.setMasterListenerPort(Integer.parseInt(args[++i]));
            }
        }

        slave.start();
    }
}
