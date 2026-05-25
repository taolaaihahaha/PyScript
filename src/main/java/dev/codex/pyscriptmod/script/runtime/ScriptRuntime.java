package dev.codex.pyscriptmod.script.runtime;

import dev.codex.pyscriptmod.bridge.MinecraftBridge;
import dev.codex.pyscriptmod.script.io.ScriptModuleLoader;
import dev.codex.pyscriptmod.script.ir.CompiledFunction;
import dev.codex.pyscriptmod.script.ir.CompiledModule;
import dev.codex.pyscriptmod.script.ir.Compiler;
import dev.codex.pyscriptmod.script.ir.Instruction;
import dev.codex.pyscriptmod.script.ir.OpCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

public final class ScriptRuntime {
    private static final int DEFAULT_STEP_BUDGET = 5_000;
    private static final Set<String> DYNAMIC_CONTEXT_NAMES = Set.of("player", "world", "dimension", "pos", "target");
    private static final String SB_WRITE_FILE = "write_file";
    private static final String SB_READ_FILE = "read_file";
    private static final String SB_RUN_COMMANDS = "run_commands";
    private static final String SB_COPY = "copy";
    private static final String SB_PASTE = "paste";
    private static final String SB_EDIT_CLIPBOARD = "edit_clipboard";

    private final ScriptModuleLoader loader;
    private final Function<String, Boolean> sandboxPolicy;
    private final Map<String, BuiltinFunction> builtins = new LinkedHashMap<>();
    private final Map<UUID, ScriptThread> activeThreads = new ConcurrentHashMap<>();
    private final Map<String, List<EventSubscription>> subscriptions = new ConcurrentHashMap<>();
    private long currentTick;

    public ScriptRuntime(ScriptModuleLoader loader, Function<String, Boolean> sandboxPolicy) {
        this.loader = loader;
        this.sandboxPolicy = sandboxPolicy;
        registerBuiltins();
    }

    public ScriptModuleLoader loader() {
        return loader;
    }

    public UUID runModule(String moduleName, MinecraftBridge bridge, Map<String, Object> inputs) {
        ScriptInstance instance = createInstance(loader.load(moduleName), bridge, inputs);
        return runInstance(instance);
    }

    public UUID runPath(Path scriptPath, MinecraftBridge bridge, Map<String, Object> inputs) {
        ScriptInstance instance = createInstance(loader.load(scriptPath), bridge, inputs);
        return runInstance(instance);
    }

    private UUID runInstance(ScriptInstance instance) {
        ScriptThread thread = new ScriptThread(UUID.randomUUID(), instance, null);
        thread.frames.push(createFrame(instance.module.entryPoint(), instance.globals, instance.globals));
        activeThreads.put(thread.id, thread);
        runThread(thread, DEFAULT_STEP_BUDGET);
        return thread.id;
    }

    public void reloadScripts() {
        loader.invalidateAll();
        stopAll();
    }

    public StopSummary stopAll() {
        int activeThreadCount = activeThreads.size();
        int eventCount = subscriptions.values().stream().mapToInt(List::size).sum();
        activeThreads.clear();
        subscriptions.clear();
        currentTick = 0L;
        return new StopSummary(activeThreadCount, eventCount);
    }

    public StopSummary stopModule(String moduleOrFile) {
        String target = normalizeModuleName(moduleOrFile);
        if (target.isEmpty()) {
            return new StopSummary(0, 0);
        }

        int stoppedThreads = 0;
        for (Map.Entry<UUID, ScriptThread> entry : new ArrayList<>(activeThreads.entrySet())) {
            ScriptThread thread = entry.getValue();
            if (target.equals(normalizeModuleName(thread.instance.module.moduleName()))) {
                activeThreads.remove(entry.getKey());
                if (thread.subscription != null) {
                    thread.subscription.busy = false;
                }
                stoppedThreads++;
            }
        }

        int removedHandlers = 0;
        for (Map.Entry<String, List<EventSubscription>> entry : new ArrayList<>(subscriptions.entrySet())) {
            List<EventSubscription> handlers = entry.getValue();
            if (handlers == null || handlers.isEmpty()) {
                continue;
            }
            int before = handlers.size();
            handlers.removeIf(handler -> target.equals(normalizeModuleName(handler.instance.module.moduleName())));
            removedHandlers += (before - handlers.size());
            if (handlers.isEmpty()) {
                subscriptions.remove(entry.getKey());
            }
        }

        return new StopSummary(stoppedThreads, removedHandlers);
    }

    public void tick() {
        currentTick++;

        for (ScriptThread thread : new ArrayList<>(activeThreads.values())) {
            if (thread.state == ThreadState.WAITING && thread.wakeTick <= currentTick) {
                thread.state = ThreadState.RUNNABLE;
            }
        }

        dispatchEvent("tick");

        for (ScriptThread thread : new ArrayList<>(activeThreads.values())) {
            if (thread.state == ThreadState.RUNNABLE) {
                runThread(thread, DEFAULT_STEP_BUDGET);
            }
            if (thread.state == ThreadState.FINISHED || thread.state == ThreadState.FAILED) {
                cleanupThread(thread);
            }
        }
    }

