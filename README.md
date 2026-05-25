# PyScript Mod MVP

## Ownership & Disclaimer

- Owner: **taolaaihahaha**
- This project is **100% AI-coded** for experimentation ("xem no lam duoc gi").
- Mod co the van con bug. Neu ban gap bug, vui long bao lai kem `latest.log` va script dang chay.
- Known behavior: trong difficulty `peaceful`, summon hostile mob co the bi game xoa ngay (co the xem nhu feature vanilla behavior).

## 1. Muc tieu

PyScript Mod la mot Fabric mod theo huong "Python-like scripting engine" cho Minecraft.
Nguoi choi viet script bang syntax gan Python de dieu khien world, block, entity, player va event.
Runtime khong nhung Python that; no dung lexer, parser, AST, compiler IR va bytecode-like VM tu thiet ke.
Script co the chay top-level, resume theo `wait(ticks)`, va dang ky event `tick`.
Context mac dinh gom `player`, `world`, `dimension`, `pos`, `target`, va `inputs`.
Built-in API bridge sang Minecraft duoc dong goi tai `FabricMinecraftBridge`.
MVP uu tien "chay duoc truoc": command `/pyscript run <module>`, folder script, event tick, built-ins co ban.
Kien truc duoc tach ro de sau nay mo rong them module, permission profile, scheduler, va native debug tooling.

## 1.1 Command usage

Script folder runtime la `.minecraft/huh`.
Source script dung duoi `.ppy`.
Compiled script dung duoi `.cpy` (IR noi bo + gzip, khong giu source text).

- `/pyscript create <ten_file>`
  - Tao file moi trong `.minecraft/huh`.
  - Neu ten khong co duoi mo rong, mod tu them `.ppy`.
  - Neu da ton tai, command bao loi.
- `/pyscript list`
  - Liet ke tat ca file `.ppy/.cpy` dang co trong `.minecraft/huh` (de quy).
- `/pyscript load <ten_or_full_path> [k=v,k2=v2]`
  - Neu la full path absolute: load truc tiep file do.
  - Neu chi la ten: uu tien tim `.cpy`, neu khong co thi tim `.ppy` trong `.minecraft/huh`.
  - Neu khong tim thay: bao loi.
- `/pyscript stop`
  - Dung tat ca script thread va event handlers dang chay.
- `/pyscript stop <ten_file>`
  - Dung rieng module do, khong anh huong module khac.

## 2. Kien truc tong the

```text
[Player Command /pyscript run demo]
                |
                v
        [PyScriptCommand]
                |
                v
      [ScriptRuntime.runModule]
                |
                v
        [ScriptModuleLoader]
                |
                v
     source text (*.pms in config/)
                |
                v
      [Lexer] -> [Parser] -> [AST]
                |
                v
          [Compiler -> IR]
                |
                v
       [VM / ScriptRuntime]
          |             |
          |             +--> [Event Registry] <--- server tick
          |
          +--> [FabricMinecraftBridge] ---> Minecraft server/world/entity/block API
                          |
                          v
                   server-thread actions
```

### Luong xu ly

1. Player chay `/pyscript run <module>`.
2. `PyScriptCommand` tao `FabricMinecraftBridge` tu `ServerCommandSource`.
3. `ScriptModuleLoader` doc file `.ppy/.cpy` trong `.minecraft/huh`.
4. `Lexer` token hoa indentation-based syntax.
5. `Parser` dung AST gom statement/expression/function/decorator.
6. `Compiler` chuyen AST thanh IR stack-based.
7. `ScriptRuntime` tao `ScriptInstance`, bind context (`player`, `world`, `dimension`, `pos`, `target`, `inputs`).
8. VM thuc thi top-level. Khi gap built-in Minecraft, no di qua `FabricMinecraftBridge`.
9. Khi gap `wait(ticks)`, VM pause thread va scheduler resume o tick sau.
10. Khi gap `@event("tick")`, function duoc dang ky vao event registry va duoc goi o `END_SERVER_TICK`.

### Threading

- `Lexer`, `Parser`, `Compiler`, doc file: co the chay async trong ban sau, nhung MVP dang chay dong bo khi player start script.
- Toan bo thao tac world/entity/block/player: bat buoc server thread.
- `tick()` va event dispatch: server thread.
- Cach tranh lag trong MVP:
  - moi script thread co `step budget` per tick;
  - `wait()` cho phep tach cong viec dai thanh nhieu tick;
  - tick event co `busy flag`, khong reentrant de tranh pile-up.

### Sandbox / permission

- `say`, `log`, `get_*`, `input`, `range`, `len`, `wait`: mo mac dinh.
- `summon`, `tp`, `give`, `kill`, `set_block`: require `permission level >= 2`.
- Nguon command phai la player. Console/command block bi tu choi trong MVP.

