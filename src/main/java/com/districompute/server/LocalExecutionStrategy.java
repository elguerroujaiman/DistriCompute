package com.districompute.server;

import com.districompute.common.models.Task;
import com.districompute.common.models.Task.Status;
import com.districompute.common.patterns.ExecutionStrategy;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * V1 ExecutionStrategy — the Master executes the task directly and locally.
 *
 * In V2, this will be replaced by DistributedExecutionStrategy
 * where the Master distributes work to Slave nodes.
 *
 * The isolation between strategies (via the ExecutionStrategy interface)
 * makes this swap seamless without touching TaskExecutor or any other component.
 */
public class LocalExecutionStrategy implements ExecutionStrategy {

    private static final AppLogger logger = AppLogger.getInstance();

    @Override
    public String getStrategyName() {
        return "Local Execution (V1 - Master Only)";
    }

    @Override
    public void execute(Task task) {
        long startTime = System.currentTimeMillis();
        task.setStartedAt(java.time.LocalDateTime.now());

        Path tempDir = null;
        try {
            // Create temp directory for this task's JAR
            tempDir = Files.createTempDirectory("districompute_task_" + task.getId());
            Path jarPath = tempDir.resolve("task.jar");

            // Write JAR data to temp file
            if (task.getJarData() == null || task.getJarData().length == 0) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("No JAR data provided for task.");
                return;
            }
            Files.write(jarPath, task.getJarData());

            // Extract main class from JAR manifest
            String mainClass = getMainClass(jarPath);
            if (mainClass == null || mainClass.isBlank()) {
                task.setStatus(Status.FAILED);
                task.setErrorMessage("JAR manifest does not specify a Main-Class.");
                return;
            }

            // Execute JAR in a separate process with timeout
            String result = runJar(jarPath, mainClass, task);
            task.setResultText(result);
            task.setStatus(Status.COMPLETED);

            logger.success("TASK_EXECUTED", "Task '" + task.getTitle() + "' completed successfully.",
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());

        } catch (TimeoutException e) {
            task.setStatus(Status.FAILED);
            task.setErrorMessage("Task timed out after " +
                    ServerConfig.getInstance().getExecutionTimeoutSeconds() + " seconds.");
            logger.error("TASK_TIMEOUT", "Task '" + task.getTitle() + "' timed out.",
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());

        } catch (Exception e) {
            task.setStatus(Status.FAILED);
            task.setErrorMessage("Execution error: " + e.getMessage());
            logger.error("TASK_FAILED", "Task '" + task.getTitle() + "' failed: " + e.getMessage(),
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());

        } finally {
            task.setCompletedAt(java.time.LocalDateTime.now());
            task.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            // Clean up temp directory
            if (tempDir != null) {
                try { deleteDirectory(tempDir); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Reads the Main-Class from the JAR manifest.
     */
    private String getMainClass(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return null;
            return manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Runs the JAR in a separate JVM process and captures stdout.
     */
    private String runJar(Path jarPath, String mainClass, Task task)
            throws IOException, InterruptedException, TimeoutException {

        int timeout = ServerConfig.getInstance().getExecutionTimeoutSeconds();

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", jarPath.toAbsolutePath().toString(), mainClass
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Read output in separate thread
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("Process exceeded timeout of " + timeout + " seconds.");
        }

        outputReader.join(2000);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String result = output.toString();
            throw new RuntimeException("Process exited with code " + exitCode +
                    (result.isEmpty() ? "" : ". Output: " + result.trim()));
        }

        return output.toString().isEmpty()
                ? "(Task completed with no output)"
                : output.toString();
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
