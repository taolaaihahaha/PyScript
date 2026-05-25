package dev.codex.pyscriptmod.script.ir;

import dev.codex.pyscriptmod.script.lang.Ast;
import dev.codex.pyscriptmod.script.lang.Ast.Assign;
import dev.codex.pyscriptmod.script.lang.Ast.AttributeExpr;
import dev.codex.pyscriptmod.script.lang.Ast.BinaryExpr;
import dev.codex.pyscriptmod.script.lang.Ast.BreakStmt;
import dev.codex.pyscriptmod.script.lang.Ast.CallExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ContinueStmt;
import dev.codex.pyscriptmod.script.lang.Ast.Decorator;
import dev.codex.pyscriptmod.script.lang.Ast.DictExpr;
import dev.codex.pyscriptmod.script.lang.Ast.Expr;
import dev.codex.pyscriptmod.script.lang.Ast.ExprStmt;
import dev.codex.pyscriptmod.script.lang.Ast.ForStmt;
import dev.codex.pyscriptmod.script.lang.Ast.FunctionDef;
import dev.codex.pyscriptmod.script.lang.Ast.IfStmt;
import dev.codex.pyscriptmod.script.lang.Ast.ImportStmt;
import dev.codex.pyscriptmod.script.lang.Ast.IndexExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ListExpr;
import dev.codex.pyscriptmod.script.lang.Ast.LiteralExpr;
import dev.codex.pyscriptmod.script.lang.Ast.Module;
import dev.codex.pyscriptmod.script.lang.Ast.NameExpr;
import dev.codex.pyscriptmod.script.lang.Ast.ReturnStmt;
import dev.codex.pyscriptmod.script.lang.Ast.Stmt;
import dev.codex.pyscriptmod.script.lang.Ast.UnaryExpr;
import dev.codex.pyscriptmod.script.lang.Ast.WhileStmt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.Serial;
import java.io.Serializable;

public final class Compiler {
    private final Map<String, CompiledFunction> functions = new LinkedHashMap<>();

    public CompiledModule compile(String moduleName, Module module) {
        FunctionCompiler topLevel = new FunctionCompiler("__main__", List.of(), true);
        for (Stmt stmt : module.statements()) {
            if (stmt instanceof FunctionDef functionDef) {
                compileFunction(functionDef);
                topLevel.emit(Instruction.of(OpCode.MAKE_FUNCTION), functionDef.name());
                topLevel.emit(Instruction.of(OpCode.STORE_NAME), functionDef.name());
                for (Decorator decorator : functionDef.decorators()) {
                    compileDecorator(topLevel, decorator, functionDef.name());
                }
            } else {
                topLevel.compileStatement(stmt);
            }
        }
        topLevel.emit(Instruction.of(OpCode.LOAD_CONST), null);
        topLevel.emit(Instruction.of(OpCode.RETURN));
        CompiledFunction entryPoint = topLevel.finish();
        return new CompiledModule(moduleName, entryPoint, Map.copyOf(functions));
    }

    private void compileDecorator(FunctionCompiler compiler, Decorator decorator, String functionName) {
        if (!"event".equals(decorator.name())) {
            throw new IllegalStateException("Unsupported decorator @" + decorator.name() + " in MVP");
        }
        if (decorator.arguments().size() != 1 || !(decorator.arguments().get(0) instanceof LiteralExpr literal) || !(literal.value() instanceof String eventName)) {
            throw new IllegalStateException("@event requires a single string literal");
        }
        compiler.emit(Instruction.of(OpCode.REGISTER_EVENT), new EventRegistration(eventName, functionName));
    }

    private void compileFunction(FunctionDef def) {
        FunctionCompiler compiler = new FunctionCompiler(def.name(), def.parameters(), false);
        for (Stmt stmt : def.body()) {
            if (stmt instanceof FunctionDef) {
                throw new IllegalStateException("Nested functions are not supported in the MVP");
            }
            compiler.compileStatement(stmt);
        }
        compiler.emit(Instruction.of(OpCode.LOAD_CONST), null);
        compiler.emit(Instruction.of(OpCode.RETURN));
        functions.put(def.name(), compiler.finish());
    }