### Storage

- `CompiledModule` cache trong `ScriptModuleLoader`.
- `globals` per script instance.
- `locals` per call frame.
- `subscriptions` luu event handler dang ky.
- `waiting threads` giu stack/call frames de resume sau `wait()`.

## 3. Syntax ngôn ngữ

### Supported subset in MVP

- indentation 4 spaces
- `def`
- `if / elif / else`
- `for name in range(...)`
- `for name in list_or_range`
- `while`
- `return`
- `break / continue`
- `import module`
- list literal, dict literal
- call, indexing `[]`, attribute `.`
- decorator `@event("tick")`

### Chua ho tro trong MVP

- class
- closure / nested function
- assignment vao `list[i]` hoac `obj.x`
- try/except
- generator
- tuple
- multi-line expression phuc tap
- import chua ho tro `wait()` trong top-level imported module

### Vi du syntax

```python
nickname = input("nickname", "builder")
hp = get_hp(player)

if hp < 10:
    say("Low HP")
elif hp < 20:
    say("Careful")
else:
    say("Healthy")

for i in range(0, 3):
    say("tick=" + i)

@event("tick")
def heartbeat():
    log("pulse")
    wait(20)

def spawn_once():
    summon("minecraft:zombie", [pos["x"] + 1, pos["y"], pos["z"]])
```

## 4. Built-in functions

- `say(msg)`: gui chat global.
- `summon(entity_id, pos=None)`: spawn entity tai vi tri hien tai hoac vi tri duoc chi dinh.
- `tp(target, x, y, z)`: dich chuyen entity/player.
- `give(target, item, amount)`: dua item cho player.
- `kill(target)`: kill entity/player.
- `get_block(x, y, z)`: tra ve block info object co `id`.
- `set_block(x, y, z, block)`: dat block.
- `get_player(name=None)`: lay player hien tai hoac player theo ten.
- `wait(ticks)`: pause VM thread va resume sau N tick.
- `log(msg)`: ghi log server + feedback command source.
- `input(name, default=None)`: lay input tu command `/pyscript load demo key=value`.
- `range(...)`: tao list integer.
- `len(value)`: do dai string/list/dict.
- `get_name(player_or_entity)`: lay ten.
- `get_hp(player_or_entity)`: lay HP.
- `get_mana(player)`: doc scoreboard objective `mana`, khong co thi 0.
- `on(event, handler)`: dang ky event bang function thay vi decorator.

## 5. mod

### Cau truc repo

```text
build.gradle
gradle.properties
settings.gradle
src/main/java/dev/codex/pyscriptmod/
  PyScriptMod.java
  PyScriptCommand.java
  bridge/
  script/
src/main/resources/fabric.mod.json
example-scripts/demo.pms
```

### Diem thiet ke chinh

- `script/lang/*`: lexer/parser/AST.
- `script/ir/*`: compiler sang IR.
- `script/runtime/*`: VM, scheduler, builtins, event registry.
- `bridge/*`: mapping runtime <-> Minecraft API.
- `PyScriptCommand`: entrypoint cho player.

## 6. Script mau

```python
say("hello " + get_name(player))

nickname = input("nickname", "builder")
say("input nickname=" + nickname)

if get_hp(player) < 20:
    say("hp below full")
else:
    say("hp ok")

def spawn_mob():
    summon("minecraft:zombie", [pos["x"] + 1, pos["y"], pos["z"]])

@event("tick")
def heartbeat():
    log("heartbeat for " + get_name(player))
    wait(20)

spawn_mob()
```

## 7. Gioi han ky thuat ban dau

- Cau hinh Fabric dang target `1.21.1` do `1.21.11` khong phai version Minecraft chuan.
- VM chua co debugger, stack trace dep, hay source map.
- `and/or` chua short-circuit; hien tai evaluate ca 2 ve.
- Import chi la internal module `.pms`; khong co package init.
- Khong co persistent variable store qua server restart.
- `world` hien tai duoc expose toi script o dang binding co ban, chua phai object API day du.
- Event MVP moi co `tick`; them `join`, `use_item`, `block_break` la buoc tiep theo.

## 8. Roadmap 3 giai doan

### MVP

- command `/pyscript run` va `/pyscript reload`
- lexer/parser/AST/compiler/VM
- built-ins co ban
- `wait(ticks)` + `tick` event
- permission gate don gian

### Ban mo rong

- them event `player_join`, `entity_death`, `block_break`, `use_item`
- import namespace day du
- persistent storage theo world/player
- parser error message + line mapping tot hon
- asynchronous precompile/cache invalidation

### Ban nang cao

- debugger, profiler, quota CPU/tick
- typed foreign object wrappers
- bytecode verifier / sandbox policy chi tiet
- hot-reload module dang chay
- language service: formatter, syntax highlight, LSP-like hints
