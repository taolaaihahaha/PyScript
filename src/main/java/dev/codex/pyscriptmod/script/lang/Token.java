package dev.codex.pyscriptmod.script.lang;

public record Token(TokenType type, String lexeme, Object literal, int line, int column) {
}
