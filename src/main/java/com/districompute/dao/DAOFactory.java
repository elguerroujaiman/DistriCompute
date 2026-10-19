package com.districompute.dao;

import com.districompute.common.utils.JsonStorage;

/**
 * Factory for creating DAO instances.
 * Implements the Factory Method pattern — callers get DAO objects
 * without knowing their concrete implementation (JSON, DB, etc.).
 *
 * In the future, this factory could return database-backed DAOs
 * without changing any other code.
 */
public class DAOFactory {

    public enum StorageType {
        JSON
    }

    private final StorageType storageType;
    private final JsonStorage jsonStorage;

    public DAOFactory(StorageType storageType, String dataDirectory) {
        this.storageType = storageType;
        this.jsonStorage = new JsonStorage(dataDirectory);
    }

    /**
     * Creates a UserDAO for the configured storage backend.
     */
    public UserDAO createUserDAO() {
        return switch (storageType) {
            case JSON -> new JsonUserDAO(jsonStorage);
        };
    }

    /**
     * Creates a TaskDAO for the configured storage backend.
     */
    public TaskDAO createTaskDAO() {
        return switch (storageType) {
            case JSON -> new JsonTaskDAO(jsonStorage);
        };
    }

    /**
     * Creates a LogDAO for the configured storage backend.
     */
    public LogDAO createLogDAO() {
        return switch (storageType) {
            case JSON -> new JsonLogDAO(jsonStorage);
        };
    }

    public JsonStorage getJsonStorage() { return jsonStorage; }
}
