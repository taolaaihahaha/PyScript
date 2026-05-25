package dev.codex.pyscriptmod.script.ir;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

public record CompiledModule(String moduleName, CompiledFunction entryPoint, Map<String, CompiledFunction> functions) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
