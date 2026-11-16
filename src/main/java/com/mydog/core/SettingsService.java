package com.mydog.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** App settings in ~/.mydog/settings.json. Generic key-value with typed getters. */
public class SettingsService {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public SettingsService(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                Map<String, Object> loaded = gson.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
                if (loaded != null) values.putAll(loaded);
            } catch (IOException | RuntimeException ignored) {
                // corrupt settings -> start fresh
            }
        }
    }

    public String get(String key, String def) {
        Object v = values.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public void put(String key, Object value) {
        values.put(key, value);
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(values), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
