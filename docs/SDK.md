# PyScript SDK

## Script formats

- Source: `.ppy`
- Compiled: `.cpy` (internal IR payload, gzip + serialized module)

Load policy:

1. `load <name>` prefers `<name>.cpy`
2. Fallback to `<name>.ppy`
3. If source is newer, runtime recompiles and refreshes `.cpy`

## Runtime stop control

- `/pyscript stop` -> stop all active threads and event handlers
- `/pyscript stop <module_or_file>` -> stop only matching module thread/handlers

## Native backend status

- Current backend: Java VM interpreter over internal IR.
- Planned backend: `.cpy -> C/C++ AOT/JIT` as an optional execution engine.
- Not enabled yet in this build.
