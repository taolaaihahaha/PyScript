package dev.codex.pyscriptmod.script.lang;

import java.util.List;

public final class Ast {
    private Ast() {
    }

    public record Module(List<Stmt> statements) {
    }

    public record Decorator(String name, List<Expr> arguments) {
    }

    public sealed interface Stmt permits Assign, ExprStmt, IfStmt, WhileStmt, ForStmt, FunctionDef, ReturnStmt, BreakStmt, ContinueStmt, ImportStmt {
    }

    public sealed interface Expr permits LiteralExpr, NameExpr, BinaryExpr, UnaryExpr, CallExpr, ListExpr, DictExpr, IndexExpr, AttributeExpr {
    }

    public record Assign(String name, Expr value) implements Stmt {
    }

    public record ExprStmt(Expr expression) implements Stmt {
    }

    public record IfStmt(List<Branch> branches, List<Stmt> elseBranch) implements Stmt {
        public record Branch(Expr condition, List<Stmt> body) {
        }
    }

    public record WhileStmt(Expr condition, List<Stmt> body) implements Stmt {
    }

    public record ForStmt(String variable, Expr iterable, List<Stmt> body) implements Stmt {
    }

    public record FunctionDef(String name, List<String> parameters, List<Decorator> decorators, List<Stmt> body) implements Stmt {
    }

    public record ReturnStmt(Expr value) implements Stmt {
    }

    public record BreakStmt() implements Stmt {
    }

    public record ContinueStmt() implements Stmt {
    }

    public record ImportStmt(String moduleName) implements Stmt {
    }

    public record LiteralExpr(Object value) implements Expr {
    }

    public record NameExpr(String name) implements Expr {
    }

    public record BinaryExpr(Expr left, String operator, Expr right) implements Expr {
    }

    public record UnaryExpr(String operator, Expr expression) implements Expr {
    }

    public record CallExpr(Expr callee, List<Expr> arguments) implements Expr {
    }

    public record ListExpr(List<Expr> elements) implements Expr {
    }

    public record DictExpr(List<Entry> entries) implements Expr {
        public record Entry(Expr key, Expr value) {
        }
    }

    public record IndexExpr(Expr target, Expr index) implements Expr {
    }

    public record AttributeExpr(Expr target, String attribute) implements Expr {
    }
}