    private void dispatchEvent(String eventName) {
        List<EventSubscription> handlers = subscriptions.getOrDefault(eventName, List.of());
        for (EventSubscription subscription : handlers) {
            if (subscription.busy) {
                continue;
            }
            ScriptThread thread = new ScriptThread(UUID.randomUUID(), subscription.instance, subscription);
            hydrateContext(subscription.instance);
            thread.frames.push(createFrame(subscription.function, subscription.instance.globals, new HashMap<>()));
            bindParameters(thread.frames.peek(), List.of());
            activeThreads.put(thread.id, thread);
            subscription.busy = true;
        }
    }

    private void cleanupThread(ScriptThread thread) {
        activeThreads.remove(thread.id);
        if (thread.subscription != null) {
            thread.subscription.busy = false;
        }
    }

    private ScriptInstance createInstance(CompiledModule module, MinecraftBridge bridge, Map<String, Object> inputs) {
        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("inputs", new LinkedHashMap<>(inputs));
        return new ScriptInstance(module, bridge, globals, new LinkedHashMap<>(inputs));
    }

    private CallFrame createFrame(CompiledFunction function, Map<String, Object> globals, Map<String, Object> locals) {
        return new CallFrame(function, globals, locals);
    }

    private void bindParameters(CallFrame frame, List<Object> arguments) {
        List<String> parameters = frame.function.parameters();
        if (parameters.size() != arguments.size()) {
            throw new IllegalStateException("Function " + frame.function.name() + " expected " + parameters.size()
                    + " args, got " + arguments.size());
        }
        for (int i = 0; i < parameters.size(); i++) {
            frame.locals.put(parameters.get(i), arguments.get(i));
        }
    }

    private void runThread(ScriptThread thread, int stepBudget) {
        hydrateContext(thread.instance);
        int steps = 0;
        try {
            while (thread.state == ThreadState.RUNNABLE && steps < stepBudget) {
                if (thread.frames.isEmpty()) {
                    thread.state = ThreadState.FINISHED;
                    return;
                }

                CallFrame frame = thread.frames.peek();
                if (thread.hasPendingResumeValue) {
                    frame.stack.push(thread.pendingResumeValue);
                    thread.pendingResumeValue = null;
                    thread.hasPendingResumeValue = false;
                }

                if (frame.ip >= frame.function.instructions().size()) {
                    thread.frames.pop();
                    if (thread.frames.isEmpty()) {
                        thread.state = ThreadState.FINISHED;
                    } else {
                        thread.frames.peek().stack.push(null);
                    }
                    continue;
                }

                Instruction instruction = frame.function.instructions().get(frame.ip++);
                executeInstruction(thread, frame, instruction);
                steps++;
            }
        } catch (Exception e) {
            thread.state = ThreadState.FAILED;
            thread.instance.bridge.log("[PyScript] " + e.getMessage());
        }
    }

    private void executeInstruction(ScriptThread thread, CallFrame frame, Instruction instruction) {
        switch (instruction.opCode()) {
            case LOAD_CONST -> frame.stack.push(instruction.operand());
            case LOAD_NAME -> frame.stack.push(resolveName(thread.instance, frame, (String) instruction.operand()));
            case STORE_NAME -> storeName(frame, (String) instruction.operand(), frame.stack.pop());
            case POP -> frame.stack.pop();
            case UNARY_NEGATE -> frame.stack.push(ScriptValueSupport.negate(frame.stack.pop()));
            case UNARY_NOT -> frame.stack.push(!ScriptValueSupport.isTruthy(frame.stack.pop()));
            case BINARY_ADD -> frame.stack.push(ScriptValueSupport.add(popLeft(frame), frame.stack.pop()));
            case BINARY_SUB -> frame.stack.push(ScriptValueSupport.subtract(popLeft(frame), frame.stack.pop()));
            case BINARY_MUL -> frame.stack.push(ScriptValueSupport.multiply(popLeft(frame), frame.stack.pop()));
            case BINARY_DIV -> frame.stack.push(ScriptValueSupport.divide(popLeft(frame), frame.stack.pop()));
            case BINARY_MOD -> frame.stack.push(ScriptValueSupport.mod(popLeft(frame), frame.stack.pop()));
            case BINARY_EQ -> frame.stack.push(ScriptValueSupport.equalsValue(popLeft(frame), frame.stack.pop()));
            case BINARY_NE -> frame.stack.push(!ScriptValueSupport.equalsValue(popLeft(frame), frame.stack.pop()));
            case BINARY_LT -> frame.stack.push(ScriptValueSupport.compare(popLeft(frame), frame.stack.pop()) < 0);
            case BINARY_LE -> frame.stack.push(ScriptValueSupport.compare(popLeft(frame), frame.stack.pop()) <= 0);
            case BINARY_GT -> frame.stack.push(ScriptValueSupport.compare(popLeft(frame), frame.stack.pop()) > 0);
            case BINARY_GE -> frame.stack.push(ScriptValueSupport.compare(popLeft(frame), frame.stack.pop()) >= 0);
            case BINARY_AND -> {
                Object right = frame.stack.pop();
                Object left = frame.stack.pop();
                frame.stack.push(ScriptValueSupport.isTruthy(left) && ScriptValueSupport.isTruthy(right));
            }
            case BINARY_OR -> {
                Object right = frame.stack.pop();
                Object left = frame.stack.pop();
                frame.stack.push(ScriptValueSupport.isTruthy(left) || ScriptValueSupport.isTruthy(right));
            }
            case CALL -> call(thread, frame, (int) instruction.operand());
            case BUILD_LIST -> buildList(frame, (int) instruction.operand());
            case BUILD_DICT -> buildDict(frame, (int) instruction.operand());
            case GET_INDEX -> {
                Object index = frame.stack.pop();
                Object target = frame.stack.pop();
                frame.stack.push(ScriptValueSupport.index(target, index));
            }
            case GET_ATTR -> frame.stack.push(ScriptValueSupport.attr(frame.stack.pop(), (String) instruction.operand()));
            case JUMP -> frame.ip = (int) instruction.operand();
            case JUMP_IF_FALSE -> {
                Object value = frame.stack.pop();
                if (!ScriptValueSupport.isTruthy(value)) {
                    frame.ip = (int) instruction.operand();
                }
            }
            case MAKE_FUNCTION -> frame.stack.push(new ScriptFunctionValue(thread.instance.module.functions().get((String) instruction.operand())));
            case REGISTER_EVENT -> registerEvent(thread.instance, (Compiler.EventRegistration) instruction.operand());
            case IMPORT_MODULE -> frame.stack.push(runImport(thread.instance, (String) instruction.operand()));
            case GET_ITER -> frame.stack.push(ScriptValueSupport.iterator(frame.stack.pop()));
            case FOR_ITER -> handleForIter(frame, (int) instruction.operand());
            case RETURN -> handleReturn(thread, frame);
        }
    }

