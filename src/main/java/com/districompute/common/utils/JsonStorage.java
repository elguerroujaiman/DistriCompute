package com.districompute.common.utils;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.time.LocalDate;

/**
 * Utility class for JSON-based file storage using Gson.
 * Handles serialization and deserialization of entities to/from JSON files.
 *
 * Used by all DAO implementations (DAO pattern).
 */
public class JsonStorage {

    private static final Logger LOGGER = Logger.getLogger(JsonStorage.class.getName());
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Path dataDir;
    private final Gson gson;

    public JsonStorage(String dataDirectory) {
        this.dataDir = Paths.get(dataDirectory);
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonSerializer<LocalDateTime>) (src, type, ctx) ->
                                new JsonPrimitive(src.format(DT_FORMAT)))
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonDeserializer<LocalDateTime>) (json, type, ctx) ->
                                LocalDateTime.parse(json.getAsString(), DT_FORMAT))
                .registerTypeAdapter(LocalDate.class,
                        (JsonSerializer<LocalDate>) (src, type, ctx) ->
                                new JsonPrimitive(src.toString()))
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, type, ctx) ->
                                LocalDate.parse(json.getAsString()))
                .serializeNulls()
                .create();

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            LOGGER.severe("Failed to create data directory: " + e.getMessage());
        }
    }

    /**
     * Saves an object to a JSON file named {key}.json
     */
    public <T> void save(String key, T data) {
        Path filePath = dataDir.resolve(key + ".json");
        try (Writer writer = Files.newBufferedWriter(filePath)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.severe("Failed to save " + key + ": " + e.getMessage());
        }
    }

    /**
     * Loads raw object from a JSON file.
     */
    public <T> T load(String key, Class<T> clazz) {
        Path filePath = dataDir.resolve(key + ".json");
        if (!Files.exists(filePath)) return null;
        try (Reader reader = Files.newBufferedReader(filePath)) {
            return gson.fromJson(reader, clazz);
        } catch (IOException e) {
            LOGGER.severe("Failed to load " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads a Map<String, T> from a JSON file into the provided map.
     */
    public <T> void loadTyped(String key, Map<String, T> resultMap, Class<T> valueClass) {
        Path filePath = dataDir.resolve(key + ".json");
        if (!Files.exists(filePath)) return;
        try (Reader reader = Files.newBufferedReader(filePath)) {
            JsonObject obj = gson.fromJson(reader, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                T value = gson.fromJson(entry.getValue(), valueClass);
                resultMap.put(entry.getKey(), value);
            }
        } catch (IOException e) {
            LOGGER.severe("Failed to loadTyped " + key + ": " + e.getMessage());
        }
    }

    /**
     * Loads a List<T> from a JSON file.
     */
    public <T> List<T> loadList(String key, Class<T> elementClass) {
        Path filePath = dataDir.resolve(key + ".json");
        if (!Files.exists(filePath)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(filePath)) {
            JsonArray arr = gson.fromJson(reader, JsonArray.class);
            if (arr == null) return new ArrayList<>();
            List<T> list = new ArrayList<>();
            for (JsonElement el : arr) {
                list.add(gson.fromJson(el, elementClass));
            }
            return list;
        } catch (IOException e) {
            LOGGER.severe("Failed to loadList " + key + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Saves a list to JSON file.
     */
    public <T> void saveList(String key, List<T> list) {
        save(key, list);
    }

    public Gson getGson() { return gson; }
    public Path getDataDir() { return dataDir; }
}
