package dev.codex.pyscriptmod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class PyScriptSettings {
    public static final String WRITE_FILE = "write_file";
    public static final String READ_FILE = "read_file";
    public static final String RUN_COMMANDS = "run_commands";
    public static final String COPY = "copy";
    public static final String PASTE = "paste";
    public static final String EDIT_CLIPBOARD = "edit_clipboard";
    private static final String LEGACY_SANDBOX_KEY = "sandbox";
    private static final List<String> ALL_KEYS = List.of(
            WRITE_FILE, READ_FILE, RUN_COMMANDS, COPY, PASTE, EDIT_CLIPBOARD
    );

    private final Path settingsFile;
    private final Map<String, Boolean> sandboxFlags = new LinkedHashMap<>();

    public PyScriptSettings(Path scriptRoot) {
        this.settingsFile = scriptRoot.resolve("settings.properties");
        load();
    }

    public boolean sandbox() {
        return sandboxFlags.values().stream().allMatch(Boolean::booleanValue);
    }

    public synchronized void setSandbox(boolean enabled) {
        for (String key : ALL_KEYS) {
            sandboxFlags.put(key, enabled);
        }
        save();
    }

    public synchronized boolean isBlocked(String key) {
        return sandboxFlags.getOrDefault(key, true);
    }

    public synchronized void setBlocked(String key, boolean blocked) {
        requireKey(key);
        sandboxFlags.put(key, blocked);
        save();
    }

    public synchronized Map<String, Boolean> allFlags() {
        return new LinkedHashMap<>(sandboxFlags);
    }

    private void requireKey(String key) {
        if (!ALL_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unknown sandbox key: " + key);
        }
    }

    private void load() {
        try {
            Files.createDirectories(settingsFile.getParent());
            if (!Files.exists(settingsFile)) {
                for (String key : ALL_KEYS) {
                    sandboxFlags.put(key, true);
                }
                save();
                return;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(settingsFile)) {
                props.load(in);
            }
            String legacy = props.getProperty(LEGACY_SANDBOX_KEY);
            boolean legacyValue = legacy == null || Boolean.parseBoolean(legacy);
            for (String key : ALL_KEYS) {
                String raw = props.getProperty("sandbox." + key);
                boolean blocked = raw == null ? legacyValue : Boolean.parseBoolean(raw);
                sandboxFlags.put(key, blocked);
            }
            save();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load settings: " + settingsFile, e);
        }
    }

    private synchronized void save() {
        Properties props = new Properties();
        props.setProperty(LEGACY_SANDBOX_KEY, Boolean.toString(sandbox()));
        for (String key : ALL_KEYS) {
            props.setProperty("sandbox." + key, Boolean.toString(sandboxFlags.getOrDefault(key, true)));
        }
        try (OutputStream out = Files.newOutputStream(settingsFile)) {
            props.store(out, "PyScript settings");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings: " + settingsFile, e);
        }
    }
}