    public record EventRegistration(String eventName, String functionName) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class FunctionCompiler {
        private final String name;
        private final List<String> parameters;
        private final boolean topLevel;
        private final List<Instruction> instructions = new ArrayList<>();
        private final Deque<LoopContext> loops = new ArrayDeque<>();

        private FunctionCompiler(String name, List<String> parameters, boolean topLevel) {
            this.name = name;
            this.parameters = List.copyOf(parameters);
            this.topLevel = topLevel;
        }

        private CompiledFunction finish() {
            return new CompiledFunction(name, parameters, List.copyOf(instructions), topLevel);
        }

        private void emit(Instruction template) {
            instructions.add(template);
        }

        private void emit(Instruction template, Object operand) {
            instructions.add(new Instruction(template.opCode(), operand));
        }

        private int emitJump(OpCode opCode) {
            instructions.add(new Instruction(opCode, -1));
            return instructions.size() - 1;
        }

        private void patchJump(int index, int target) {
            Instruction original = instructions.get(index);
            instructions.set(index, new Instruction(original.opCode(), target));
        }

        private void compileStatement(Stmt stmt) {
            switch (stmt) {
                case Assign assign -> {
                    compileExpr(assign.value());
                    emit(Instruction.of(OpCode.STORE_NAME), assign.name());
                }
                case ExprStmt exprStmt -> {
                    compileExpr(exprStmt.expression());
                    emit(Instruction.of(OpCode.POP));
                }
                case IfStmt ifStmt -> compileIf(ifStmt);
                case WhileStmt whileStmt -> compileWhile(whileStmt);
                case ForStmt forStmt -> compileFor(forStmt);
                case ReturnStmt returnStmt -> {
                    if (returnStmt.value() == null) {
                        emit(Instruction.of(OpCode.LOAD_CONST), null);
                    } else {
                        compileExpr(returnStmt.value());
                    }
                    emit(Instruction.of(OpCode.RETURN));
                }
                case BreakStmt ignored -> compileBreak();
                case ContinueStmt ignored -> compileContinue();
                case ImportStmt importStmt -> {
                    emit(Instruction.of(OpCode.IMPORT_MODULE), importStmt.moduleName());
                    emit(Instruction.of(OpCode.STORE_NAME), importStmt.moduleName());
                }
                case FunctionDef ignored -> throw new IllegalStateException("Function definitions are only supported at module scope in the MVP");
            }
        }

        private void compileIf(IfStmt stmt) {
            List<Integer> endJumps = new ArrayList<>();
            for (IfStmt.Branch branch : stmt.branches()) {
                compileExpr(branch.condition());
                int falseJump = emitJump(OpCode.JUMP_IF_FALSE);
                compileBlock(branch.body());
                endJumps.add(emitJump(OpCode.JUMP));
                patchJump(falseJump, instructions.size());
            }
            compileBlock(stmt.elseBranch());
            int end = instructions.size();
            for (int jump : endJumps) {
                patchJump(jump, end);
            }
        }

        private void compileWhile(WhileStmt stmt) {
            int loopStart = instructions.size();
            compileExpr(stmt.condition());
            int exitJump = emitJump(OpCode.JUMP_IF_FALSE);
            LoopContext loop = new LoopContext(loopStart);
            loops.push(loop);
            compileBlock(stmt.body());
            emit(Instruction.of(OpCode.JUMP), loopStart);
            int loopEnd = instructions.size();
            patchJump(exitJump, loopEnd);
            patchLoop(loop, loopEnd);
        }

        private void compileFor(ForStmt stmt) {
            compileExpr(stmt.iterable());
            emit(Instruction.of(OpCode.GET_ITER));
            int loopStart = instructions.size();
            int exitJump = emitJump(OpCode.FOR_ITER);
            emit(Instruction.of(OpCode.STORE_NAME), stmt.variable());
            LoopContext loop = new LoopContext(loopStart);
            loops.push(loop);
            compileBlock(stmt.body());
            emit(Instruction.of(OpCode.JUMP), loopStart);
            int loopEnd = instructions.size();
            patchJump(exitJump, loopEnd);
            patchLoop(loop, loopEnd);
        }

