package com.districompute.server;

import com.districompute.common.models.SlaveNode;
import com.districompute.common.models.Task;
import com.districompute.common.models.Task.Status;
import com.districompute.common.models.NetworkMessage;
import com.districompute.common.models.NetworkMessage.Type;
import com.districompute.common.patterns.ExecutionStrategy;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * V2 ExecutionStrategy — distributes task segments across Slave nodes.
 *
 * Flow:
 * 1. Run the Splitter JAR locally to get segment arguments
 * 2. Assign one segment per Slave
 * 3. Send each segment to its assigned Slave
 * 4. Collect results with retry on failure (max 3 attempts per segment)
 * 5. Merge results labeled by Slave name
 */
public class DistributedExecutionStrategy implements ExecutionStrategy {

    private final SlaveManager slaveManager;
    private final AppLogger logger = AppLogger.getInstance();
    private final ServerConfig config = ServerConfig.getInstance();

    public DistributedExecutionStrategy(SlaveManager slaveManager) {
        this.slaveManager = slaveManager;
    }

    @Override
    public String getStrategyName() {
        return "Distributed Execution (V2 - Master + Slaves)";
    }

    @Override
    public void execute(Task task) {
        long startTime = System.currentTimeMillis();
        task.setStartedAt(java.time.LocalDateTime.now());

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("districompute_v2_" + task.getId());

            // --- Step 1: Validate and write JARs ---
            if (task.getJarData() == null || task.getJarData().length == 0) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("No Task JAR data provided.");
                return;
            }
            if (task.getSplitterData() == null || task.getSplitterData().length == 0) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("No Splitter JAR data provided.");
                return;
            }

            Path taskJarPath = tempDir.resolve("task.jar");
            Path splitterJarPath = tempDir.resolve("splitter.jar");

            Files.write(taskJarPath, task.getJarData());
            Files.write(splitterJarPath, task.getSplitterData());

            // --- Step 2: Validate main classes ---
            String taskMainClass = getMainClass(taskJarPath);
            if (taskMainClass == null) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("Task JAR manifest does not specify a Main-Class.");
                return;
            }
            String splitterMainClass = getMainClass(splitterJarPath);
            if (splitterMainClass == null) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("Splitter JAR manifest does not specify a Main-Class.");
                return;
            }

            // --- Step 3: Run Splitter JAR to get segments ---
            int requestedSlaves = task.getRequestedSlaves() > 0 ? task.getRequestedSlaves() : 1;
            List<String> segments = runSplitter(splitterJarPath, splitterMainClass, requestedSlaves);

            if (segments == null || segments.isEmpty()) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("Splitter JAR produced no output.");
                return;
            }
            if (segments.size() != requestedSlaves) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("Splitter JAR produced " + segments.size() +
                        " segments but " + requestedSlaves + " Slaves were requested.");
                return;
            }

            task.setSegmentArgs(segments);

            // --- Step 4: Get available Slaves ---
            List<SlaveNode> assignedSlaves = slaveManager.getAvailableSlaves(requestedSlaves);
            if (assignedSlaves.isEmpty()) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("No Slave nodes are currently online.");
                return;
            }
            if (assignedSlaves.size() < requestedSlaves) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("Requested " + requestedSlaves + " Slaves but only " +
                        assignedSlaves.size() + " are online.");
                return;
            }

            List<String> assignedNames = new ArrayList<>();
            for (SlaveNode s : assignedSlaves) assignedNames.add(s.getName());
            task.setAssignedSlaves(assignedNames);

            logger.info("TASK_DISTRIBUTING",
                    "Distributing '" + task.getTitle() + "' across: " + String.join(", ", assignedNames),
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());

            // --- Step 5: Execute segments in parallel with retries ---
            List<String> segmentResults = executeSegments(
                    task, taskJarPath, taskMainClass, segments, assignedSlaves, tempDir);

            // --- Step 6: Merge results ---
            task.setSegmentResults(segmentResults);
            String merged = mergeResults(assignedNames, segmentResults);
            task.setResultText(merged);
            task.setStatus(Status.COMPLETED);

            logger.success("TASK_COMPLETED",
                    "Task '" + task.getTitle() + "' distributed and completed.",
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());

        } catch (Exception e) {
            task.setStatus(Status.FAILED);
            task.setErrorMessage("Distribution error: " + e.getMessage());
            logger.error("TASK_DISTRIBUTE_ERROR",
                    "Task '" + task.getTitle() + "' failed: " + e.getMessage(),
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());
        } finally {
            task.setCompletedAt(java.time.LocalDateTime.now());
            task.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            if (tempDir != null) {
                try { deleteDirectory(tempDir); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Runs the Splitter JAR and parses its output into segment argument strings.
     * Expected output format (one line per segment):
     *   1 333333
     *   333334 666666
     *   666667 1000000
     */
    private List<String> runSplitter(Path jarPath, String mainClass, int expectedSegments)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder("java", "-cp",
                jarPath.toAbsolutePath().toString(), mainClass,
                String.valueOf(expectedSegments));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Handle both \n and \r\n line endings
                line = line.trim();
                if (!line.isBlank()) lines.add(line);
            }
        }

        process.waitFor(30, TimeUnit.SECONDS);
        return lines;
    }

    /**
     * Executes all segments in parallel across assigned Slaves.
     * Retries failed segments on other available Slaves (max 3 attempts).
     */
    private List<String> executeSegments(Task task, Path taskJarPath, String mainClass,
                                          List<String> segments, List<SlaveNode> slaves,
                                          Path tempDir) throws Exception {

        int numSegments = segments.size();
        String[] results = new String[numSegments];
        int totalRetries = 0;
        int maxRetries = config.getMaxSegmentRetries();

        // Submit all segments in parallel
        ExecutorService pool = Executors.newFixedThreadPool(numSegments);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numSegments; i++) {
            final int segIndex = i;
            final SlaveNode slave = slaves.get(i);
            final String args = segments.get(i);

            futures.add(pool.submit(() ->
                    executeSegmentWithRetry(task, taskJarPath, mainClass, args,
                            slave, segIndex, maxRetries)));
        }

        pool.shutdown();
        pool.awaitTermination(config.getExecutionTimeoutSeconds() + 60L, TimeUnit.SECONDS);

        // Collect results
        for (int i = 0; i < numSegments; i++) {
            try {
                results[i] = futures.get(i).get();
            } catch (ExecutionException e) {
                results[i] = "FAILED after " + maxRetries + " attempts — segment unavailable";
            }
        }

        return Arrays.asList(results);
    }

    /**
     * Executes one segment on a Slave with retry logic.
     * On failure, tries other available Slaves up to maxRetries times.
     */
    private String executeSegmentWithRetry(Task task, Path taskJarPath, String mainClass,
                                            String args, SlaveNode primarySlave,
                                            int segIndex, int maxRetries) {
        List<SlaveNode> tried = new ArrayList<>();
        SlaveNode currentSlave = primarySlave;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            tried.add(currentSlave);
            SlaveConnection conn = slaveManager.getConnection(currentSlave.getName());

            if (conn == null || !conn.isConnected()) {
                logger.error("SLAVE_UNAVAILABLE",
                        currentSlave.getName() + " unavailable for segment " + segIndex +
                                " (attempt " + attempt + ")",
                        task.getSubmittedByUserId(), task.getSubmittedByUsername());
            } else {
                try {
                    // Build a sub-task for this segment
                    Task segTask = buildSegmentTask(task, taskJarPath, mainClass, args, segIndex);
                    slaveManager.markSlaveActive(currentSlave.getName(),
                            task.getId(), task.getTitle() + " [seg " + segIndex + "]");

                    logger.info("SEGMENT_DISPATCH",
                            "Sending segment " + segIndex + " to " + currentSlave.getName() +
                                    " with args: [" + args + "]",
                            task.getSubmittedByUserId(), task.getSubmittedByUsername());

                    NetworkMessage result = conn.sendTask(segTask);
                    slaveManager.markSlaveIdle(currentSlave.getName(), result.isSuccess());

                    if (result.isSuccess()) {
                        String output = (String) result.getPayload();
                        return output != null ? output : "(no output)";
                    }

                    logger.error("SEGMENT_FAILED",
                            "Segment " + segIndex + " failed on " + currentSlave.getName() +
                                    ": " + result.getMessage() + " (attempt " + attempt + ")",
                            task.getSubmittedByUserId(), task.getSubmittedByUsername());

                } catch (Exception e) {
                    slaveManager.markSlaveIdle(currentSlave.getName(), false);
                    logger.error("SEGMENT_ERROR",
                            "Error on segment " + segIndex + " at " + currentSlave.getName() +
                                    ": " + e.getMessage(),
                            task.getSubmittedByUserId(), task.getSubmittedByUsername());
                }
            }

            // Try another Slave for next attempt
            if (attempt < maxRetries) {
                SlaveNode next = getAlternativeSlave(tried);
                if (next != null) {
                    currentSlave = next;
                } else {
                    logger.error("NO_ALTERNATIVE_SLAVE",
                            "No alternative Slave available for segment " + segIndex,
                            task.getSubmittedByUserId(), task.getSubmittedByUsername());
                }
            }
        }

        throw new RuntimeException("Segment " + segIndex + " failed after " + maxRetries + " attempts.");
    }

    private SlaveNode getAlternativeSlave(List<SlaveNode> excluded) {
        List<SlaveNode> all = slaveManager.getAllSlaves();
        for (SlaveNode s : all) {
            if (s.getStatus() != SlaveNode.Status.OFFLINE &&
                    excluded.stream().noneMatch(e -> e.getName().equals(s.getName()))) {
                return s;
            }
        }
        return null;
    }

    /**
     * Builds a lightweight Task object representing one segment for the Slave.
     * Contains the JAR data and the segment arguments.
     */
    private Task buildSegmentTask(Task parent, Path taskJarPath, String mainClass,
                                   String args, int segIndex) throws IOException {
        byte[] jarBytes = Files.readAllBytes(taskJarPath);
        Task seg = new Task.Builder()
                .id(parent.getId() + "_seg" + segIndex)
                .title(parent.getTitle() + " [segment " + segIndex + "]")
                .jarFileName(parent.getJarFileName())
                .jarData(jarBytes)
                .submittedByUserId(parent.getSubmittedByUserId())
                .submittedByUsername(parent.getSubmittedByUsername())
                .priority(parent.getPriority())
                .build();

        // Store segment args in description field for Slave to read
        seg.setErrorMessage(mainClass + "|" + args); // reused as a transport field
        return seg;
    }

    /**
     * Merges per-Slave results into one labeled output string.
     */
    private String mergeResults(List<String> slaveNames, List<String> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DistriCompute V2 — Distributed Result ===\n\n");
        for (int i = 0; i < slaveNames.size(); i++) {
            sb.append("[").append(slaveNames.get(i)).append("]\n");
            sb.append(i < results.size() ? results.get(i) : "No result");
            sb.append("\n\n");
        }
        return sb.toString();
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

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                    .map(Path::toFile).forEach(File::delete);
        }
    }
}
