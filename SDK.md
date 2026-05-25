**PyScript Mod SDK**

This document describes the scripting language, built-in functions, runtime semantics, and example patterns for writing scripts for the PyScript Fabric mod.

**Language Overview**:
- **Syntax**: Python-like, indentation (4 spaces) denotes blocks.
- **Files**: Scripts use the `.pms` extension and live in the mod script root.
- **Top-level**: Top-level statements run when a module is loaded.
- **Functions**: `def` syntax, optional decorators (e.g. `@event("tick")`).

**Core Language Features**:
- `def name(params):` — function declaration.
- `return` — return a value from a function.
- Conditionals: `if`, `elif`, `else`.
- Loops: `for`, `while`, with `break` and `continue`.
- Collections: list `[1, 2, 3]`, dict `{"k": "v"}`.
- Indexing: `a[0]`, attribute access: `obj.attr` (bridge types).
- Decorators: currently supports `@event("name")` for event handlers.
- `wait(ticks)` — pause current thread for N ticks (yield control).

**Module & Import**:
- `import module_name` loads `module_name.pms` from script root.
- Imports run synchronously and may not call `wait()` during import.

**Execution Model**:
- Scripts run inside a stack-based VM.
- Each function call has a call frame and local scope.
- The runtime schedules threads and enforces a per-tick step budget to avoid lag.

**Built-in Context Bindings** (available at runtime):
- `player` — the `ScriptPlayer` record for the executing player. **Always current** per access.
- `world` — current world identifier. **Always current** per access.
- `dimension` — current dimension id string. **Always current** per access.
- `pos` — map with `{"x":...,"y":...,"z":...}` of the player. **Always current** per access.
- `target` — aimed/selected entity or null. **Always current** per access.

**Note**: These context values are **dynamically resolved** on every access, not cached. This means you can safely use them in loops and event handlers, and they will reflect the player's live game state.

**Selected Built-in Functions** (common):
- `say(msg)` : broadcast chat + feedback.
- `log(msg)` : server log and feedback.
- `get_player(name?)` : resolves a player or returns current player.
- `get_hp(target)` / `set_hp(target, hp)` / `add_hp(delta, target)`
- `summon(entityId, pos?)`
- `tp(target, x, y, z)`
- `give(target, itemId, amount)`
- `inventory()` / `get_inventory()` : returns list of 36 slot values (UI order).
- `equipment()` : returns list of 4 equipment slots.
- `left_hand()` / `get_left_hand()` : offhand item value.
- `wait(ticks)` : pause this thread N ticks.
- `input(key, default?)` : get command inputs provided when running.
- `read_text(path)` / `write_text(path, content, append=False)` (permission gated: `read_file`, `write_file`)
- `edit_inventory(slot, itemId, amount, nbt?)` : set a specific inventory slot (permission gated: `edit_inventory`, see below)

**Server Commands** (always allowed, no permission needed):
- `run(command)` : execute Minecraft server command (e.g. `/give @s diamond`)
- `set_gamemode(mode, target?)` : set gamemode (survival, creative, adventure, spectator)
- `set_difficulty(difficulty)` : set server difficulty (peaceful, easy, normal, hard)
- `set_xp(amount, unit?, target?)` : set player XP (unit: "points" or "levels")

**OS Shell Commands** (requires `run_commands=true` permission):
- `run_os(command, shell?)` : execute OS shell command, returns stdout+stderr (default shell: "powershell")

**Permissions / Sandbox**:
- High-risk operations (file I/O, OS commands, clipboard, inventory edit) require permissions set in `settings.properties`.
- By default, the runtime is sandboxed and denies these actions.
- **Server commands** (`run()`, `set_gamemode()`, `set_difficulty()`, `set_xp()`) are **always allowed** - no permission needed.
- **OS shell execution** (`run_os()`) requires `run_commands=true` permission.

**OS Shell Execution (`run_os`)**:
- Signature: `run_os(command, shell?)`.
- `command` is the shell command to execute (string).
- `shell` (optional): `"powershell"` (default), `"pwsh"`, or `"cmd"`.
- **Returns**: stdout + stderr combined as a string.
- **Requires**: `run_commands` permission enabled.
- Examples:

```python
# List directory (default: powershell)
result = run_os("Get-ChildItem -Path C:\\Users")
log("Dir result: " + result)

# Ping a host
ping_result = run_os("ping -n 1 google.com")
say("Ping: " + ping_result)

# Use cmd.exe instead
cmd_result = run_os("echo Hello World", "cmd")
```

