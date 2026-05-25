package dev.codex.pyscriptmod;

import dev.codex.pyscriptmod.bridge.FabricMinecraftBridge;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PyScriptCommand {
    private static final String SOURCE_EXT = ".ppy";
    private static final String COMPILED_EXT = ".cpy";

    private PyScriptCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
                                net.minecraft.command.CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("pyscript")
                .requires(source -> true)
                .then(literal("create")
                        .then(argument("name", string())
                                .executes(ctx -> create(ctx.getSource(), getString(ctx, "name")))))
                .then(literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(literal("load")
                        .then(argument("file", string())
                                .executes(ctx -> load(ctx.getSource(), getString(ctx, "file"), Map.of()))
                                .then(argument("inputs", greedyString())
                                        .executes(ctx -> load(ctx.getSource(), getString(ctx, "file"),
                                                parseInputs(getString(ctx, "inputs")))))))
                .then(literal("run")
                        .then(argument("module", word())
                                .executes(ctx -> load(ctx.getSource(), getString(ctx, "module"), Map.of()))
                                .then(argument("inputs", greedyString())
                                        .executes(ctx -> load(ctx.getSource(), getString(ctx, "module"),
                                                parseInputs(getString(ctx, "inputs")))))))
                .then(literal("reload")
                        .requires(source -> true)
                        .executes(ctx -> {
                            PyScriptMod.instance().runtime().reloadScripts();
                            ctx.getSource().sendFeedback(() -> Text.literal("PyScript cache reloaded"), false);
                            return 1;
                        }))
                .then(literal("settings")
                        .then(literal("sandbox")
                                .executes(ctx -> {
                                    Map<String, Boolean> flags = PyScriptMod.instance().settings().allFlags();
                                    ctx.getSource().sendFeedback(() -> Text.literal("sandbox flags:"), false);
                                    flags.forEach((key, blocked) -> ctx.getSource()
                                            .sendFeedback(() -> Text.literal(" - " + key + "=" + blocked), false));
                                    return 1;
                                })
                                .then(argument("key", word())
                                        .then(argument("enabled", bool())
                                                .executes(ctx -> {
                                                    String key = getString(ctx, "key");
                                                    boolean enabled = getBool(ctx, "enabled");
                                                    try {
                                                        PyScriptMod.instance().settings().setBlocked(key, enabled);
                                                    } catch (IllegalArgumentException e) {
                                                        ctx.getSource().sendError(Text.literal(e.getMessage()));
                                                        return 0;
                                                    }
                                                    ctx.getSource().sendFeedback(() -> Text.literal("sandbox." + key + "=" + enabled), false);
                                                    return 1;
                                                })))))
                .then(literal("stop")
                        .then(argument("module", string())
                                .executes(ctx -> {
                                    String module = getString(ctx, "module");
                                    dev.codex.pyscriptmod.script.runtime.ScriptRuntime.StopSummary summary =
                                            PyScriptMod.instance().runtime().stopModule(module);
                                    ctx.getSource().sendFeedback(() -> Text.literal(
                                            "Stopped module '" + module + "': threads=" + summary.threadsStopped()
                                                    + ", handlers=" + summary.eventHandlersRemoved()), false);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            dev.codex.pyscriptmod.script.runtime.ScriptRuntime.StopSummary summary =
                                    PyScriptMod.instance().runtime().stopAll();
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                    "Stopped scripts: threads=" + summary.threadsStopped()
                                            + ", handlers=" + summary.eventHandlersRemoved()), false);
                            return 1;
                        })));
    }

    private static int create(ServerCommandSource source, String rawName) {
        Path scriptRoot = PyScriptMod.instance().scriptRoot();
        Path target;
        try {
            target = resolveScriptPath(scriptRoot, rawName, false, false);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal(e.getMessage()));
            return 0;
        }

        if (Files.exists(target)) {
            source.sendError(Text.literal("File already exists: " + target.getFileName()));
            return 0;
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, """
                    # PyScript file
                    say("hello from " + get_name(player))
                    """);
            source.sendFeedback(() -> Text.literal("Created: " + target), false);
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("Failed to create file: " + e.getMessage()));
            return 0;
        }
    }

    private static int list(ServerCommandSource source) {
        Path scriptRoot = PyScriptMod.instance().scriptRoot();
        try {
            Files.createDirectories(scriptRoot);
            List<Path> files;
            try (Stream<Path> stream = Files.walk(scriptRoot)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(SOURCE_EXT) || name.endsWith(COMPILED_EXT);
                        })
                        .sorted(Comparator.comparing(path -> scriptRoot.relativize(path).toString()))
                        .collect(Collectors.toList());
            }
            if (files.isEmpty()) {
                source.sendFeedback(() -> Text.literal("No .ppy/.cpy files in: " + scriptRoot), false);
                return 1;
            }
            source.sendFeedback(() -> Text.literal("Scripts in " + scriptRoot + ":"), false);
            for (Path file : files) {
                String rel = scriptRoot.relativize(file).toString();
                source.sendFeedback(() -> Text.literal(" - " + rel), false);
            }
            return 1;
        } catch (IOException e) {
            source.sendError(Text.literal("Failed to list files: " + e.getMessage()));
            return 0;
        }
    }

    private static int load(ServerCommandSource source, String fileArg, Map<String, Object> inputs) {
        if (source.getEntity() == null || !(source.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity)) {
            source.sendError(Text.literal("PyScript runner must be a player. Console and command blocks are rejected in the MVP."));
            return 0;
        }

        Path scriptRoot = PyScriptMod.instance().scriptRoot();
        Path target;
        try {
            target = resolveScriptPath(scriptRoot, fileArg, true, true);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal(e.getMessage()));
            return 0;
        }

        try {
            source.sendFeedback(() -> Text.literal("Loaded script: " + target), false);
            PyScriptMod.instance().runtime().runPath(target, new FabricMinecraftBridge(source), inputs);
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("PyScript error: " + e.getMessage()));
            return 0;
        }
    }

    private static Path resolveScriptPath(Path scriptRoot, String rawInput, boolean mustExist, boolean allowAbsolute) {
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("File name/path must not be empty");
        }

        Path rawPath;
        try {
            rawPath = Path.of(trimmed);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path: " + trimmed);
        }

        Path resolved;
        if (rawPath.isAbsolute()) {
            if (!allowAbsolute) {
                throw new IllegalArgumentException("Create only supports names under .minecraft/huh");
            }
            resolved = rawPath.normalize();
        } else {
            resolved = scriptRoot.resolve(rawPath).normalize();
            if (!resolved.startsWith(scriptRoot.normalize())) {
                throw new IllegalArgumentException("Relative path escapes .minecraft/huh");
            }
        }

        if (!resolved.getFileName().toString().contains(".")) {
            if (mustExist) {
                Path compiled = resolved.resolveSibling(resolved.getFileName() + COMPILED_EXT);
                if (Files.exists(compiled)) {
                    resolved = compiled;
                } else {
                    Path source = resolved.resolveSibling(resolved.getFileName() + SOURCE_EXT);
                    resolved = source;
                }
            } else {
                resolved = resolved.resolveSibling(resolved.getFileName() + SOURCE_EXT);
            }
        }

        if (mustExist && !Files.exists(resolved)) {
            throw new IllegalArgumentException("File not found: " + resolved);
        }

        return resolved.toAbsolutePath().normalize();
    }

    private static Map<String, Object> parseInputs(String raw) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                inputs.put(trimmed, "true");
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            inputs.put(key, value);
        }
        return inputs;
    }
}
