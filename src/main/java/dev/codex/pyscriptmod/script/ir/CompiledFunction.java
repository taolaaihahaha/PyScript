package dev.codex.pyscriptmod.script.ir;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record CompiledFunction(String name, List<String> parameters, List<Instruction> instructions, boolean topLevel) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
