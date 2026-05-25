# PyScript Mod (Fabric)

A Python-like scripting mod for Minecraft Fabric that lets players control world, entities, and players using custom `.ppy` scripts compiled to `.cpy`.

## Ownership & Disclaimer

- Owner: **taolaaihahaha**
- This repository is **100% AI-coded** for experimentation.
- Bugs may still exist. If you find one, please report it with script + command + `latest.log`.

## Current Status

This project is MVP-first and runnable:

- Script loading/compiling/runtime execution
- Tick/event execution
- Minecraft bridge built-ins
- Sandbox settings for OS/file/clipboard actions
- Stop/reload controls for running scripts

## Commands

- `/pyscript create <name>`
  - Creates a script in `.minecraft/huh`
  - Adds `.ppy` automatically if missing
  - Fails if the file already exists

- `/pyscript list`
  - Lists `.ppy` and `.cpy` files in `.minecraft/huh`

- `/pyscript load <name-or-full-path> [k=v,k2=v2]`
  - Full path: loads directly
  - Name only: searches inside `.minecraft/huh` (prefers `.cpy`, then `.ppy`)

- `/pyscript reload`
  - Reloads script cache so file edits are picked up

- `/pyscript stop`
  - Stops all running script threads and handlers

- `/pyscript stop <module>`
  - Stops only one running module

- `/pyscript settings sandbox`
  - Shows sandbox flags

- `/pyscript settings sandbox <key> <true|false>`
  - Toggles one sandbox gate

## Runtime Notes

- Dynamic context (`player`, `pos`, `world`, `dimension`, `target`) is resolved live at execution time.
- `wait(ticks)` pauses/resumes script threads.
- Event handlers run in server tick flow.

## Built-ins (Highlights)

- Chat/utility: `say`, `log`, `input`, `wait`
- World/entity: `summon`, `tp`, `kill`, `get_block`, `set_block`
- Player stats: `get_name`, `get_hp`, `set_hp`, `add_hp`, `get_mana`
- Inventory/equipment: `get_inventory`, `get_equipment`, `get_left_hand`, `edit_inventory`
- State/control: `get_xp`, `set_xp`, `get_gamemode`, `set_gamemode`, `get_difficulty`, `set_difficulty`
- Server command: `run("...")`
- Advancements: `get_advancements`, `add_advancement`

## Peaceful Difficulty Behavior

In `peaceful`, vanilla may remove hostile mobs after spawn. Script execution still continues (no hard runtime failure).

## File Formats

- Source: `.ppy`
- Compiled: `.cpy`

## Build

```bat
.\gradlew.bat build --no-daemon
```

Output jar:

`build/libs/pyscriptmod-0.1.0.jar`

## Bug Report Template

Please include:

1. Script content
2. Exact `/pyscript ...` command
3. Minecraft + Fabric Loader version
4. `latest.log`
