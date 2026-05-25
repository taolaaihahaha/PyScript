package dev.codex.pyscriptmod.script.io;

import dev.codex.pyscriptmod.script.ir.CompiledModule;
import dev.codex.pyscriptmod.script.ir.Compiler;
import dev.codex.pyscriptmod.script.lang.Lexer;
import dev.codex.pyscriptmod.script.lang.Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptModuleLoader {
    public static final String SOURCE_EXTENSION = ".ppy";
    public static final String COMPILED_EXTENSION = ".cpy";

    private final Path scriptRoot;
    private final Lexer lexer = new Lexer();
    private final Compiler compiler = new Compiler();
    private final Map<String, CompiledModule> compiledModules = new ConcurrentHashMap<>();

    public ScriptModuleLoader(Path scriptRoot) {
        this.scriptRoot = scriptRoot;
    }

    public Path scriptRoot() {
        return scriptRoot;
    }

    public CompiledModule load(String moduleName) {
        return compiledModules.computeIfAbsent(moduleName, this::compileModule);
    }

    public CompiledModule load(Path scriptPath) {
        Path normalized = scriptPath.toAbsolutePath().normalize();
        String cacheKey = "@path:" + normalized;
        return compiledModules.computeIfAbsent(cacheKey, ignored -> compilePathModule(normalized));
    }

    public void invalidateAll() {
        compiledModules.clear();
    }

    public boolean exists(String moduleName) {
        Path source = resolveSource(moduleName);
        Path compiled = resolveCompiled(moduleName);
        return Files.exists(source) || Files.exists(compiled);
    }

    private CompiledModule compileModule(String moduleName) {
        Path sourcePath = resolveSource(moduleName);
        Path compiledPath = resolveCompiled(moduleName);
        boolean sourceExists = Files.exists(sourcePath);
        boolean compiledExists = Files.exists(compiledPath);

        if (!sourceExists && !compiledExists) {
            throw new IllegalStateException("Script module not found: " + moduleName + " (" + sourcePath + ")");
        }

        if (compiledExists && !isSourceNewer(sourcePath, compiledPath)) {
            return CompiledModuleIO.read(compiledPath);
        }

        if (!sourceExists) {
            return CompiledModuleIO.read(compiledPath);
        }

        CompiledModule compiled = compileFromSourcePath(moduleName, sourcePath);
        CompiledModuleIO.write(compiledPath, compiled);
        return compiled;
    }

    private CompiledModule compilePathModule(Path modulePath) {
        if (!Files.exists(modulePath)) {
            throw new IllegalStateException("Script file not found: " + modulePath);
        }
        String fileName = modulePath.getFileName().toString();
        String moduleName = baseName(fileName);
        if (fileName.endsWith(COMPILED_EXTENSION)) {
            return CompiledModuleIO.read(modulePath);
        }
        if (fileName.endsWith(SOURCE_EXTENSION)) {
            CompiledModule compiled = compileFromSourcePath(moduleName, modulePath);
            CompiledModuleIO.write(withExtension(modulePath, COMPILED_EXTENSION), compiled);
            return compiled;
        }

        Path maybeCompiled = withExtension(modulePath, COMPILED_EXTENSION);
        if (Files.exists(maybeCompiled)) {
            return CompiledModuleIO.read(maybeCompiled);
        }
        Path maybeSource = withExtension(modulePath, SOURCE_EXTENSION);
        if (Files.exists(maybeSource)) {
            CompiledModule compiled = compileFromSourcePath(moduleName, maybeSource);
            CompiledModuleIO.write(withExtension(maybeSource, COMPILED_EXTENSION), compiled);
            return compiled;
        }

        throw new IllegalStateException("Unsupported script extension for " + modulePath + ". Use .ppy or .cpy");
    }

    private CompiledModule compileFromSourcePath(String moduleName, Path modulePath) {
        try {
            String source = Files.readString(modulePath);
            return compiler.compile(moduleName, new Parser(lexer.lex(source)).parse());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read module " + moduleName, e);
        }
    }

    private Path resolveSource(String moduleName) {
        String relative = moduleName.replace('.', '/');
        return scriptRoot.resolve(relative + SOURCE_EXTENSION);
    }

    private Path resolveCompiled(String moduleName) {
        String relative = moduleName.replace('.', '/');
        return scriptRoot.resolve(relative + COMPILED_EXTENSION);
    }

    private boolean isSourceNewer(Path sourcePath, Path compiledPath) {
        if (!Files.exists(sourcePath)) {
            return false;
        }
        if (!Files.exists(compiledPath)) {
            return true;
        }
        try {
            FileTime sourceTime = Files.getLastModifiedTime(sourcePath);
            FileTime compiledTime = Files.getLastModifiedTime(compiledPath);
            return sourceTime.compareTo(compiledTime) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    private String baseName(String fileName) {
        if (fileName.endsWith(SOURCE_EXTENSION)) {
            return fileName.substring(0, fileName.length() - SOURCE_EXTENSION.length());
        }
        if (fileName.endsWith(COMPILED_EXTENSION)) {
            return fileName.substring(0, fileName.length() - COMPILED_EXTENSION.length());
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private Path withExtension(Path path, String extension) {
        String fileName = path.getFileName().toString();
        String base = baseName(fileName);
        Path parent = path.getParent();
        if (parent == null) {
            return Path.of(base + extension);
        }
        return parent.resolve(base + extension);
    }
}
