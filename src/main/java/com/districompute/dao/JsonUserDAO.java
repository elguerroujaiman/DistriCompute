package com.districompute.dao;

import com.districompute.common.models.User;
import com.districompute.common.utils.JsonStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON file-based implementation of UserDAO.
 * Persists users to data/users.json on disk.
 * Thread-safe: all read/write operations are synchronized.
 */
public class JsonUserDAO implements UserDAO {

    private static final String FILE_KEY = "users";
    private final JsonStorage storage;
    private final Object lock = new Object();

    public JsonUserDAO(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public void save(User user) {
        synchronized (lock) {
            Map<String, User> users = loadAll();
            users.put(user.getId(), user);
            storage.save(FILE_KEY, users);
        }
    }

    @Override
    public void update(User user) {
        save(user);
    }

    @Override
    public void delete(String id) {
        synchronized (lock) {
            Map<String, User> users = loadAll();
            users.remove(id);
            storage.save(FILE_KEY, users);
        }
    }

    @Override
    public Optional<User> findById(String id) {
        synchronized (lock) {
            return Optional.ofNullable(loadAll().get(id));
        }
    }

    @Override
    public List<User> findAll() {
        synchronized (lock) {
            return new ArrayList<>(loadAll().values());
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        synchronized (lock) {
            return loadAll().values().stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(username))
                    .findFirst();
        }
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @SuppressWarnings("unchecked")
    private Map<String, User> loadAll() {
        Map<String, User> result = storage.load(FILE_KEY, Map.class);
        if (result == null) return new LinkedHashMap<>();
        Map<String, User> typed = new LinkedHashMap<>();
        storage.loadTyped(FILE_KEY, typed, User.class);
        return typed;
    }
}