**`edit_inventory` details**:
- Signature: `edit_inventory(slot, itemId, amount, nbt?)`.
- `slot` uses UI order mapping (0-26 main inventory, top-left → top-right; 27-35 hotbar left→right).
- `itemId` is a full registry id string, e.g. `minecraft:diamond_sword`.
- `amount` is integer stack size.
- `nbt` (optional): pass a nested map to describe NBT-like structure. Support is implementation-dependent; nested maps and primitives are converted into the best-effort representation. Complex lists and exact vanilla NBT shapes may require enhancement depending on Minecraft version.
- Example:

```python
# place a diamond sword in UI slot 0
edit_inventory(0, "minecraft:diamond_sword", 1)

# set a stack with simple metadata map (implementation best-effort)
edit_inventory(5, "minecraft:diamond_pickaxe", 1, {
    "Enchantments": [{"id": "minecraft:efficiency", "lvl": 5}],
    "display": {"Name": "Super Pickaxe"}
})
```

**Events and Decorators**:
- Use `@event("tick")` to register a function run every tick. The runtime spawns a new thread for the handler.
- Handlers may call `wait()` to suspend and resume later.
- Example heartbeat handler:

```python
@event("tick")
def heartbeat():
    log("heartbeat for " + get_name(player))
    wait(20)
```

**Example Scripts**:
- Heartbeat with live position tracking:

```python
@event("tick")
def show_position():
    log("Player at: x=" + str(pos["x"]) + ", y=" + str(pos["y"]) + ", z=" + str(pos["z"]))
    wait(20)
```

- OS Shell command example (requires `run_commands=true` in settings):

```python
# Execute PowerShell command and log result
result = run_os("Get-Date -Format 'HH:mm:ss'")
say("Server time: " + result)

# Execute OS command in a loop
@event("tick")
def monitor_disk():
    disk_info = run_os("Get-Volume | Select-Object DriveLetter, SizeRemaining | Format-Table -AutoSize")
    log("Disk status: " + disk_info)
    wait(200)  # Check every 200 ticks
```

- Echo + input example (`demo.pms`):

```python
say("hello " + get_name(player))
nickname = input("nickname", "builder")
say("input nickname=" + nickname)

def spawn_mob():
    summon("minecraft:zombie", [pos["x"] + 1, pos["y"], pos["z"]])

@event("tick")
def heartbeat():
    log("heartbeat for " + get_name(player))
    wait(20)

spawn_mob()
```

**Debugging & Development Tips**:
- Use `log()` liberally for server-side debugging.
- Keep heavy work broken into `wait()` slices to avoid hitting per-tick instruction limits.
- Use `input()` to parameterize scripts run via `/pyscript run <module> key=value`.

**Command Usage (admin)**:
- `/pyscript list` — list `.pms` files.
- `/pyscript run <module> [inputs]` — run a module. Inputs are passed to `input()`.
- `/pyscript load <file>` — load and execute file.
- `/pyscript reload` — clear module cache.
- `/pyscript stop` — stop all running scripts.

**Appendix: Slot mapping quick reference**
- UI slots 0..26 → inventory main grid (UI top-left → top-right, row-major)
- UI slots 27..35 → hotbar left→right

**Appendix: Permission Settings**

Create or edit `settings.properties` in the mod config directory to enable permissions:

```properties
# Allow OS shell execution (run_os function)
# Server commands (run, set_gamemode, etc.) are always allowed
run_commands=true

# Allow reading/writing files
read_file=true
write_file=true

# Allow clipboard access
copy=true
paste=true
edit_clipboard=true

# Allow editing inventory slots (edit_inventory)
edit_inventory=true
```

**Notes**:
- **Default** (no file or all false): Full sandbox, only game/player/world operations allowed.
- **Server commands** are always available - they run Minecraft commands directly, so they're already restricted by Minecraft permissions.
- **OS shell access** is dangerous and requires explicit `run_commands=true` permission.

**Where to put scripts**
- Place `.pms` files in the script root (created by mod; see `PyScriptMod` logs for the exact directory). Use `/pyscript create <name>` to create a new file quickly.

**Want improvements?**
- If you need exact vanilla NBT fidelity or complex list handling in `edit_inventory`, I can extend the bridge to use the appropriate `NbtCompound` APIs for your target Minecraft mappings. Open an issue or ask me to implement that enhancement.

---
Generated by taolaaihahaha — concise SDK for PyScript usage.