        private void patchLoop(LoopContext loop, int loopEnd) {
            loops.pop();
            for (int jump : loop.breakJumps) {
                patchJump(jump, loopEnd);
            }
            for (int jump : loop.continueJumps) {
                patchJump(jump, loop.continueTarget);
            }
        }

        private void compileBreak() {
            LoopContext loop = requireLoop("break");
            loop.breakJumps.add(emitJump(OpCode.JUMP));
        }

        private void compileContinue() {
            LoopContext loop = requireLoop("continue");
            loop.continueJumps.add(emitJump(OpCode.JUMP));
        }

        private LoopContext requireLoop(String keyword) {
            LoopContext loop = loops.peek();
            if (loop == null) {
                throw new IllegalStateException(keyword + " can only be used inside a loop");
            }
            return loop;
        }

        private void compileBlock(List<Stmt> statements) {
            for (Stmt stmt : statements) {
                compileStatement(stmt);
            }
        }

        private void compileExpr(Expr expr) {
            switch (expr) {
                case LiteralExpr literal -> emit(Instruction.of(OpCode.LOAD_CONST), literal.value());
                case NameExpr nameExpr -> emit(Instruction.of(OpCode.LOAD_NAME), nameExpr.name());
                case UnaryExpr unaryExpr -> {
                    compileExpr(unaryExpr.expression());
                    emit(Instruction.of(switch (unaryExpr.operator()) {
                        case "-" -> OpCode.UNARY_NEGATE;
                        case "not" -> OpCode.UNARY_NOT;
                        default -> throw new IllegalStateException("Unsupported unary operator: " + unaryExpr.operator());
                    }));
                }
                case BinaryExpr binaryExpr -> {
                    compileExpr(binaryExpr.left());
                    compileExpr(binaryExpr.right());
                    emit(Instruction.of(mapBinary(binaryExpr.operator())));
                }
                case CallExpr callExpr -> {
                    compileExpr(callExpr.callee());
                    for (Expr argument : callExpr.arguments()) {
                        compileExpr(argument);
                    }
                    emit(Instruction.of(OpCode.CALL), callExpr.arguments().size());
                }
                case ListExpr listExpr -> {
                    for (Expr element : listExpr.elements()) {
                        compileExpr(element);
                    }
                    emit(Instruction.of(OpCode.BUILD_LIST), listExpr.elements().size());
                }
                case DictExpr dictExpr -> {
                    for (DictExpr.Entry entry : dictExpr.entries()) {
                        compileExpr(entry.key());
                        compileExpr(entry.value());
                    }
                    emit(Instruction.of(OpCode.BUILD_DICT), dictExpr.entries().size());
                }
                case IndexExpr indexExpr -> {
                    compileExpr(indexExpr.target());
                    compileExpr(indexExpr.index());
                    emit(Instruction.of(OpCode.GET_INDEX));
                }
                case AttributeExpr attributeExpr -> {
                    compileExpr(attributeExpr.target());
                    emit(Instruction.of(OpCode.GET_ATTR), attributeExpr.attribute());
                }
            }
        }

        private OpCode mapBinary(String operator) {
            return switch (operator) {
                case "+" -> OpCode.BINARY_ADD;
                case "-" -> OpCode.BINARY_SUB;
                case "*" -> OpCode.BINARY_MUL;
                case "/" -> OpCode.BINARY_DIV;
                case "%" -> OpCode.BINARY_MOD;
                case "==" -> OpCode.BINARY_EQ;
                case "!=" -> OpCode.BINARY_NE;
                case "<" -> OpCode.BINARY_LT;
                case "<=" -> OpCode.BINARY_LE;
                case ">" -> OpCode.BINARY_GT;
                case ">=" -> OpCode.BINARY_GE;
                case "and" -> OpCode.BINARY_AND;
                case "or" -> OpCode.BINARY_OR;
                default -> throw new IllegalStateException("Unsupported operator: " + operator);
            };
        }

        private static final class LoopContext {
            private final int continueTarget;
            private final List<Integer> breakJumps = new ArrayList<>();
            private final List<Integer> continueJumps = new ArrayList<>();

            private LoopContext(int continueTarget) {
                this.continueTarget = continueTarget;
            }
        }
    }
}
