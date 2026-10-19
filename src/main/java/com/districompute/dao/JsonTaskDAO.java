package com.districompute.dao;

import com.districompute.common.models.Task;
import com.districompute.common.models.Task.Priority;
import com.districompute.common.models.Task.Status;
import com.districompute.common.utils.JsonStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON file-based implementation of TaskDAO.
 * Uses a Map&lt;String, Task&gt; keyed by task ID, sorted by priority + submission time.
 * Uses a PriorityQueue internally for queue-order access.
 *
 * Thread-safe: all read/write operations are synchronized to prevent
 * concurrent file corruption when multiple tasks are processed in parallel.
 */
public class JsonTaskDAO implements TaskDAO {

    private static final String FILE_KEY = "tasks";
    private final JsonStorage storage;
    private final Object lock = new Object();

    public JsonTaskDAO(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public void save(Task task) {
        synchronized (lock) {
            Map<String, Task> tasks = loadAllMap();
            tasks.put(task.getId(), task);
            storage.save(FILE_KEY, new ArrayList<>(tasks.values()));
        }
    }

    @Override
    public void update(Task task) {
        save(task);
    }

    @Override
    public void delete(String id) {
        synchronized (lock) {
            Map<String, Task> tasks = loadAllMap();
            tasks.remove(id);
            storage.save(FILE_KEY, new ArrayList<>(tasks.values()));
        }
    }

    @Override
    public Optional<Task> findById(String id) {
        synchronized (lock) {
            return Optional.ofNullable(loadAllMap().get(id));
        }
    }

    @Override
    public List<Task> findAll() {
        synchronized (lock) {
            return storage.loadList(FILE_KEY, Task.class);
        }
    }

    @Override
    public List<Task> findByUserId(String userId) {
        return findAll().stream()
                .filter(t -> userId.equals(t.getSubmittedByUserId()))
                .sorted(Comparator.comparing(Task::getSubmittedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByAssignedUserId(String userId) {
        return findAll().stream()
                .filter(t -> userId.equals(t.getAssignedToUserId()))
                .sorted(Comparator.comparing(Task::getSubmittedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByStatus(Status status) {
        return findAll().stream()
                .filter(t -> t.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByPriority(Priority priority) {
        return findAll().stream()
                .filter(t -> t.getPriority() == priority)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByBatchId(String batchId) {
        return findAll().stream()
                .filter(t -> batchId.equals(t.getBatchId()))
                .sorted(Comparator.comparing(Task::getSubmittedAt))
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findPending() {
        PriorityQueue<Task> pq = new PriorityQueue<>(
                Comparator.comparingInt((Task t) -> priorityOrder(t.getPriority()))
                        .thenComparing(Task::getSubmittedAt)
        );

        findAll().stream()
                .filter(t -> t.getStatus() == Status.PENDING || t.getStatus() == Status.RECEIVED)
                .forEach(pq::add);

        List<Task> ordered = new ArrayList<>();
        while (!pq.isEmpty()) ordered.add(pq.poll());
        return ordered;
    }

    private int priorityOrder(Priority p) {
        return switch (p) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    private Map<String, Task> loadAllMap() {
        List<Task> list = storage.loadList(FILE_KEY, Task.class);
        Map<String, Task> map = new LinkedHashMap<>();
        for (Task t : list) map.put(t.getId(), t);
        return map;
    }
}
