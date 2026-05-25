package dev.codex.pyscriptmod;

import dev.codex.pyscriptmod.script.io.ScriptModuleLoader;
import dev.codex.pyscriptmod.script.runtime.ScriptRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PyScriptMod implements ModInitializer {
    public static final String MOD_ID = "pyscriptmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PyScriptMod instance;

    private ScriptRuntime runtime;
    private Path scriptRoot;
    private PyScriptSettings settings;

    public static PyScriptMod instance() {
        return instance;
    }

    public ScriptRuntime runtime() {
        return runtime;
    }

    public Path scriptRoot() {
        return scriptRoot;
    }

    public PyScriptSettings settings() {
        return settings;
    }

    @Override
    public void onInitialize() {
        instance = this;
        this.scriptRoot = FabricLoader.getInstance().getGameDir().resolve("huh");
        ensureScriptRoot();
        this.settings = new PyScriptSettings(scriptRoot);
        this.runtime = new ScriptRuntime(new ScriptModuleLoader(scriptRoot), settings::isBlocked);

        CommandRegistrationCallback.EVENT.register(PyScriptCommand::register);
        ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick());
        LOGGER.info("PyScript Mod initialized. Script root: {}, sandbox={}", scriptRoot, settings.sandbox());
    }

    private void ensureScriptRoot() {
        try {
            Files.createDirectories(scriptRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize script directory", e);
        }
    }
}
