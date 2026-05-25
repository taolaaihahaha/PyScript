package dev.codex.pyscriptmod.script.ir;

import java.io.Serial;
import java.io.Serializable;

public record Instruction(OpCode opCode, Object operand) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static Instruction of(OpCode opCode) {
        return new Instruction(opCode, null);
    }
}
