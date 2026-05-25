package dev.codex.pyscriptmod.script.io;

import dev.codex.pyscriptmod.script.ir.CompiledModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class CompiledModuleIO {
    private static final int FORMAT_VERSION = 1;

    private CompiledModuleIO() {
    }

    static void write(Path output, CompiledModule module) {
        try {
            Files.createDirectories(output.getParent());
            try (OutputStream file = Files.newOutputStream(output);
                 GZIPOutputStream gzip = new GZIPOutputStream(file);
                 ObjectOutputStream objectOut = new ObjectOutputStream(gzip)) {
                objectOut.writeInt(FORMAT_VERSION);
                objectOut.writeObject(module);
                objectOut.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write compiled module: " + output, e);
        }
    }

    static CompiledModule read(Path input) {
        try (InputStream file = Files.newInputStream(input);
             GZIPInputStream gzip = new GZIPInputStream(file);
             ObjectInputStream objectIn = new ObjectInputStream(gzip)) {
            int version = objectIn.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported .cpy version " + version + " for " + input);
            }
            Object decoded = objectIn.readObject();
            if (!(decoded instanceof CompiledModule module)) {
                throw new IllegalStateException("Invalid .cpy payload: " + input);
            }
            return module;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to read compiled module: " + input, e);
        }
    }
}
