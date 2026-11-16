package com.mydog.connection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mydog.util.JsonEnumAdapterFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** CRUD for saved connection profiles in ~/.mydog/connections.json (atomic write). */
public class ConnectionStore {

    private static final Type LIST_TYPE = new TypeToken<List<ConnectionProfile>>() { }.getType();

    private final Path file;
    private final Gson gson;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();

    public ConnectionStore(Path file) {
        this.file = file;
        this.gson = new GsonBuilder()
                .registerTypeAdapterFactory(new JsonEnumAdapterFactory())
                .setPrettyPrinting()
                .create();
        load();
    }

    private synchronized void load() {
        profiles.clear();
        if (Files.exists(file)) {
            try {
                List<ConnectionProfile> loaded = gson.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) loaded.forEach(p -> profiles.put(p.id(), p));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
            }
        }
    }

    public synchronized List<ConnectionProfile> all() {
        return new ArrayList<>(profiles.values());
    }

    public synchronized Optional<ConnectionProfile> find(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public synchronized void save(ConnectionProfile profile) {
        profiles.put(profile.id(), profile);
        persist();
    }

    public synchronized boolean delete(String id) {
        boolean removed = profiles.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(new ArrayList<>(profiles.values())), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
