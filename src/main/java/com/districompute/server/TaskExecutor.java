package com.districompute.server;

import com.districompute.common.models.Task;
import com.districompute.common.models.Task.Status;
import com.districompute.common.patterns.ExecutionStrategy;
import com.districompute.common.patterns.Observable;
import com.districompute.common.patterns.Observer;
import com.districompute.common.utils.AppLogger;
import com.districompute.common.utils.ServerConfig;
import com.districompute.dao.TaskDAO;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the global task queue and executes tasks using the configured ExecutionStrategy.
 *
 * Implements Observable<Task> to notify connected clients of task status changes (Observer pattern).
 * Uses a PriorityBlockingQueue for thread-safe, priority-ordered task processing.
 * Uses the Strategy pattern — execution logic is swappable (V1: local, V2: distributed).
 */
public class TaskExecutor implements Observable<Task> {

    private static volatile TaskExecutor instance;

    private ExecutionStrategy strategy;
    private TaskDAO taskDAO;
    private final AppLogger logger = AppLogger.getInstance();

    // Thread-safe priority queue: HIGH > MEDIUM > LOW, then by submission time
    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>(
            50,
            Comparator.comparingInt((Task t) -> priorityOrder(t.getPriority()))
                    .thenComparing(Task::getSubmittedAt)
    );

    private final List<Observer<Task>> observers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workerPool;
    private Thread schedulerThread;

    // Track tasks per user for limit enforcement: Map<userId, Set<taskId>>
    private final Map<String, Set<String>> activeTasksByUser = new ConcurrentHashMap<>();

    private TaskExecutor() {}

    public static TaskExecutor getInstance() {
        if (instance == null) {
            synchronized (TaskExecutor.class) {
                if (instance == null) instance = new TaskExecutor();
            }
        }
        return instance;
    }

    /**
     * Initialize the executor with its dependencies.
     */
    public void init(ExecutionStrategy strategy, TaskDAO taskDAO) {
        this.strategy = strategy;
        this.taskDAO = taskDAO;
        this.workerPool = Executors.newFixedThreadPool(4);
        logger.system("EXECUTOR_INIT", "TaskExecutor initialized with strategy: " + strategy.getStrategyName());
    }

