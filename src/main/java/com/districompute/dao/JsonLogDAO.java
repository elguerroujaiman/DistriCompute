package com.districompute.dao;

import com.districompute.common.models.LogEntry;
import com.districompute.common.utils.JsonStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON file-based implementation of LogDAO.
 * Thread-safe: all read/write operations are synchronized.
 */
public class JsonLogDAO implements LogDAO {

    private static final String FILE_KEY = "logs";
    private final JsonStorage storage;
    private final Object lock = new Object();

    public JsonLogDAO(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public void save(LogEntry entry) {
        synchronized (lock) {
            List<LogEntry> logs = findAllInternal();
            logs.add(entry);
            storage.saveList(FILE_KEY, logs);
        }
    }

    @Override
    public void update(LogEntry entry) {
        synchronized (lock) {
            List<LogEntry> logs = findAllInternal();
            logs.replaceAll(l -> l.getId().equals(entry.getId()) ? entry : l);
            storage.saveList(FILE_KEY, logs);
        }
    }

    @Override
    public void delete(String id) {
        synchronized (lock) {
            List<LogEntry> logs = findAllInternal();
            logs.removeIf(l -> l.getId().equals(id));
            storage.saveList(FILE_KEY, logs);
        }
    }

    @Override
    public Optional<LogEntry> findById(String id) {
        synchronized (lock) {
            return findAllInternal().stream().filter(l -> l.getId().equals(id)).findFirst();
        }
    }

    @Override
    public List<LogEntry> findAll() {
        synchronized (lock) {
            return findAllInternal();
        }
    }

    @Override
    public List<LogEntry> findByUserId(String userId) {
        return findAll().stream()
                .filter(l -> userId.equals(l.getUserId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<LogEntry> findRecent(int limit) {
        List<LogEntry> all = findAll();
        all.sort(Comparator.comparing(LogEntry::getTimestamp).reversed());
        return all.stream().limit(limit).collect(Collectors.toList());
    }

    private List<LogEntry> findAllInternal() {
        return storage.loadList(FILE_KEY, LogEntry.class);
    }
}