    private Object popLeft(CallFrame frame) {
        Object right = frame.stack.pop();
        Object left = frame.stack.pop();
        frame.stack.push(right);
        return left;
    }

    private void buildList(CallFrame frame, int size) {
        List<Object> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(0, frame.stack.pop());
        }
        frame.stack.push(values);
    }

    private void buildDict(CallFrame frame, int size) {
        List<Object> pairs = new ArrayList<>(size * 2);
        for (int i = 0; i < size * 2; i++) {
            pairs.add(0, frame.stack.pop());
        }
        frame.stack.push(ScriptValueSupport.dictFromPairs(pairs));
    }

    private void handleForIter(CallFrame frame, int exitTarget) {
        Iterator<?> iterator = (Iterator<?>) frame.stack.peek();
        if (iterator.hasNext()) {
            frame.stack.push(iterator.next());
        } else {
            frame.stack.pop();
            frame.ip = exitTarget;
        }
    }

    private void handleReturn(ScriptThread thread, CallFrame frame) {
        Object returnValue = frame.stack.pop();
        thread.frames.pop();
        if (thread.frames.isEmpty()) {
            thread.state = ThreadState.FINISHED;
            thread.result = returnValue;
        } else {
            thread.frames.peek().stack.push(returnValue);
        }
    }

    private void call(ScriptThread thread, CallFrame frame, int argCount) {
        List<Object> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            args.add(0, frame.stack.pop());
        }
        Object callee = frame.stack.pop();

        if (callee instanceof BuiltinFunction builtin) {
            BuiltinResult result = builtin.invoke(new BuiltinContext(thread, frame), args);
            if (result.pauseTicks > 0) {
                thread.state = ThreadState.WAITING;
                thread.wakeTick = currentTick + result.pauseTicks;
                thread.pendingResumeValue = result.resumeValue;
                thread.hasPendingResumeValue = true;
            } else {
                frame.stack.push(result.resumeValue);
            }
            return;
        }

        if (callee instanceof ScriptFunctionValue functionValue) {
            CompiledFunction function = functionValue.function;
            CallFrame child = createFrame(function, thread.instance.globals, new HashMap<>());
            bindParameters(child, args);
            thread.frames.push(child);
            return;
        }

        throw new IllegalStateException("Value is not callable: " + callee);
    }

    private Object resolveName(ScriptInstance instance, CallFrame frame, String name) {
        // Dynamic context names are ALWAYS fresh - check them first before locals/globals cache
        if (DYNAMIC_CONTEXT_NAMES.contains(name)) {
            return resolveDynamicContext(instance.bridge, name);
        }
        if (frame.locals.containsKey(name)) {
            return frame.locals.get(name);
        }
        if (frame.globals.containsKey(name)) {
            return frame.globals.get(name);
        }
        if (builtins.containsKey(name)) {
            return builtins.get(name);
        }
        throw new IllegalStateException("Undefined name: " + name);
    }

    private void storeName(CallFrame frame, String name, Object value) {
        if (frame.function.topLevel()) {
            frame.globals.put(name, value);
        } else {
            frame.locals.put(name, value);
        }
    }

    private void hydrateContext(ScriptInstance instance) {
        // Dynamic context names are resolved per LOAD_NAME, so they always reflect live game state.
    }

    private Object resolveDynamicContext(MinecraftBridge bridge, String name) {
        return switch (name) {
            case "player" -> bridge.requireCurrentPlayer();
            case "world" -> bridge.currentWorld();
            case "dimension" -> bridge.currentDimension();
            case "pos" -> bridge.currentPos().asMap();
            case "target" -> bridge.currentTarget();
            default -> throw new IllegalStateException("Unknown dynamic context: " + name);
        };
    }

    private void registerEvent(ScriptInstance instance, Compiler.EventRegistration registration) {
        CompiledFunction function = instance.module.functions().get(registration.functionName());
        if (function == null) {
            throw new IllegalStateException("Unknown function for event: " + registration.functionName());
        }
        subscriptions.computeIfAbsent(registration.eventName(), ignored -> new ArrayList<>())
                .add(new EventSubscription(instance, function));
    }

    private ScriptModuleValue runImport(ScriptInstance parentInstance, String moduleName) {
        CompiledModule module = loader.load(moduleName);
        ScriptInstance importedInstance = createInstance(module, parentInstance.bridge, parentInstance.inputs);
        ScriptThread thread = new ScriptThread(UUID.randomUUID(), importedInstance, null);
        thread.frames.push(createFrame(module.entryPoint(), importedInstance.globals, importedInstance.globals));
        runThread(thread, 100_000);
        if (thread.state == ThreadState.WAITING) {
            throw new IllegalStateException("Imported module '" + moduleName + "' cannot call wait() during import in the MVP");
        }
        if (thread.state == ThreadState.FAILED) {
            throw new IllegalStateException("Import failed for module " + moduleName);
        }
        return new ScriptModuleValue(moduleName, importedInstance.globals);
    }

    private void registerBuiltins() {
        builtins.put("say", (ctx, args) -> {
            ctx.thread.instance.bridge.say(expectArgCount("say", args, 1).getFirst().toString());
            return BuiltinResult.immediate(null);
        });
        builtins.put("log", (ctx, args) -> {
            ctx.thread.instance.bridge.log(expectArgCount("log", args, 1).getFirst().toString());
            return BuiltinResult.immediate(null);
        });
        builtins.put("summon", (ctx, args) -> {
            requirePermission(ctx, "summon");
            String entityId = expectArgCountBetween("summon", args, 1, 2).getFirst().toString();
            MinecraftBridge.ScriptPos pos = args.size() > 1 ? toPos(args.get(1), ctx.thread.instance.bridge) : ctx.thread.instance.bridge.currentPos();
            return BuiltinResult.immediate(ctx.thread.instance.bridge.summon(entityId, pos));
        });
        builtins.put("tp", (ctx, args) -> {
            requirePermission(ctx, "tp");
            expectArgCount("tp", args, 4);
            ctx.thread.instance.bridge.tp(args.get(0), ScriptValueSupport.asDouble(args.get(1)),
                    ScriptValueSupport.asDouble(args.get(2)), ScriptValueSupport.asDouble(args.get(3)));
            return BuiltinResult.immediate(null);
        });
        builtins.put("give", (ctx, args) -> {
            requirePermission(ctx, "give");
            expectArgCount("give", args, 3);
            ctx.thread.instance.bridge.give(args.get(0), args.get(1).toString(), ScriptValueSupport.asInt(args.get(2)));
            return BuiltinResult.immediate(null);
        });
        builtins.put("kill", (ctx, args) -> {
            requirePermission(ctx, "kill");
            expectArgCount("kill", args, 1);
            ctx.thread.instance.bridge.kill(args.getFirst());
            return BuiltinResult.immediate(null);
        });
        builtins.put("get_block", (ctx, args) -> {
            expectArgCount("get_block", args, 3);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getBlock(
                    ScriptValueSupport.asInt(args.get(0)),
                    ScriptValueSupport.asInt(args.get(1)),
                    ScriptValueSupport.asInt(args.get(2))
            ));
        });
        builtins.put("set_block", (ctx, args) -> {
            requirePermission(ctx, "set_block");
            expectArgCount("set_block", args, 4);
            ctx.thread.instance.bridge.setBlock(
                    ScriptValueSupport.asInt(args.get(0)),
                    ScriptValueSupport.asInt(args.get(1)),
                    ScriptValueSupport.asInt(args.get(2)),
                    args.get(3).toString()
            );
            return BuiltinResult.immediate(null);
        });
        builtins.put("get_player", (ctx, args) -> {
            if (args.isEmpty()) {
                return BuiltinResult.immediate(ctx.thread.instance.bridge.requireCurrentPlayer());
            }
            expectArgCount("get_player", args, 1);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getPlayer(args.getFirst().toString()));
        });
        builtins.put("inventory", (ctx, args) -> {
            expectArgCount("inventory", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.scanInventory());
        });
        builtins.put("get_inventory", (ctx, args) -> {
            expectArgCount("get_inventory", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.scanInventory());
        });
        builtins.put("equipment", (ctx, args) -> {
            expectArgCount("equipment", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.scanEquipment());
        });
        builtins.put("get_equipment", (ctx, args) -> {
            expectArgCount("get_equipment", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.scanEquipment());
        });
        builtins.put("left_hand", (ctx, args) -> {
            expectArgCount("left_hand", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getOffHand());
        });
        builtins.put("get_left_hand", (ctx, args) -> {
            expectArgCount("get_left_hand", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getOffHand());
        });
        builtins.put("edit_inventory", (ctx, args) -> {
            requirePermission(ctx, "edit_inventory");
            expectArgCountBetween("edit_inventory", args, 3, 4);
            int slot = ScriptValueSupport.asInt(args.get(0));
            String itemId = args.get(1).toString();
            int amount = ScriptValueSupport.asInt(args.get(2));
            Object nbt = args.size() > 3 ? args.get(3) : null;
            ctx.thread.instance.bridge.editInventory(slot, itemId, amount, nbt);
            return BuiltinResult.immediate(null);
        });
        builtins.put("wait", (ctx, args) -> {
            expectArgCount("wait", args, 1);
            return BuiltinResult.pause(ScriptValueSupport.asLong(args.getFirst()), null);
        });
        builtins.put("input", (ctx, args) -> {
            expectArgCountBetween("input", args, 1, 2);
            String key = args.getFirst().toString();
            Object value = ctx.thread.instance.inputs.get(key);
            if (value == null && args.size() == 2) {
                value = args.get(1);
            }
            return BuiltinResult.immediate(value);
        });
        builtins.put("range", (ctx, args) -> BuiltinResult.immediate(ScriptValueSupport.buildRange(args)));
        builtins.put("len", (ctx, args) -> {
            expectArgCount("len", args, 1);
            Object value = args.getFirst();
            if (value instanceof List<?> list) {
                return BuiltinResult.immediate((long) list.size());
            }
            if (value instanceof Map<?, ?> map) {
                return BuiltinResult.immediate((long) map.size());
            }
            if (value instanceof String string) {
                return BuiltinResult.immediate((long) string.length());
            }
            throw new IllegalStateException("len() does not support " + ScriptValueSupport.typeName(value));
        });
        builtins.put("get_name", (ctx, args) -> {
            expectArgCount("get_name", args, 1);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getName(args.getFirst()));
        });
        builtins.put("get_hp", (ctx, args) -> {
            expectArgCount("get_hp", args, 1);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getHp(args.getFirst()));
        });
        builtins.put("set_hp", (ctx, args) -> {
            expectArgCountBetween("set_hp", args, 1, 2);
            double hp = ScriptValueSupport.asDouble(args.get(0));
            Object target = args.size() == 2
                    ? args.get(1)
                    : ctx.thread.instance.bridge.requireCurrentPlayer();
            ctx.thread.instance.bridge.setHp(target, hp);
            return BuiltinResult.immediate(null);
        });
        builtins.put("add_hp", (ctx, args) -> {
            expectArgCountBetween("add_hp", args, 1, 2);
            double delta = ScriptValueSupport.asDouble(args.get(0));
            Object target = args.size() == 2
                    ? args.get(1)
                    : ctx.thread.instance.bridge.requireCurrentPlayer();
            ctx.thread.instance.bridge.addHp(target, delta);
            return BuiltinResult.immediate(null);
        });
        builtins.put("get_mana", (ctx, args) -> {
            expectArgCount("get_mana", args, 1);
            return BuiltinResult.immediate((long) ctx.thread.instance.bridge.getMana(args.getFirst()));
        });
        builtins.put("get_xp", (ctx, args) -> {
            expectArgCountBetween("get_xp", args, 0, 1);
            Object target = args.isEmpty()
                    ? ctx.thread.instance.bridge.requireCurrentPlayer()
                    : args.getFirst();
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getXp(target));
        });
        builtins.put("get_gamemode", (ctx, args) -> {
            expectArgCountBetween("get_gamemode", args, 0, 1);
            Object target = args.isEmpty()
                    ? ctx.thread.instance.bridge.requireCurrentPlayer()
                    : args.getFirst();
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getGameMode(target));
        });
        builtins.put("get_difficulty", (ctx, args) -> {
            expectArgCount("get_difficulty", args, 0);
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getDifficulty());
        });
        builtins.put("add_advancement", (ctx, args) -> {
            expectArgCountBetween("add_advancement", args, 1, 2);
            String advancementId = args.get(0).toString();
            Object target = args.size() == 2
                    ? args.get(1)
                    : ctx.thread.instance.bridge.requireCurrentPlayer();
            ctx.thread.instance.bridge.grantAdvancement(target, advancementId);
            return BuiltinResult.immediate(null);
        });
        builtins.put("get_advancements", (ctx, args) -> {
            expectArgCountBetween("get_advancements", args, 0, 1);
            Object target = args.isEmpty()
                    ? ctx.thread.instance.bridge.requireCurrentPlayer()
                    : args.getFirst();
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getAdvancements(target));
        });
        builtins.put("get_look", (ctx, args) -> {
            expectArgCountBetween("get_look", args, 0, 1);
            Object target = args.isEmpty()
                    ? ctx.thread.instance.bridge.requireCurrentPlayer()
                    : args.getFirst();
            return BuiltinResult.immediate(ctx.thread.instance.bridge.getLook(target));
        });
        builtins.put("set_look", (ctx, args) -> {
            expectArgCountBetween("set_look", args, 2, 3);
            double yaw = ScriptValueSupport.asDouble(args.get(0));
            double pitch = ScriptValueSupport.asDouble(args.get(1));
            Object target = args.size() == 3
                    ? args.get(2)
                    : ctx.thread.instance.bridge.requireCurrentPlayer();
            ctx.thread.instance.bridge.setLook(target, yaw, pitch);
            return BuiltinResult.immediate(null);
        });
        builtins.put("click_lmb", (ctx, args) -> {
            expectArgCountBetween("click_lmb", args, 0, 1);
            Object target = args.isEmpty() ? null : args.getFirst();
            ctx.thread.instance.bridge.clickLeft(target);
            return BuiltinResult.immediate(null);
        });
        builtins.put("click_rmb", (ctx, args) -> {
            expectArgCountBetween("click_rmb", args, 0, 1);
            Object target = args.isEmpty() ? null : args.getFirst();
            ctx.thread.instance.bridge.clickRight(target);
            return BuiltinResult.immediate(null);
        });
        builtins.put("click_mmb", (ctx, args) -> {
            expectArgCount("click_mmb", args, 0);
            ctx.thread.instance.bridge.clickMiddle();
            return BuiltinResult.immediate(null);
        });
        builtins.put("scroll", (ctx, args) -> {
            expectArgCount("scroll", args, 1);
            ctx.thread.instance.bridge.scrollHotbar(ScriptValueSupport.asInt(args.getFirst()));
            return BuiltinResult.immediate(null);
        });
        builtins.put("read_text", (ctx, args) -> {
            expectArgCount("read_text", args, 1);
            if (isSandboxBlocked(SB_READ_FILE)) {
                return BuiltinResult.immediate("");
            }
            Path path = resolveScriptFilePath(args.getFirst().toString());
            try {
                return BuiltinResult.immediate(Files.readString(path));
            } catch (IOException e) {
                throw new IllegalStateException("read_text failed: " + e.getMessage());
            }
        });
        builtins.put("write_text", (ctx, args) -> {
            expectArgCountBetween("write_text", args, 2, 3);
            if (isSandboxBlocked(SB_WRITE_FILE)) {
                return BuiltinResult.immediate(null);
            }
            Path path = resolveScriptFilePath(args.get(0).toString());
            String content = String.valueOf(args.get(1));
            boolean append = args.size() == 3 && ScriptValueSupport.isTruthy(args.get(2));
            try {
                Files.createDirectories(path.getParent());
                if (append) {
                    Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } else {
                    Files.writeString(path, content);
                }
            } catch (IOException e) {
                throw new IllegalStateException("write_text failed: " + e.getMessage());
            }
            return BuiltinResult.immediate(null);
        });
        builtins.put("list_files", (ctx, args) -> {
            expectArgCountBetween("list_files", args, 0, 1);
            if (isSandboxBlocked(SB_READ_FILE)) {
                return BuiltinResult.immediate(List.of());
            }
            String suffix = args.isEmpty() ? "" : args.getFirst().toString();
            try (Stream<Path> stream = Files.walk(loader.scriptRoot())) {
                List<String> files = stream
                        .filter(Files::isRegularFile)
                        .map(path -> loader.scriptRoot().relativize(path).toString().replace('\\', '/'))
                        .filter(name -> suffix.isEmpty() || name.endsWith(suffix))
                        .sorted()
                        .collect(Collectors.toList());
                return BuiltinResult.immediate(files);
            } catch (IOException e) {
                throw new IllegalStateException("list_files failed: " + e.getMessage());
            }
        });
        builtins.put("run", (ctx, args) -> {
            expectArgCount("run", args, 1);
            ctx.thread.instance.bridge.runCommandAsServer(args.getFirst().toString());
            return BuiltinResult.immediate(null);
        });
        builtins.put("run_os", (ctx, args) -> {
            expectArgCountBetween("run_os", args, 1, 2);
            if (isSandboxBlocked(SB_RUN_COMMANDS)) {
                return BuiltinResult.immediate("");
            }
            String command = args.get(0).toString();
            String shell = args.size() == 2 ? args.get(1).toString().toLowerCase() : "powershell";
            return BuiltinResult.immediate(runOsCommand(command, shell));
        });
        builtins.put("copy", (ctx, args) -> {
            expectArgCountBetween("copy", args, 0, 1);
            if (isSandboxBlocked(SB_COPY)) {
                return BuiltinResult.immediate("");
            }
            String payload = args.isEmpty()
                    ? buildRuntimeSnapshot(ctx)
                    : resolveCopyPayload(args.getFirst());
            setClipboard(payload, SB_COPY);
            return BuiltinResult.immediate(payload);
        });
        builtins.put("paste", (ctx, args) -> {
            expectArgCount("paste", args, 0);
            if (isSandboxBlocked(SB_PASTE)) {
                return BuiltinResult.immediate("");
            }
            return BuiltinResult.immediate(getClipboardText());
        });
        builtins.put("edit_clipboard", (ctx, args) -> {
            expectArgCount("edit_clipboard", args, 1);
            if (isSandboxBlocked(SB_EDIT_CLIPBOARD)) {
                return BuiltinResult.immediate("");
            }
            String text = String.valueOf(args.getFirst());
            setClipboard(text, SB_EDIT_CLIPBOARD);
            return BuiltinResult.immediate(text);
        });
        builtins.put("set_gamemode", (ctx, args) -> {
            expectArgCountBetween("set_gamemode", args, 1, 2);
            String mode = args.get(0).toString().toLowerCase();
            String target = args.size() == 2
                    ? args.get(1).toString()
                    : ctx.thread.instance.bridge.requireCurrentPlayer().name();
            ctx.thread.instance.bridge.runCommandAsServer("gamemode " + mode + " " + target);
            return BuiltinResult.immediate(null);
        });
        builtins.put("set_difficulty", (ctx, args) -> {
            expectArgCount("set_difficulty", args, 1);
            String difficulty = args.getFirst().toString().toLowerCase();
            ctx.thread.instance.bridge.runCommandAsServer("difficulty " + difficulty);
            return BuiltinResult.immediate(null);
        });
        builtins.put("set_xp", (ctx, args) -> {
            expectArgCountBetween("set_xp", args, 1, 3);
            long amount = ScriptValueSupport.asLong(args.get(0));
            String unit = args.size() >= 2 ? args.get(1).toString().toLowerCase() : "points";
            if (!unit.equals("points") && !unit.equals("levels")) {
                throw new IllegalStateException("set_xp unit must be 'points' or 'levels'");
            }
            String target = args.size() == 3
                    ? args.get(2).toString()
                    : ctx.thread.instance.bridge.requireCurrentPlayer().name();
            ctx.thread.instance.bridge.runCommandAsServer("xp set " + target + " " + amount + " " + unit);
            return BuiltinResult.immediate(null);
        });
        builtins.put("on", (ctx, args) -> {
            expectArgCount("on", args, 2);
            if (!(args.get(1) instanceof ScriptFunctionValue functionValue)) {
                throw new IllegalStateException("on(event, handler) requires a script function as second argument");
            }
            subscriptions.computeIfAbsent(args.getFirst().toString(), ignored -> new ArrayList<>())
                    .add(new EventSubscription(ctx.thread.instance, functionValue.function));
            return BuiltinResult.immediate(null);
        });
    }

    private List<Object> expectArgCount(String name, List<Object> args, int count) {
        if (args.size() != count) {
            throw new IllegalStateException(name + "() expects " + count + " arguments");
        }
        return args;
    }

    private List<Object> expectArgCountBetween(String name, List<Object> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalStateException(name + "() expects between " + min + " and " + max + " arguments");
        }
        return args;
    }

    private void requirePermission(BuiltinContext ctx, String action) {
        if (!ctx.thread.instance.bridge.hasPermission(action)) {
            throw new IllegalStateException("Permission denied for action: " + action);
        }
    }

    private MinecraftBridge.ScriptPos toPos(Object raw, MinecraftBridge bridge) {
        if (raw instanceof MinecraftBridge.ScriptPos pos) {
            return pos;
        }
        if (raw instanceof List<?> list && list.size() == 3) {
            return new MinecraftBridge.ScriptPos(
                    ScriptValueSupport.asDouble(list.get(0)),
                    ScriptValueSupport.asDouble(list.get(1)),
                    ScriptValueSupport.asDouble(list.get(2))
            );
        }
        if (raw instanceof Map<?, ?> map) {
            return new MinecraftBridge.ScriptPos(
                    ScriptValueSupport.asDouble(map.get("x")),
                    ScriptValueSupport.asDouble(map.get("y")),
                    ScriptValueSupport.asDouble(map.get("z"))
            );
        }
        return bridge.currentPos();
    }

    private Path resolveScriptFilePath(String rawPath) {
        Path root = loader.scriptRoot().toAbsolutePath().normalize();
        Path path = Path.of(rawPath);
        Path resolved = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Path escapes script root: " + rawPath);
        }
        return resolved;
    }

    private String normalizeModuleName(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        if (value.endsWith(".ppy") || value.endsWith(".cpy")) {
            value = value.substring(0, value.length() - 4);
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(dot + 1);
        }
        return value;
    }

    private boolean isSandboxBlocked(String key) {
        try {
            return sandboxPolicy.apply(key);
        } catch (Exception e) {
            return true;
        }
    }

    private String runOsCommand(String command, String shell) {
        ProcessBuilder builder;
        if ("cmd".equals(shell)) {
            builder = new ProcessBuilder("cmd.exe", "/c", command);
        } else if ("powershell".equals(shell) || "pwsh".equals(shell)) {
            builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
        } else {
            throw new IllegalStateException("run_os shell must be 'powershell' or 'cmd'");
        }

        try {
            Process process = builder.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            process.waitFor();
            String out = new String(stdout).trim();
            String err = new String(stderr).trim();
            if (!err.isEmpty()) {
                return out.isEmpty() ? err : out + System.lineSeparator() + err;
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("run_os interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("run_os failed: " + e.getMessage());
        }
    }

    private String buildRuntimeSnapshot(BuiltinContext ctx) {
        StringBuilder builder = new StringBuilder();
        builder.append("# module=").append(ctx.thread.instance.module.moduleName()).append(System.lineSeparator());
        builder.append("# globals").append(System.lineSeparator());
        for (Map.Entry<String, Object> entry : ctx.thread.instance.globals.entrySet()) {
            builder.append(entry.getKey()).append(" = ").append(String.valueOf(entry.getValue())).append(System.lineSeparator());
        }
        builder.append("# locals").append(System.lineSeparator());
        for (Map.Entry<String, Object> entry : ctx.frame.locals.entrySet()) {
            builder.append(entry.getKey()).append(" = ").append(String.valueOf(entry.getValue())).append(System.lineSeparator());
        }
        builder.append("# dynamic").append(System.lineSeparator());
        for (String name : DYNAMIC_CONTEXT_NAMES) {
            builder.append(name).append(" = ").append(String.valueOf(resolveDynamicContext(ctx.thread.instance.bridge, name)))
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String resolveCopyPayload(Object raw) {
        if (raw instanceof String rawString) {
            Path path = Path.of(rawString);
            Path resolved = path.isAbsolute() ? path.normalize() : loader.scriptRoot().resolve(path).normalize();
            if (Files.exists(resolved) && Files.isRegularFile(resolved) && !isSandboxBlocked(SB_READ_FILE)) {
                try {
                    return Files.readString(resolved);
                } catch (IOException e) {
                    throw new IllegalStateException("copy(file) failed: " + e.getMessage());
                }
            }
            return rawString;
        }
        return String.valueOf(raw);
    }

    private void setClipboard(String text, String gate) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        } catch (Exception e) {
            throw new IllegalStateException("clipboard write failed for gate=" + gate + ": " + e.getMessage());
        }
    }

    private String getClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return "";
            }
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data == null ? "" : String.valueOf(data);
        } catch (Exception e) {
            throw new IllegalStateException("clipboard read failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface BuiltinFunction {
        BuiltinResult invoke(BuiltinContext ctx, List<Object> args);
    }

    private record BuiltinContext(ScriptThread thread, CallFrame frame) {
    }

    private record BuiltinResult(long pauseTicks, Object resumeValue) {
        private static BuiltinResult immediate(Object value) {
            return new BuiltinResult(0, value);
        }

        private static BuiltinResult pause(long ticks, Object value) {
            return new BuiltinResult(Math.max(0, ticks), value);
        }
    }

    private static final class ScriptInstance {
        private final CompiledModule module;
        private final MinecraftBridge bridge;
        private final Map<String, Object> globals;
        private final Map<String, Object> inputs;

        private ScriptInstance(CompiledModule module, MinecraftBridge bridge, Map<String, Object> globals, Map<String, Object> inputs) {
            this.module = module;
            this.bridge = bridge;
            this.globals = globals;
            this.inputs = inputs;
        }
    }

    private static final class CallFrame {
        private final CompiledFunction function;
        private final Map<String, Object> globals;
        private final Map<String, Object> locals;
        private final Deque<Object> stack = new LinkedList<>();
        private int ip;

        private CallFrame(CompiledFunction function, Map<String, Object> globals, Map<String, Object> locals) {
            this.function = function;
            this.globals = globals;
            this.locals = locals;
        }
    }

    private enum ThreadState {
        RUNNABLE,
        WAITING,
        FINISHED,
        FAILED
    }

    private static final class ScriptThread {
        private final UUID id;
        private final ScriptInstance instance;
        private final EventSubscription subscription;
        private final Deque<CallFrame> frames = new ArrayDeque<>();
        private ThreadState state = ThreadState.RUNNABLE;
        private long wakeTick;
        private Object pendingResumeValue;
        private boolean hasPendingResumeValue;
        private Object result;

        private ScriptThread(UUID id, ScriptInstance instance, EventSubscription subscription) {
            this.id = id;
            this.instance = instance;
            this.subscription = subscription;
        }
    }

    private static final class EventSubscription {
        private final ScriptInstance instance;
        private final CompiledFunction function;
        private boolean busy;

        private EventSubscription(ScriptInstance instance, CompiledFunction function) {
            this.instance = Objects.requireNonNull(instance);
            this.function = Objects.requireNonNull(function);
        }
    }

    public static final class ScriptFunctionValue {
        private final CompiledFunction function;

        private ScriptFunctionValue(CompiledFunction function) {
            this.function = function;
        }

        @Override
        public String toString() {
            return "<function " + function.name() + ">";
        }
    }

    public record ScriptModuleValue(String moduleName, Map<String, Object> exports) {
        @Override
        public String toString() {
            return "<module " + moduleName + ">";
        }
    }

    public record StopSummary(int threadsStopped, int eventHandlersRemoved) {
    }
}