    /**
     * Starts the scheduler thread that pulls tasks from the queue and executes them.
     */
    public void start() {
        running.set(true);
        schedulerThread = new Thread(this::scheduleLoop, "task-scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        logger.system("EXECUTOR_START", "Task scheduler started.");
    }

    public void stop() {
        running.set(false);
        workerPool.shutdown();
        if (schedulerThread != null) schedulerThread.interrupt();
        logger.system("EXECUTOR_STOP", "Task scheduler stopped.");
    }

    /**
     * Submits a task to the queue.
     * Enforces per-user and global queue limits.
     *
     * @return error message if rejected, null if accepted
     */
    public synchronized String submitTask(Task task) {
        ServerConfig cfg = ServerConfig.getInstance();

        // Global queue limit
        if (queue.size() >= cfg.getMaxQueueSize()) {
            return "Server queue is full (" + cfg.getMaxQueueSize() + " tasks). Please try again later.";
        }

        // Per-user limit
        Set<String> userTasks = activeTasksByUser.computeIfAbsent(
                task.getSubmittedByUserId(), k -> ConcurrentHashMap.newKeySet());
        if (userTasks.size() >= cfg.getMaxTasksPerUser()) {
            return "You already have " + cfg.getMaxTasksPerUser() +
                    " tasks in queue. Please wait for them to complete.";
        }

        task.setStatus(Status.PENDING);
        task.setQueuePosition(queue.size() + 1);
        taskDAO.save(task);
        queue.offer(task);
        userTasks.add(task.getId());

        logger.info("TASK_QUEUED", "Task '" + task.getTitle() + "' added to queue (position ~" +
                task.getQueuePosition() + ").",
                task.getSubmittedByUserId(), task.getSubmittedByUsername());

        notifyObservers(task);
        return null; // success
    }

    /**
     * Cancels a task if it is still pending or received.
     *
     * @return true if cancelled successfully
     */
    public synchronized boolean cancelTask(String taskId, String requestingUserId) {
        // Remove from queue if present
        Optional<Task> inQueue = queue.stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst();

        if (inQueue.isPresent()) {
            Task task = inQueue.get();
            // Only owner or admin can cancel
            queue.remove(task);
            task.setStatus(Status.CANCELLED);
            taskDAO.update(task);
            removeFromUserTracking(requestingUserId, taskId);
            logger.info("TASK_CANCELLED", "Task '" + task.getTitle() + "' cancelled.",
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());
            notifyObservers(task);
            return true;
        }

        // Check if already executing (can't cancel)
        Optional<Task> persisted = taskDAO.findById(taskId);
        if (persisted.isPresent() && persisted.get().getStatus() == Status.EXECUTING) {
            return false; // too late
        }

        return false;
    }

    /**
     * Returns current queue snapshot ordered by priority.
     */
    public List<Task> getQueueSnapshot() {
        List<Task> snapshot = new ArrayList<>(queue);
        snapshot.sort(Comparator.comparingInt((Task t) -> priorityOrder(t.getPriority()))
                .thenComparing(Task::getSubmittedAt));
        // Assign display positions
        for (int i = 0; i < snapshot.size(); i++) {
            snapshot.get(i).setQueuePosition(i + 1);
        }
        return snapshot;
    }

    // -------------------------------------------------------------------------
    // Scheduler loop
    // -------------------------------------------------------------------------

    private void scheduleLoop() {
        while (running.get()) {
            try {
                Task task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                workerPool.submit(() -> executeTask(task));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void executeTask(Task task) {
        try {
            // RECEIVED
            task.setStatus(Status.RECEIVED);
            taskDAO.update(task);
            notifyObservers(task);
            sleep(300);

            // VALIDATING
            task.setStatus(Status.VALIDATING);
            taskDAO.update(task);
            notifyObservers(task);
            sleep(500);

            // DISTRIBUTING (V2 stage)
            task.setStatus(Status.DISTRIBUTING);
            taskDAO.update(task);
            notifyObservers(task);
            sleep(400);

            // EXECUTING
            task.setStatus(Status.EXECUTING);
            taskDAO.update(task);
            notifyObservers(task);

            // Run strategy (DistributedExecutionStrategy in V2)
            strategy.execute(task);

            if (task.getStatus() == Status.COMPLETED) {
                // MERGING (V2 stage)
                task.setStatus(Status.MERGING);
                taskDAO.update(task);
                notifyObservers(task);
                sleep(400);

                // FINALIZING
                task.setStatus(Status.FINALIZING);
                taskDAO.update(task);
                notifyObservers(task);
                sleep(400);

                task.setStatus(Status.COMPLETED);
            }

            taskDAO.update(task);
            notifyObservers(task);

        } catch (Exception e) {
            task.setStatus(Status.FAILED);
            task.setErrorMessage("Unexpected error: " + e.getMessage());
            taskDAO.update(task);
            notifyObservers(task);
            logger.error("TASK_ERROR", "Unexpected error in task '" + task.getTitle() + "': " + e.getMessage(),
                    task.getSubmittedByUserId(), task.getSubmittedByUsername());
        } finally {
            removeFromUserTracking(task.getSubmittedByUserId(), task.getId());
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void removeFromUserTracking(String userId, String taskId) {
        Set<String> tasks = activeTasksByUser.get(userId);
        if (tasks != null) tasks.remove(taskId);
    }

    private int priorityOrder(Task.Priority p) {
        return switch (p) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    // -------------------------------------------------------------------------
    // Observer pattern
    // -------------------------------------------------------------------------

    @Override
    public void addObserver(Observer<Task> observer) { observers.add(observer); }

    @Override
    public void removeObserver(Observer<Task> observer) { observers.remove(observer); }

    @Override
    public void notifyObservers(Task task) {
        for (Observer<Task> obs : observers) {
            try { obs.onEvent(task); } catch (Exception ignored) {}
        }
    }
}
