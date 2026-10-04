# Cyberblock wire protocol v1 and shared-memory frame

Contract between the Minecraft mod (`mc/`), `Cyberblock.dll` (`cp/plugin/`) and the test tools (`tools/`).
Change it only through the integrator. Coordinate conversion lives in `tools/cbmath.py`:
- **Positions** go through the world's vertical alignment `off = k/64` m (`k` an integer 0..63, see
  [Ground alignment](#ground-alignment-re-test-2)): Cyberpunk `(x, y, z)`, Z-up → Minecraft `(x, z − off, −y)`; back:
  Minecraft `(x, y, z)` → Cyberpunk `(x, −z, y + off)`.
- **Directions, velocities, impulses and angles** never get the offset: Cyberpunk `(x, y, z)` → Minecraft `(x, z, −y)`;
  back `(x, −z, y)`.

Everything on the wire is already in Minecraft convention, in both directions. Parts marked **re-test 2** come from
`docs/superpowers/plans/2026-10-03-retest2-and-cyberware.md` §1 (ground alignment, flight launches and the DLL drive,
cyberware, four binds). Parts marked **re-test 3** come from `docs/superpowers/plans/2026-10-03-retest3-polish.md` §1
(distinct quickhacks, Minecraft-drawn UI, vehicles, six binds); they are collected in
[Re-test 3](#re-test-3-minecraft-drawn-ui-distinct-quickhacks-seat-six-binds).

## Wire protocol v1 (WebSocket on `127.0.0.1:25600`, Minecraft is the server)

Messages are JSON text frames, one object per frame, discriminated by `t`.

### Cyberpunk → Minecraft (all values already Minecraft convention)
| `t` | fields | when |
|---|---|---|
| `hello` | `proto:1, game:"cp2077", build:"2.31"` | on connect |
| `cam` | `f` frame (long), `p:[x,y,z]` camera, `r:[yaw,pitch,roll]` deg, `fov` vertical deg, `fp:true`, `pl:[x,y,z]` feet, `h` body yaw deg | every Cyberpunk frame while active (layers 3–6 add `cr`, see below) |
| `ground` | `c:[x,z,yBottom,yTop, …]` inclusive | probe deltas (new solid ranges) |
| `unground` | `c:[x,z,yBottom,yTop, …]` | probe deltas (ranges no longer solid / out of radius) |
| `clear` | — | on activate, deactivate, teleport > 64 m |
| `state` | `active:bool, vehicle:bool` | on change |
| `view` | `w, h` | on connect and when Cyberpunk's backbuffer size changes |
| `aim` | `d` metres from the camera to the Night City surface under the crosshair, `-1` if none within 8 m | on change (2 cm), 10 Hz; Minecraft caps block targeting at `d` |
| `hud` | `hidden:bool` | on change (hidden in vehicles; **re-test 3:** the DLL now always sends `hidden:false`, see [Re-test 3](#re-test-3-minecraft-drawn-ui-distinct-quickhacks-seat-six-binds)) |
| `key` | `k` ∈ `use, attack, pick, inventory, drop, swap, chat, command, escape`, `down:bool` | input (layers 3–6 add more values of `k`, see below) |
| `slot` | `n` 0–8 | number keys |
| `scroll` | `d` int (wheel notches, +1 = up) | wheel |
| `cursor` | `x, y` in 0..1 of the window | only while `status.screen` |
| `click` | `b` 0 left, 1 right, 2 middle, `down:bool` | only while `status.screen` |
| `dmg` | `frac` 0..4 (fraction of V's max health the hit would have taken), `kind` ∈ `bullet, explosion, melee, fall, vehicle, fire, other`, `from:[x,y,z]` or `null` | each damage event on V while active (layer 2) |
| `npcs` | `list:[{id:"<uint64 as decimal string>", p:[x,y,z] feet, yaw, kind:"human"\|"vehicle", hostile:bool, dead:true (optional)}]` | 10 Hz while active: everything alive within 48 m, nearest first, at most 48 entries; an id missing from the list is gone (layer 2). Phase 3: an NPC that died (or is defeated) or a vehicle that was destroyed, whoever caused it, is listed once more after the living with `dead:true` (its last sighting; the key is only ever `true` and absent otherwise), and Minecraft leaves a body for 3 s or a wreck for 5 s; `killed` stays the report of V's own kills (XP) |

### Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `hello` | `proto:1, mod:"cyberblock", mc:"26.3"` | on connect |
| `status` | `hp, maxHp, food, gm` (`survival`/`creative`/`adventure`/`spectator`), `screen:bool` | 10 Hz and on change |
| `block` | `op:"add"\|"remove"`, `pos:[bx,by,bz]` | the player or an explosion places/removes a solid non-barrier block (layer 2) |
| `explosion` | `pos:[x,y,z]`, `power` (Minecraft radius: TNT 4, creeper 3, charged creeper 6, firework 1.5), `fire:bool` | every explosion in the Minecraft world while the host is attached (layer 2) |
| `hit` | `target:"<id>"`, `dmg` (Minecraft health points after armour), `kind` ∈ `melee, arrow, trident, mob, explosion, fire`, `impulse:[x,y,z]` m/s | a proxy takes damage (layer 2; layers 3–6 add `mob`, see below) |
| `ignite` | `target:"<id>"`, `seconds` | a proxy is set on fire: fire charge, flint & steel, lava, fire aspect, flaming arrow (layer 2) |

Unknown `t` values and unknown fields are ignored by both sides (forward compatibility; `proto` stays 1 for layers 2–6).

### Layer 2 semantics and limits
Both receivers validate every number and drop a bad message or list entry without crashing or disconnecting.
- **Ids** (`npcs[].id`, `hit.target`, `ignite.target`) are the Cyberpunk entity id hash as a decimal string
  (unsigned 64 bit; it does not fit a JSON double). A `hit` or `ignite` for an id that is not (or no longer) a proxy
  is ignored.
- **Proxies**: Minecraft keeps one invisible proxy entity per id in the last `npcs` list, at feet position `p` facing
  `yaw`; humans are 0.6 × 1.8 m, vehicles 2.0 × 1.6 m. Damage to a proxy never hurts it in Minecraft: it is sent as `hit`.
- **Positions and vectors** are finite with `|coordinate| < 3e7`; `block.pos` holds integers. `dmg.from` is the
  attacker's position, `null` when unknown. `hit.impulse` is a velocity in m/s.
- **Ranges**: `dmg.frac` 0..4, `explosion.power` 0..20, `hit.dmg` 0..1000, `ignite.seconds` 0..120.
- **Scaling** (applied once, on the receiving side): `dmg.frac` → Steve loses `frac × Steve's max health`
  (Minecraft side); `hit.dmg` → the target loses `dmg / 20` of its max health (redscript side).
- **Block cells** are Minecraft cells; the DLL maps a block `(bx, by, bz)` to the Cyberpunk box
  `(bx, -(bz+1), by + k/64)` … `(bx+1, -bz, by + 1 + k/64)` (`tools/cbmath.py` `mc_block_to_cp_aabb`, with the
  world's alignment `k` of the [Ground alignment](#ground-alignment-re-test-2) section; `k = 0` is the old
  `(bx, -(bz+1), by)` … `(bx+1, -bz, by+1)`).

### Examples
One message per line (JSON Lines), one line per `t` in the tables above.

Cyberpunk → Minecraft:
```jsonl
{"t":"hello","proto":1,"game":"cp2077","build":"2.31"}
{"t":"cam","f":1042,"p":[-1245.5,66.62,812.25],"r":[150.0,12.5,0.0],"fov":58.7,"fp":true,"pl":[-1245.5,64.9,812.25],"h":150.0}
{"t":"ground","c":[10,-21,3,4,-4,7,12,12]}
{"t":"unground","c":[10,-21,3,4]}
{"t":"clear"}
{"t":"state","active":true,"vehicle":false}
{"t":"view","w":2560,"h":1440}
{"t":"aim","d":2.437}
{"t":"hud","hidden":false}
{"t":"key","k":"attack","down":true}
{"t":"slot","n":2}
{"t":"scroll","d":-1}
{"t":"cursor","x":0.5,"y":0.5}
{"t":"click","b":0,"down":true}
{"t":"dmg","frac":0.25,"kind":"bullet","from":[-1250.5,66.6,805.0]}
{"t":"npcs","list":[{"id":"9007199254740993","p":[-1243.0,64.9,808.5],"yaw":90.0,"kind":"human","hostile":true},{"id":"18446744073709551615","p":[-1240.25,64.9,815.0],"yaw":-45.0,"kind":"human","hostile":false},{"id":"4503599627370497","p":[-1252.0,64.9,820.5],"yaw":180.0,"kind":"vehicle","hostile":false}]}
```

Minecraft → Cyberpunk:
```jsonl
{"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"}
{"t":"status","hp":20,"maxHp":20,"food":20,"gm":"survival","screen":false}
{"t":"block","op":"add","pos":[10,4,-21]}
{"t":"explosion","pos":[10.5,4.0,-20.5],"power":4.0,"fire":false}
{"t":"hit","target":"9007199254740993","dmg":7.0,"kind":"melee","impulse":[0.0,1.5,6.0]}
{"t":"ignite","target":"9007199254740993","seconds":8.0}
```

## Layers 3–6 (mobs, flight, items, Cyberpunk powers)

Same wire, same conventions (everything is already Minecraft convention, in both directions); `proto` stays 1. Plan:
`docs/superpowers/plans/2026-10-03-layers3-6-complete-mod.md` §1. Minecraft is the authority for everything on the
Minecraft side (perks, RAM, items, gliding physics, mob AI); Cyberpunk is the authority for Night City (NPCs, devices,
V's body and camera). The receiver checks every limit below: a bad message (or a bad list entry) is dropped and counted,
and never ends the link.

### Layers 3–6: Cyberpunk → Minecraft
Rows marked **extends** add to a layer 1/2 message of the same `t`; the old fields stay as they were.

| `t` | fields | when |
|---|---|---|
| `cam` | **extends layer 1:** adds `cr:bool` (V is crouched), `air:bool` (V jumps or falls), `held:bool` (V is in a vehicle, or held by a quest scene of tier 2–5, a workspot, a braindance or a moving elevator), all from `Cyberblock_SetStance`; `menu:bool` (a Cyberpunk menu is open: input forwarding pauses, `Cyberblock_SetMenu`); `fly:bool` (V follows Steve's glide **or launch**; **re-test 2:** the DLL's per-frame drive sets it on the first frame it moves V and clears it when the flight ends, it is no longer a script native; Minecraft calls a flight off that `fly` does not confirm within 0.5 s); **re-test 2:** `lv:int` 0..63 (the alignment `k` the DLL converted this `cam` with; absent from the fake host and older hosts: Minecraft then assumes its current `k`) | every frame (each flag carried until it changes) |
| `key` | **extends layer 1:** `k` also ∈ `jump`, `sprint` (Space and either Shift: forwarded, **not** swallowed: Cyberpunk keeps them), `perspective` (F5, swallowed), `hack`, `skills`, **re-test 2:** `cyberware`, `activate` (the keys from `binds`, swallowed while forwarding); `swap` is X; `down:bool` as before; adds `mods` (SDL keymod bits held: 1 Shift, 64 Ctrl; Ctrl+Q drops a whole stack) | input while forwarding |
| `click` | **extends layer 1:** adds `mods` (as `key`: Shift-click quick-moves a stack) | only while `status.screen` |
| `char` | `c` a Unicode code point (≥ 0x20, never 0x7F; UTF-16 surrogate pairs joined) | text typed while `status.screen` (chat, signs, anvil, book) |
| `skey` | `sc` SDL scancode of an editing key (Enter 40, Backspace 42, Tab 43, Home 74, Page Up 75, Delete 76, End 77, Page Down 78, Right 79, Left 80, Down 81, Up 82), `mods` | each press and auto-repeat while `status.screen` |
| `mobdmg` | `id` (the mob: Minecraft network entity id, int ≥ 0), `frac` 0..4 (fraction of the puppet's max health), `by:"<npc id>"` or `null` | a Cyberpunk NPC damaged a mob's puppet |
| `pcells` | `c:[x,y,z, …]` integer block cells, at most 64 cells | Night City surfaces on predicted projectile paths: Minecraft makes them temporary barriers for 3 s |
| `unlead` | `target:"<id>"` | Cyberpunk gave up a tether (target gone, or snapped: more than length + 12 m for 2 s) |
| `scaninfo` | `id`, `ok:bool`, `target:"<id>"`, `name` (≤ 64 chars), `faction` (≤ 64), `threat` ∈ `none, low, medium, high, extreme`, `hp` 0..1, `device:bool`, `p:[x,y,z]` (feet / device base), `size:[w,h]` m | reply to every `scan` (`ok:false` with empty strings when nothing is there) |
| `hackresult` | `id`, `ok:bool`, `reason` (≤ 64 chars, `""` on success) | reply to every `hack` |
| `killed` | `target:"<id>"`, `kind:"human"\|"vehicle"`, `hostile:bool` | an NPC or vehicle that V (Cyberblock) damaged died or was destroyed |
| `look` | `d:[dyaw, dpitch]` degrees | only after `Cyberblock_SetLookCapture(true)`, ≤ 120 Hz. **Re-test 2:** nothing calls it any more (the camera-only flight fallback is gone); the native and this message stay for a follow-up |

### Layers 3–6: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `hit` | **extends layer 2:** adds `mob:int` (the Minecraft entity id of the mob that dealt a `kind:"mob"` or mob `arrow` hit; absent otherwise) | as layer 2 |
| `blocks` | `reset:bool`, `c:[x,y,z, …]` ≤ 64 cells, all solid non-barrier blocks of the player | after each host `hello`: first message `reset:true` (it may have an empty `c`), then the rest at ≤ 5 messages/s |
| `mobs` | `list:[{id, kind:"zombie", p:[x,y,z] feet, yaw, w, h, hp, maxHp}]`, ≤ 12 entries, nearest first | 10 Hz while active: hostile goal-driven mobs within 48 m of Steve. One empty list after the last one goes |
| `flight` | `active:bool`, `p:[x,y,z]` feet, `v:[x,y,z]` m/s, `yaw`, `pitch`; **re-test 2:** `kind` ∈ `glide`, `launch` (anything else or absent = glide), `ts` (Minecraft's monotonic sample clock in ms, `(System.nanoTime() − Flight.EPOCH) / 1e6`, finite, 0..1e13; a bad `ts` is ignored, not rejected) | every client frame (≤ 120 Hz) while Steve is driving (glide or launch). One `active:false` at touchdown, with `p` = landing spot |
| `teleport` | `p:[x,y,z]` feet, `yaw` | an ender pearl of Steve's landed |
| `proj` | `list:[{id, p:[x,y,z], v:[x,y,z] m/s, g}]`, ≤ 16 entries | 10 Hz while a projectile Steve owns flies (pearl, arrow, trident, firework, potion, wind charge, fishing hook). One empty list after |
| `lead` | `target:"<id>"`, `anchor:[x,y,z]` or `null` (`null`: Steve holds it), `length` m (1..32) | a proxy was leashed |
| `lead` | `target:"<id>"`, `release:true` | the leash was removed, broken or dropped |
| `pull` | `target:"<id>"`, `toward:[x,y,z]`, `strength` m/s (0..50) | a fishing rod reeled in a hooked proxy |
| `knock` | `pos:[x,y,z]`, `radius` m (0..16), `strength` m/s (0..50) | a wind charge burst (sent **instead of** `explosion`) |
| `effect` | `name` (Minecraft effect path, `[a-z_]{1,32}`), `seconds` 0..86400 (0 = removed; infinite = 86400), `amp` 0..255 | a status effect on Steve was added, changed or removed; the full current set again on each host `hello` |
| `scan` | `id`, `target:"<id>"` or `null` (`null`: whatever V looks at) | the hack key targets something (≤ 5/s) |
| `hack` | `id`, `target:"<id>"`, `hack` (a hack name, below), `chain:bool` | a quickhack item was used on the locked target |
| `perks` | `list:[{id, rank}]`: all 20 perk ids (below), `rank` 0 or 1 | after each host `hello`, and on every purchase |
| `perkfx` | `id:"sandevistan"`, `seconds` 0..30 | Sandevistan Sprint fired. **Superseded by `cyberfx`:** Minecraft no longer sends it, the DLL still accepts it |
| `binds` | `keys:{hack:<vk>, skills:<vk>, cyberware:<vk>, activate:<vk>}` Windows virtual-key codes 1..254. **Re-test 2:** the two new names; a missing name keeps the current key (older Minecraft builds) | after each host `hello`, and when rebound in Minecraft's controls |
| `align` | **re-test 2:** `k` integer 0..63 (the world's ground alignment, [below](#ground-alignment-re-test-2)) | from the server thread on world attach while linked, and on every change, **immediately followed** by a block resync (`blocks reset:true`, same thread). From the link thread on each host `hello` while a world is attached |
| `cyberware` | **re-test 2:** `list:[{id, on}]`, `id` one of the 17 cyberware ids (below), `on` 0 or 1, at most 32 entries | after each host `hello`, on join, after every install or removal; every `on` 0 on disconnect. Kept while the host is not Active |
| `cyberfx` | **re-test 2:** `id` ∈ `sandevistan`, `kerenzikov`, `berserk`, `seconds` 0..30 (0 = end it now) | an implant effect starts (transient: discarded while the host is not Active). It replaces Minecraft's use of `perkfx` |

### Layers 3–6 semantics and limits
- **Ids:**
  - Cyberpunk entities (NPC, vehicle, device): the EntityID hash as a decimal string, as in layer 2 (`0` means none).
  - Minecraft mobs (`mobdmg.id`, `mobs[].id`, `hit.mob`): the network entity id, an int ≥ 0.
  - Projectiles (`proj[].id`): an int ≥ 0.
  - Request ids (`scan.id`, `hack.id`, echoed in `scaninfo.id` and `hackresult.id`): an int ≥ 0 chosen by Minecraft.
- **Units:** positions are Minecraft cells and metres, velocities and impulses are m/s along the Minecraft axes,
  `proj.g` is a scalar in m/s² pointing down. **Re-test 2: positions are offset, velocities and directions are not.**
  The DLL maps positions with `mc_to_cp_pos(x, y, z, off)` (Minecraft `(x, y, z)` → Cyberpunk `(x, -z, y + off)`),
  velocities and impulses with `mc_vel_to_cp` (Minecraft `(x, y, z)` → Cyberpunk `(x, -z, y)`, written out, no offset),
  and the other way with `cp_to_mc_pos(…, off)` for positions and `cp_dir_to_mc` for directions
  (the [Ground alignment](#ground-alignment-re-test-2) section lists every field). Yaw on the wire is Minecraft yaw
  (0 = facing +Z, degrees); the DLL turns it into Cyberpunk yaw with `mc_yaw_to_cp_yaw` (`a = 180 − m`, normalised to
  (−180, 180]). Pitch is Minecraft pitch (positive = down) both ways. `tools/cbmath.py` is the source of truth;
  `shared/vectors.json` holds the `mc_yaw`, `mc_vel`, `mc_pos`, `mc_block`, `down`, `wall` and `align` vector sets.
- **Limits** (checked by the receiver):
  - Every position and vector is finite with `|c| < 3e7`; `flight.v` and `proj[].v` have length ≤ 200 m/s.
  - `proj[].g` is 0..40; `mobs[].hp` and `maxHp` are 0..2048; `mobs[].w` and `h` are 0..16;
    `mobs[].kind` matches `[a-z0-9_]{1,32}`.
  - `mobdmg.frac` is 0..4; `scaninfo.hp` is 0..1; `scaninfo.threat` is one of the five listed values.
  - `pcells.c` and `blocks.c` hold whole triples of integers, at most 64 cells per message; `mobs.list` at most 12
    entries and `proj.list` at most 16.
  - `lead.length` is 1..32; `pull.strength` and `knock.strength` are 0..50; `knock.radius` is 0..16.
  - `effect.name` matches `[a-z_]{1,32}`, `effect.seconds` is 0..86400, `effect.amp` is 0..255; `perkfx.seconds` is 0..30.
  - `binds.keys` values are integers 1..254; `look.d` is two finite numbers.
  - `char.c` is a valid code point (Minecraft drops anything else); `skey.sc` is an SDL scancode 1..511; `mods` is an
    integer of SDL keymod bits.
  - `binds` (four names: `hack`, `skills`, `cyberware`, `activate`): both sides refuse W A S D C E Q T X F I M J H N B P V
    Z R, 1–9, F5, F9, Space, Shift, Ctrl and Esc for each of them (allowed: G K L O U Y 0 F1–F4 F6–F8 F10–F12;
    `shared/vectors.json` `binds`). A refused key falls back to that action's default: G (71), K (75), U (85) and Y (89).
    If any two of the four resolved keys are equal, **all four** go back to the defaults; a missing name keeps the
    current key (older Minecraft builds).
  - **Re-test 2:** `align.k` and `cam.lv` are integers 0..63; `cyberware.list` has ≤ 32 entries (the 17 ids are
    each sent once); `cyberfx.seconds` is 0..30; `flight.ts` is finite, 0..1e13; `flight.kind` is `glide` or `launch`.
  - `hack.hack` must be one of the hack names and every `perks[].id` one of the perk ids below; anything else drops the
    message (or the entry).
  - **Validation of the re-test 2 messages (receiver):**
    - `align.k` must be an integer 0..63; a bad message is counted and the old `k` is kept;
    - `cyberware[].id` must be one of the 17 cyberware ids and `on` 0 or 1; a bad entry is dropped, the others stay;
    - `cyberfx.seconds` must be finite, 0..30, and `cyberfx.id` one of the three effect ids; anything else drops the
      message;
    - `flight.ts` must be finite, 0..1e13: a bad `ts` is ignored, not rejected (the sample then counts as arriving when
      it arrived), and a `kind` that is not `launch` means a glide;
    - every other limit above is unchanged.
  - A receiver ignores every field of `scaninfo` except `id` and `ok` when `ok` is `false`.
- **Scaling** (applied once, on the receiving side): `mobdmg.frac` → the mob loses `frac × its max health` (Minecraft).
  `pull.strength` and `knock.strength` are speeds that Cyberpunk applies as given (humans: ragdoll impulse; vehicles:
  × vehicle mass).
- **Pairing and order:**
  - Every `scan` is answered by exactly one `scaninfo` and every `hack` by exactly one `hackresult`, with the same `id`.
  - After each host `hello` Minecraft sends `align` (while a world is attached; the DLL uses `k = 0` until it
    arrives), `perks`, `cyberware` and `binds`, and once the host is active after it (its `cam` frames arrive) `blocks`
    (first message `reset:true`) and the current `effect` set. Live `block` events keep their order after the resync
    starts.
  - **Re-test 2:** every change of `k` is an `align` **immediately followed by** a block resync (`blocks reset:true`, then
    the rest), from one thread, so no block is ever converted with the wrong `k`. An `align` takes effect for every
    message that follows it on the wire.
  - `mobs`, `proj` and `flight` are state streams: the latest message wins, and an empty `list` or `active:false` ends
    the state.
- **Delivery in the DLL:** while the host is not Active it keeps `blocks`, `perks`, `cyberware`, `scan` and `hack` and
  discards the other new Minecraft → Cyberpunk messages, like explosions and `cyberfx`; a `hello` clears the queue. Block changes are never dropped:
  the DLL keeps the last change per cell (a `reset` forgets the earlier ones) and hands them to the script after the
  other events.
- **Sending in the DLL:** the queue to a Minecraft that falls behind (or stops reading) is bounded. Only the newest
  `cam`, `npcs`, `aim`, `cursor` and `pcells` is kept (it replaces the queued one of its type), queued `look` deltas are
  summed, and a `clear` replaces the queued `ground`, `unground` and `clear`. Past 1024 queued messages the oldest one is
  dropped and counted in the DLL's 5 s log line; when that was a `ground`, `unground` or `clear`, the DLL starts the
  ground over with a `clear`. A send that cannot finish within 5 s ends the connection (it reconnects).

### Ground alignment (re-test 2)
Night City's floor is at arbitrary heights (Cyberpunk z), Minecraft blocks sit on whole numbers. A world therefore has an
alignment `k`, an integer 0..63, with `off = k/64` metres (exact in float32, double and JSON), and the rule is:

**Minecraft y = Cyberpunk z − off**

- **Who owns `k`:** Minecraft. It is saved in the world, sent as `align` (the Minecraft → Cyberpunk table above) and chosen so that the floor under V's feet
  maps just above a block boundary: `k = floorMod(floor((z − floor z) · 64 − 0.125), 64)` for V's feet `z` in Cyberpunk
  (`tools/cbmath.py` `align_k`, Java `GroundAlign.kOf`). The DLL never chooses `k`.
  - The aligned floor then maps to `N + e` with `e` in [1.95 mm, 17.6 mm), and its nearest-rounded top face is exactly
    `N`, so Steve's feet are never inside the barrier under him.
  - Minecraft aligns once, at the first activation of a world while V stands on the ground, and again on
    `/cyberblock level` (the ground V stands on now). Blocks placed under another `k` move by less than 1 m.
- **Before any `align`** has arrived, and after every host `hello`, the DLL uses **`k = 0`**. Every `cam` says which `k`
  it was converted with (`lv`); a `cam` without `lv` is taken at Minecraft's current `k`.
- **Ground ranges** (`ground`, `unground`; nearest rounding): a floor hit at `(x, y, z)` gives the column
  `(floor x, floor −y)` and the top solid block `top = floor(z − off + 0.5) − 1` (ties go up), as the range
  `[top − 1, top]`. A wall hit gives `b = floor(z − off)`. Noise of a millimetre in a hit does not change a range.
- **Which fields get the offset** (`tools/cbmath.py` and the DLL's `cbmath` follow this table):

| Minecraft → Cyberpunk: gets `+off` | Cyberpunk → Minecraft: gets `−off` | No offset, ever |
|---|---|---|
| block cells, `explosion.pos`, `teleport.p`, `lead.anchor`, `pull.toward`, `knock.pos`, `mobs[].p`, `proj[].p`, `flight.p` | `cam.p`, `cam.pl`, `dmg.from`, `npcs[].p`, `pcells` (floor of y − off), `scaninfo.p`, the probe centre, `ground`/`unground` ranges | `hit.impulse`, `flight.v`, `proj[].v`, camera facing and forward, the look quaternion, yaw and pitch, the `SetAim` distance |

The fixed cases every suite checks (Python, Java and C++; V's feet `z` → `k`):

| z | k |
|---|---|
| `6.907` | 57 |
| `6.001` | 63 |
| `0.0` | 63 |
| `-3.25` | 47 |
| `312.999` | 63 |
| `-0.0005` | 63 |
| `64.5` | 31 |

### Flight: the DLL drive, launches and landings (re-test 2)
Minecraft owns the physics of a **glide** (elytra) and, new in re-test 2, of a **launch** (a wind charge, a breeze charge
or a mace burst that throws Steve into the air; the launch ends in a glide when Steve opens the elytra). Cyberpunk only
follows.
- **Stream:** `flight` up to 120 Hz while Steve is driving, `kind` `glide` or `launch`, `ts` the sample clock. One
  `active:false` at touchdown, with `p` = the landing spot.
- **Clock:** with `ts`, the DLL maps a sample to its own clock (the offset is the minimum of arrival − `ts` over the last
  32 samples); without `ts` the arrival time counts.
- **Playout:** the DLL does not use the newest sample: it plays the stream out `D` behind, `D` = 1.5 × the median sample
  spacing, clamped to 8..60 ms, interpolates linearly between the two samples around that time, and extrapolates past
  the newest one by at most 50 ms (`v × Δt`).
- **Drive:** every rendered frame (from RED4ext's `OnUpdate`, not the 10 Hz script tick) the DLL teleports V to the
  played-out position, keeping V's facing, clipped against Night City Static and Terrain geometry (V stops 0.3 m short).
  It sets `cam.fly` on the first frame that moves V and clears it when the flight ends.
- **When V is not moved:** while the script gate is closed (V in a vehicle, held by a scene, workspot, braindance or
  elevator, locked, or Cyberblock off) the DLL never moves V; it stops without teleporting. A Cyberpunk menu freezes its
  timers. Minecraft calls off any flight that `fly` does not confirm within 0.5 s.
- **Landing:** at `active:false` the DLL puts V on the ground below the landing spot, never in mid-air. When the flight
  lasted under 0.3 s, or V is already within 1.5 m of the spot, V stays where it is.
- **No samples** (a Minecraft hitch without `active:false`): V is held for 1 s, then put on the ground below.

### Cyberware and keys (re-test 2)
- **`cyberware`** is the whole installed set: one entry per implant, in the fixed order of the names below. The DLL turns
  each entry into one event for the scripts (`cyber`: id and 0/1). An implant with `on:1` is installed in the player's
  ripperdoc slots in Minecraft.
- **`cyberfx`** is a transient effect an active implant starts: Sandevistan and Kerenzikov slow Night City (time
  dilation 0.2 and 0.3, V ignores it). Berserk applies Cyberpunk's berserk status only if the scripts verified that it is
  safe; otherwise it is Minecraft-side only and Cyberblock.log says `berserk: Minecraft side only`. `seconds:0` ends the
  effect now. A newer message for the same id replaces the earlier end.
- **Keys:** the `cyberware` key (default U) opens the Cyberware screen, the `activate` key (default Y) fires the selected
  implant on a tap and cycles the selection on a hold. Both come from Cyberpunk as `key` messages and are swallowed
  while forwarding. **Re-test 3 supersedes the tap and hold cycling:** `activate` now fires the Operating System implant,
  and two new keys `arms` and `camo` fire the Arms implant and Optical Camo (see [Re-test 3: keys](#re-test-3-keys-six-binds)).

### Layers 3–6 names
- **Hack names** (`hack.hack`) — `short_circuit`, `overheat`, `reboot_optics`, `cripple_movement`, `contagion`, `synapse_burnout`, `ping`, `memory_wipe`, `cyberpsychosis`, `device`
- **Perk ids** (`perks[].id`, in purchase order) — `iron_skin`, `gorilla_arms`, `painkiller`, `juggernaut`, `double_jump`, `sandevistan`, `quick_draw`, `dodge`, `tinkerer`, `smart_arrows`, `field_medic`, `demolitions`, `extra_ram`, `cheap_hacks`, `chain_hack`, `overclock`, `optical_camo`, `headhunter`, `cold_blood`, `silent_steps`
- **Cyberware ids** (`cyberware[].id`, re-test 2, the fixed order of the list) — `mantis_blades`, `gorilla_arms`, `monowire`, `projectile_launch_system`, `reinforced_tendons`, `fortified_ankles`, `lynx_paws`, `sandevistan`, `berserk`, `cyberdeck`, `kerenzikov`, `optical_camo`, `subdermal_armor`, `kiroshi_optics`, `biomonitor`, `second_heart`, `ram_upgrade`
- **Cyberfx ids** (`cyberfx.id`, re-test 2) — `sandevistan`, `kerenzikov`, `berserk`

### Layers 3–6 examples
One message per line (JSON Lines). Every `t` of the two layers 3–6 tables has at least one line; `lead` and `scan`
show both of their forms.

Layers 3–6 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"cam","f":1043,"p":[-1245.5,66.62,812.25],"r":[150.0,12.5,0.0],"fov":58.7,"fp":true,"pl":[-1245.5,64.9,812.25],"h":150.0,"cr":true,"air":false,"held":false,"menu":false,"fly":false,"lv":57}
{"t":"key","k":"hack","down":true,"mods":0}
{"t":"key","k":"cyberware","down":true,"mods":0}
{"t":"key","k":"activate","down":false,"mods":0}
{"t":"click","b":0,"down":true,"mods":1}
{"t":"char","c":233}
{"t":"skey","sc":42,"mods":0}
{"t":"mobdmg","id":412,"frac":0.5,"by":"9007199254740993"}
{"t":"pcells","c":[10,64,-21,11,64,-21,10,65,-21]}
{"t":"unlead","target":"9007199254740993"}
{"t":"scaninfo","id":7,"ok":true,"target":"9007199254740993","name":"Officer Test","faction":"NCPD","threat":"high","hp":0.8,"device":false,"p":[-1243.0,64.9,808.5],"size":[0.6,1.8]}
{"t":"hackresult","id":8,"ok":true,"reason":""}
{"t":"killed","target":"9007199254740993","kind":"human","hostile":true}
{"t":"look","d":[1.2,-0.45]}
```

Layers 3–6 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"hit","target":"9007199254740993","dmg":3.0,"kind":"mob","impulse":[0.0,1.0,3.0],"mob":412}
{"t":"blocks","reset":true,"c":[10,4,-21,11,4,-21,12,4,-21]}
{"t":"mobs","list":[{"id":412,"kind":"zombie","p":[-1241.5,64.9,810.0],"yaw":90.0,"w":0.6,"h":1.95,"hp":20,"maxHp":20},{"id":415,"kind":"skeleton","p":[-1238.0,64.9,806.5],"yaw":-135.0,"w":0.6,"h":1.99,"hp":14.5,"maxHp":20}]}
{"t":"flight","active":true,"kind":"launch","ts":81234.5,"p":[-1245.5,66.3,812.25],"v":[1.5,14.0,4.0],"yaw":-170.0,"pitch":25.0}
{"t":"teleport","p":[-1230.5,65.0,820.5],"yaw":45.0}
{"t":"proj","list":[{"id":501,"p":[-1244.0,66.0,811.0],"v":[10.0,4.0,20.0],"g":24.0},{"id":502,"p":[-1243.0,67.5,812.5],"v":[0.0,0.0,30.0],"g":0.0}]}
{"t":"lead","target":"9007199254740993","anchor":null,"length":5.0}
{"t":"lead","target":"9007199254740993","release":true}
{"t":"pull","target":"9007199254740993","toward":[-1245.5,66.5,812.25],"strength":9.0}
{"t":"knock","pos":[-1240.0,64.0,809.0],"radius":3.5,"strength":14.4}
{"t":"effect","name":"speed","seconds":30.0,"amp":1}
{"t":"scan","id":7,"target":"9007199254740993"}
{"t":"scan","id":9,"target":null}
{"t":"hack","id":8,"target":"9007199254740993","hack":"short_circuit","chain":false}
{"t":"perks","list":[{"id":"iron_skin","rank":1},{"id":"gorilla_arms","rank":0},{"id":"painkiller","rank":0},{"id":"juggernaut","rank":0},{"id":"double_jump","rank":1},{"id":"sandevistan","rank":0},{"id":"quick_draw","rank":0},{"id":"dodge","rank":0},{"id":"tinkerer","rank":0},{"id":"smart_arrows","rank":0},{"id":"field_medic","rank":0},{"id":"demolitions","rank":0},{"id":"extra_ram","rank":1},{"id":"cheap_hacks","rank":0},{"id":"chain_hack","rank":0},{"id":"overclock","rank":0},{"id":"optical_camo","rank":0},{"id":"headhunter","rank":0},{"id":"cold_blood","rank":0},{"id":"silent_steps","rank":0}]}
{"t":"perkfx","id":"sandevistan","seconds":5.0}
{"t":"binds","keys":{"hack":71,"skills":75,"cyberware":85,"activate":89}}
{"t":"align","k":57}
{"t":"cyberware","list":[{"id":"mantis_blades","on":1},{"id":"gorilla_arms","on":0},{"id":"monowire","on":0},{"id":"projectile_launch_system","on":0},{"id":"reinforced_tendons","on":1},{"id":"fortified_ankles","on":0},{"id":"lynx_paws","on":0},{"id":"sandevistan","on":1},{"id":"berserk","on":0},{"id":"cyberdeck","on":0},{"id":"kerenzikov","on":0},{"id":"optical_camo","on":1},{"id":"subdermal_armor","on":0},{"id":"kiroshi_optics","on":1},{"id":"biomonitor","on":0},{"id":"second_heart","on":1},{"id":"ram_upgrade","on":0}]}
{"t":"cyberfx","id":"sandevistan","seconds":6}
```

## Re-test 3 (Minecraft-drawn UI, distinct quickhacks, seat, six binds)

Same wire, same conventions; `proto` stays 1 and older builds ignore unknown `t` values and unknown fields. Plan:
`docs/superpowers/plans/2026-10-03-retest3-polish.md` §1.1. The principle: **Cyberpunk keeps the game logic and its own
input; Minecraft draws every piece of UI.** While Cyberblock is on, Cyberpunk streams UI data (stats, quest, pins, prompt,
choices, subtitles, calls, notifications, menu requests) and Minecraft renders it with its own font, sprites and colours.
Positions in the new messages use the same alignment rule as before (`cp_to_mc_pos(…, off)`); directions get no offset.

### Re-test 3: Cyberpunk → Minecraft
Rows marked **extends** add to an earlier message of the same `t`; the old fields stay as they were. Text limits are in
**UTF-8 bytes**: the DLL cuts a string at the limit on a character boundary and strips control characters (below 0x20 and
0x7F) before it sends.

| `t` | fields | when |
|---|---|---|
| `hackresult` | **extends layers 3–6:** adds optional `upload` (seconds, finite, clamped 0..30, `action.GetActivationTime()`, 0 = instant) and `kind` ∈ `squad, crowd, driver, camera, traffic, distract, device` (anything else is dropped by the DLL). Both are sent only when `ok` is true | reply to every `hack` |
| `hackspread` | `id` (the `hack` request id), `target:"<id>"` (decimal uint64), `why` ∈ `chain, spread, ping, frenzy` | one per extra NPC the hack touched, ≤ 16 per `id`. `chain` and `spread` come before that id's `hackresult`; `ping` and `frenzy` may come after it (upload completion) |
| `cam` | **extends layers 3–6:** adds `seat:bool` (V sits in a vehicle; `pl` is then V's hips point, not the feet). `fp` is now honest: false in a third-person vehicle camera and while the on-foot third-person view is applied | every frame |
| `hud` | **extends layer 1:** kept for older Minecraft builds; the DLL now always sends `hidden:false` (Minecraft's HUD is never hidden by Cyberpunk, not even in vehicles) | on change |
| `key` | **extends layers 3–6:** `k` also ∈ `arms`, `camo` (the new binds, swallowed while forwarding) | input while forwarding |
| `stats` | `money` int ≥ 0, `wanted` int 0..5, `district` str ≤ 64, `time:[h,m]` (`h` 0..23, `m` 0..59), `speed` km/h ≥ 0 or -1 on foot | 1 Hz and on change, while On and linked |
| `quest` | `title` ≤ 128, `kind` ∈ `main, side, gig, other`, `obj:[{text, cur, tot, opt, on}]` ≤ 12 (`text` ≤ 256, `cur` and `tot` ints ≥ 0, `tot` 0 = no counter, `opt` an optional objective, `on` the active objective), `wp:[x,y,z]` or `null` | on change; `wp` at ≤ 5 Hz |
| `pins` | `list:[{p, kind, tr}]` ≤ 32 within 150 m (`p:[x,y,z]`, `kind` ∈ `quest, vendor, fasttravel, gig, police, ripperdoc, apartment, car, other`, `tr` tracked) | ≤ 2 Hz and on change |
| `prompt` | `title` ≤ 128 (may be `""`), `list:[{text, key, hold, inactive}]` ≤ 6 (`text` ≤ 128, `key` ≤ 3 chars such as `F`, `R`, `1`, `hold` a hold action, `inactive` greyed out); an empty list clears | on change |
| `choices` | `hubs:[{id, title, list}]` ≤ 4 (`id` int, `title` ≤ 128, `list:[{text, f}]` with `text` ≤ 256 and `f` a list of flags), ≤ 12 entries in all; `active` the active hub id, `sel` int (-1 none), `left` seconds or -1; empty `hubs` clears | on change |
| `sub` | `show:bool`, `key` ≤ 64 (line id), `who` ≤ 64, `text` ≤ 256, `secs` 0..30 | each dialogue line shown or hidden |
| `call` | `phase` ∈ `incoming, start, active, end`, `who` ≤ 64, `audio:bool`, `reject:bool` (V can reject it) | on change |
| `notify` | `kind` ∈ `quest, item, money, xp, level, sms, shard, zone, wanted, warning, save, info`, `title` ≤ 128, `text` ≤ 256, `secs` 0..30 | each Cyberpunk notification while On |
| `screen` | either `open` ∈ `pause, map, journal, phone, inventory, skills, cyberware` (Minecraft opens that screen), or `pass` = a pass-through name (below), `""` = the pass-through ended | a menu was intercepted, or a pass-through menu opened or closed |
| `reply` | `kind` ∈ `journal, phone, atlas`, `id` (the request id), `groups:[{hash, title, kind, tracked, done, lines}]` ≤ 128 groups and ≤ 512 lines in all (`title` ≤ 128, `lines:[{hash, text, state}]` with `text` ≤ 256), `pins:[{p, kind, name, i}]` ≤ 256 (`name` ≤ 64); the whole message ≤ 256 KB | answer to `uiact journalreq`, `phonereq` or `atlasreq` |

### Re-test 3: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `binds` | **extends layers 3–6:** `keys:{hack, skills, cyberware, activate, arms, camo}`, Windows virtual-key codes 1..254; a missing name keeps the current key | after each host `hello`, and when rebound |
| `hello` | **extends layer 1:** adds `ver` (the mod version, e.g. `"1.0.0"`, ≤ 32 chars) | on connect |
| `status` | **extends layer 1:** adds `pause:bool` (the Night City pause screen is open: Cyberpunk must be paused) | 10 Hz and on change |
| `uiact` | `a` ∈ `save, loadlast, mainmenu, cpmenu, track, smsreply, fasttravel, journalreq, phonereq, atlasreq`, `arg` str ≤ 64, `hash` uint32, `id` int ≥ 0; an absent `arg`, `hash` or `id` means `""`, 0 and 0 | a Night City screen button |
| `persp` | `mode` ∈ `first, back` | on F5 (`key perspective`) and after each host `hello` |

### Re-test 3 semantics and limits
- **Validation:** every receiver checks types and limits, counts a bad message and drops it (or the bad entry); it never
  ends the link. Unknown enum values become `other` for `pins.kind` and `quest.kind`, are dropped for `choices` flags,
  and make the whole message invalid for `screen`, `hackspread.why`, `uiact.a` and `persp.mode`. A `hackresult` whose
  `kind` is unknown loses the `kind` only; `upload` is clamped into 0..30 and a non-finite `upload` counts as 0.
- **Quickhack timing (F1):** `hackresult` is the answer to the request: it arrives when the upload **starts**. Minecraft
  draws an upload beam from the send until the result, then for `upload` more seconds, and plays the chip's **impact** when
  the upload completes. `kind` tells Minecraft which Night City effect it is showing: `squad` an AI-driven person, `crowd`
  a crowd pedestrian (vanilla does not quickhack crowd records, so Cyberblock adds a visible extra), `driver` the driver
  of a car, and `camera`, `traffic`, `distract` or `device` for the device chip. A target that is still uploading refuses
  a new hack in Minecraft (no RAM spent). `hackspread` is accepted for the last 32 request ids; an unknown id is dropped
  quietly. `why`: `chain` (Chain Hack), `spread` (Contagion), `ping` (Compass Ping revealed it), `frenzy` (a Cyberpsychosis
  victim of the hacked person).
- **Seat (F3):** with `cam.seat` true, `pl` is V's hips point (the rotated seat offset from the vehicle's Hips slot), `fp`
  is false when the vehicle camera is third person, and Minecraft draws the whole seated Steve instead of the hand.
  `hud` is always `hidden:false`. In a vehicle Minecraft shows only Steve's hand (first-person camera) or the whole seated
  Steve (third-person camera); the mod itself hides its own HUD elements there (hotbar, survival bars and crosshair).
- **Third person (F2):** `fp` is false while the on-foot third-person view is applied. Minecraft asks for it with `persp`
  `mode:"back"` (F5); the DLL applies it only while allowed (Active on foot, not held, no menu, no braindance, no photo
  mode) and keeps reporting `fp:true` while it cannot.
- **Pause authority is level-triggered:** Cyberpunk is paused by Minecraft exactly while `status.pause` is true **and**
  the link is up **and** Cyberblock is On. On a link drop, F9 off, Lost or a host `hello` the DLL unpauses. A Cyberpunk
  pause that Minecraft did not ask for is left alone.
- **Rates:** `stats` 1 Hz, `pins` ≤ 2 Hz, `quest.wp` ≤ 5 Hz, everything else on change. The DLL deduplicates, so a
  message identical to the last one of its type is not sent again.
- **Limits** (checked by the receiver):
  - `stats.wanted` is 0..5, `stats.time` is `[0..23, 0..59]`, `stats.speed` is -1 or ≥ 0, `stats.money` an int ≥ 0;
  - `quest.obj` has ≤ 12 entries, `pins.list` ≤ 32, `prompt.list` ≤ 6, `choices.hubs` ≤ 4 and ≤ 12 entries in all;
    `choices.sel` is ≥ -1 and `choices.left` is -1 or ≥ 0;
  - `sub.secs` and `notify.secs` are 0..30; `call.phase` is one of the four phases;
  - `reply.groups` ≤ 128, ≤ 512 lines in all, `reply.pins` ≤ 256, a line `state` is 0, 1 or 2, every `hash` is a uint32
    JSON number, and the whole message is ≤ 256 KB (the DLL drops extra entries and counts them);
  - every position is finite with `|c| < 3e7`; `binds.keys` values are 1..254; `hello.ver` is ≤ 32 chars.
- **`reply` meanings:**
  - **journal:** groups are quests (`kind` main, side, gig or other), lines are objectives (`state` 0 active, 1 done,
    2 failed);
  - **phone:** groups are contacts or threads (`kind` `sms`), lines are messages (`state` 0 from the contact, 1 from V,
    2 a reply V can choose; its `hash` is what `uiact smsreply` sends);
  - **atlas:** `pins` are every Map-target mappin plus the fast-travel points (`i` is the fast-travel index, -1 when it is
    not one), `groups` is empty.
- **Knockback (F7):** `hit.impulse` is horizontal, `|impulse| = (0.4 + extra) × 5` m/s, where `extra` is the knockback
  level (attack knockback plus the sprint bonus, times 0.5). Gorilla Arms add +1.0 on people, and projectiles use
  `(0.4 + 0.6 × Punch level) × 5` along their horizontal direction. A plain hit is 2 m/s, a sprint hit or Knockback I
  4.5, Knockback II or a gauntlet or Gorilla Arms 7, Gorilla Arms plus sprint 9.5, gauntlet plus Gorilla Arms 12.
  Cyberpunk turns `s = |impulse|` on people into a tier: `s < 4.0` impact, `4.0 ≤ s < 6.5` stagger, `6.5 ≤ s < 10.0`
  knockdown, `s ≥ 10.0` knockdown plus a short ragdoll. The wire shape of `hit` did not change.

### Re-test 3: keys (six binds)
| Action | Default | VK | wire name |
|---|---|---|---|
| Hack | G | 71 | `hack` |
| Skill tree | K | 75 | `skills` |
| Cyberware screen | U | 85 | `cyberware` |
| Operating System implant | Y | 89 | `activate` |
| Arms implant | L | 76 | `arms` |
| Optical Camo | O | 79 | `camo` |

- Both sides refuse the same keys as before (W A S D C E Q T X F I M J H N B P V Z R, 1–9, F5, F9, Space, Shift, Ctrl,
  Esc; allowed: G K L O U Y 0 F1–F4 F6–F8 F10–F12). A refused key falls back to **its own** default. If any two of the
  **six** resolved keys are equal, **all six** go back to G K U Y L O. A missing name keeps the current key (older
  Minecraft builds). `shared/vectors.json` `binds_apply` pins the rule.
- Each key fires its slot's active implant: `activate` the Operating System implant (Sandevistan or Berserk), `arms` the
  Arms implant (Mantis Blades lunge, Gorilla Arms slam, Monowire lash, Projectile Launch System), `camo` Optical Camo.
  There is no tap and hold cycling any more.
- `key arms` and `key camo` are swallowed while forwarding, like every other Cyberblock key. DLL log line:
  `binds: hack G, skills K, cyberware U, activate Y, arms L, camo O`.
- Vanilla Minecraft mappings bound to one of the six keys (Advancements on L, Friends on O, Quick Actions on G) are
  unbound once by the mod on the first client tick.

### Re-test 3 names
- **Hack kinds** (`hackresult.kind`) — `squad`, `crowd`, `driver`, `camera`, `traffic`, `distract`, `device`
- **Spread reasons** (`hackspread.why`) — `chain`, `spread`, `ping`, `frenzy`
- **Quest kinds** (`quest.kind`) — `main`, `side`, `gig`, `other`
- **Pin kinds** (`pins.list[].kind`, `reply.pins[].kind`) — `quest`, `vendor`, `fasttravel`, `gig`, `police`, `ripperdoc`, `apartment`, `car`, `other`
- **Choice flags** (`choices.hubs[].list[].f`) — `inactive`, `pay`, `quest`, `check_ok`, `check_fail`, `timed`
- **Call phases** (`call.phase`) — `incoming`, `start`, `active`, `end`
- **Notify kinds** (`notify.kind`) — `quest`, `item`, `money`, `xp`, `level`, `sms`, `shard`, `zone`, `wanted`, `warning`, `save`, `info`
- **Screen open names** (`screen.open`) — `pause`, `map`, `journal`, `phone`, `inventory`, `skills`, `cyberware`
- **Pass-through names** (`screen.pass`) — `vendor`, `ripperdoc`, `stash`, `wardrobe`, `crafting`, `breach`, `arcade`, `braindance`, `tutorial`, `settings`, `saves`, `death`, `loading`, `other`
- **Reply kinds** (`reply.kind`) — `journal`, `phone`, `atlas`
- **UI actions** (`uiact.a`) — `save`, `loadlast`, `mainmenu`, `cpmenu`, `track`, `smsreply`, `fasttravel`, `journalreq`, `phonereq`, `atlasreq`
- **Perspective modes** (`persp.mode`) — `first`, `back`

### Re-test 3 examples
One message per line (JSON Lines). Every `t` of the two re-test 3 tables has at least one line; `screen` shows both of its
forms, `key` both new keys and `reply` all three kinds.

Re-test 3 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"hackresult","id":8,"ok":true,"reason":"","upload":2.5,"kind":"squad"}
{"t":"hackspread","id":8,"target":"9007199254740993","why":"spread"}
{"t":"cam","f":1044,"p":[-1245.5,66.62,812.25],"r":[150.0,12.5,0.0],"fov":58.7,"fp":false,"pl":[-1245.5,65.4,812.25],"h":150.0,"cr":false,"air":false,"held":false,"menu":false,"fly":false,"lv":57,"seat":true}
{"t":"hud","hidden":false}
{"t":"key","k":"arms","down":true,"mods":0}
{"t":"key","k":"camo","down":false,"mods":0}
{"t":"stats","money":12450,"wanted":2,"district":"Watson","time":[14,35],"speed":-1}
{"t":"quest","title":"The Pickup","kind":"main","obj":[{"text":"Meet Jackie at the bar","cur":0,"tot":0,"opt":false,"on":true},{"text":"Collect the shards","cur":1,"tot":3,"opt":true,"on":false}],"wp":[-1230.5,65.0,820.5]}
{"t":"pins","list":[{"p":[-1240.0,65.0,830.0],"kind":"vendor","tr":false},{"p":[-1230.5,65.0,820.5],"kind":"quest","tr":true}]}
{"t":"prompt","title":"Vending Machine","list":[{"text":"Open","key":"F","hold":false,"inactive":false},{"text":"Hack","key":"R","hold":true,"inactive":true}]}
{"t":"choices","hubs":[{"id":3,"title":"Judy","list":[{"text":"How are you?","f":[]},{"text":"Pay 500 eddies","f":["pay","quest"]},{"text":"Leave","f":["timed"]}]}],"active":3,"sel":0,"left":4.5}
{"t":"sub","show":true,"key":"q101_jackie_0042","who":"Jackie","text":"Hey, choom!","secs":3.5}
{"t":"call","phase":"incoming","who":"Judy","audio":true,"reject":true}
{"t":"notify","kind":"money","title":"Eddies","text":"+250","secs":4}
{"t":"screen","open":"pause"}
{"t":"screen","pass":"vendor"}
{"t":"reply","kind":"journal","id":21,"groups":[{"hash":3735928559,"title":"The Pickup","kind":"main","tracked":true,"done":false,"lines":[{"hash":1001,"text":"Meet Jackie at the bar","state":0},{"hash":1002,"text":"Get the car","state":1}]}],"pins":[]}
{"t":"reply","kind":"phone","id":22,"groups":[{"hash":77,"title":"Judy","kind":"sms","tracked":false,"done":false,"lines":[{"hash":5,"text":"Call me later.","state":0},{"hash":6,"text":"Will do.","state":2}]}],"pins":[]}
{"t":"reply","kind":"atlas","id":23,"groups":[],"pins":[{"p":[-1240.0,65.0,830.0],"kind":"fasttravel","name":"Watson Docks","i":4}]}
```

Re-test 3 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"binds","keys":{"hack":71,"skills":75,"cyberware":85,"activate":89,"arms":76,"camo":79}}
{"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3","ver":"1.0.0"}
{"t":"status","hp":20,"maxHp":20,"food":20,"gm":"survival","screen":false,"pause":true}
{"t":"uiact","a":"fasttravel","arg":"","hash":0,"id":4}
{"t":"persp","mode":"back"}
```

## Atlas (explored map and survey tour)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values and fields. Minecraft's Atlas and
radar draw Night City as a Minecraft map from **real collision geometry**: Cyberpunk casts long down rays and reports the
highest Night City surface of each **2-m cell** (no Cyberpunk map art is used). Minecraft keeps the cells **once per
installation** (`<game dir>/cyberblock/nightcity-map/`), shared by every Minecraft world and every Cyberpunk save, since
Night City is the same in all of them. `/cyberblock survey` carries V across the whole city once so the map fills in
everywhere.

### Atlas: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `terrain` | `c:[cx, cz, y, s, …]` integer quadruples, at most 64 cells: `cx = floor(x / 2)`, `cz = floor(z / 2)` the 2-m cell in Minecraft blocks, `y` the Minecraft y of the highest Night City surface there (the nearest whole y of `z − off`), `s` 0 ground or building, 1 water | survey hits, at most 10 Hz while On (more messages per tick during a tour) |
| `surveystate` | `state` (a survey state, below), `done` and `total` squares (ints ≥ 0, `done ≤ total`), `eta` seconds (−1 unknown), `reason` ≤ 64 (while running: the pass, `city`, `city retry`, `outskirts` or `outskirts retry`), optional `stop:[gx, gy]` (a square that has just been finished) | on every change of the tour, each finished square, every 1 s while it runs, and as the answer to `survey` |
| `reply` | **extends re-test 3:** an atlas pin may add `d` (its district, ≤ 64), and `pins` may hold up to 1024 entries | answer to `uiact atlasreq` |

### Atlas: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `survey` | `op` ∈ `start, stop, status`; `start` adds `done:[gx, gy, …]`, the squares Minecraft already has (at most 4096 pairs of whole numbers, \|v\| ≤ 100000) | `/cyberblock survey start`, `stop`, `status` |

### Atlas semantics and limits
- **Cells:** a 2 × 2 block cell. The DLL converts a hit at Cyberpunk `(x, y, z)` with `survey_cell(x, y, z, off)`
  (`tools/cbmath.py`: Minecraft `(x, z − off, −y)`, then `cx = floor(x / 2)`, `cz = floor(−y / 2)`, `y = floor(z − off + 0.5)`),
  and hands the scripts cell centres with `survey_column(cx, cz)` = Cyberpunk `(2cx + 1, −(2cz + 1))`. `shared/vectors.json`
  holds the `survey` and `survey_column` sets.
- **Walking survey:** after the probes, the scripts cast up to 48 more rays per 100 ms tick, from V's feet `z + 500` to
  `z − 200` m against Static, Terrain and Water, at the cells within 96 m of V, nearest first; a cell is not cast again for
  300 s (20 s after a miss). Each hit gets one short Water-only ray: water is `s:1`. In a vehicle the DLL casts them
  itself instead, 1.5 ms of rays every frame, at the cells 66 to 96 m from V (beyond Cyberblock's own collision boxes).
- **Validation:** the receiver drops a `terrain` whose `c` is not whole quadruples or holds more than 64 cells, any
  `|cx|, |cz| ≥ 1.5e7`, `|y| > 20000` or `s` not 0 or 1; a `surveystate` with an unknown `state`, `done > total`, a
  negative count or a `reason` over 64 bytes; a `survey` with an unknown `op` or a bad `done` list. A bad message is
  counted and dropped and never ends the link.
- **Tour:** planned to a **time budget**: the whole tour (city and outskirts) aims to end within 12 minutes and stops
  after 15 (a later `start` resumes). The **area** comes from the game: the city is the convex hull of the fast-travel
  points outside the Badlands (each map pin joins the district of its nearest fast-travel point within 800 m; at least 8
  pins; with fewer than 6 city points the city is where the pins are dense), widened by 300 m; the outskirts are the hull
  of every pin widened by 300 m, minus the city. Open sea beyond them is never visited. The area is cut into **squares**
  200 m apart (square `(gx, gy)` centred on Cyberpunk `(200·gx, 200·gy)`, 100 × 100 cells). A square is cast at every
  stride-th cell each way, and one ray fills its stride × stride block of cells: the city at 2, 4, 8 or 10 m, the outskirts
  (after the city) at 8, 10 or 20 m; the finest city cell that fits the budget wins, then the finest outskirts cell. The
  plan starts from guessed rates and is made again from the measured ones (seconds per column, seconds per stop, squares
  per stop) every second while sampling; the `eta` is that estimate. The **stops**: V is held every frame at 150 m (or
  25 m above the highest surface seen), over the next square (nearest to V, pulled towards where the tour started). The
  DLL casts its own rays every frame (as many as fit a 40 ms frame, 2 to 25 ms): a 3 × 3 grid of probes 60 m apart until
  they stay the same for 0.5 s (2 s at most); rings of 12 probes from 150 to 600 m measure how far loaded collision
  reaches (10 of 12 must hit); then every open column of every unfinished square within that reach, while V flies up to
  150 m on towards the next stop so its collision streams in. A square is finished when every column hit, or when it lay
  wholly inside a settled stop's reach and 90 % of its columns hit; a stop that did not settle sends its square to one
  retry, skipped once the 12 minutes are used. V is invisible, invulnerable and saving is locked while it runs; at the
  end, on `stop`, F9, a vehicle, a scene, combat, death or a dropped link, V is put back exactly where V stood. A
  Cyberpunk menu pauses it.
- **Resume:** Minecraft keeps every `surveystate.stop` and the cells it has; `start` lists the squares already done (those
  reported, and those Minecraft already knows to 90 %), and the tour skips them.
- **Sending in the DLL:** `terrain` and `surveystate` are queued like `ground` (never superseded).

### Atlas names
- **Survey states** (`surveystate.state`) — `running`, `paused`, `done`, `stopped`, `refused`, `idle`
- **Survey ops** (`survey.op`) — `start`, `stop`, `status`

### Atlas examples
One message per line (JSON Lines). Every `t` of the two Atlas tables has at least one line.

Atlas examples, Cyberpunk to Minecraft:
```jsonl
{"t":"terrain","c":[-623,-407,67,0,-623,-406,12,0,-700,-380,0,1]}
{"t":"surveystate","state":"running","done":12,"total":980,"eta":540,"reason":"city","stop":[-6,4]}
{"t":"reply","kind":"atlas","id":24,"groups":[],"pins":[{"p":[-1240.0,65.0,830.0],"kind":"fasttravel","name":"Japantown West","i":-1,"d":"Westbrook"},{"p":[-1201.5,64.0,845.0],"kind":"vendor","name":"Gun Shop","i":-1}]}
```

Atlas examples, Minecraft to Cyberpunk:
```jsonl
{"t":"survey","op":"start","done":[-6,4,-5,4]}
```

## Phase Q (third-person view and step-up assist)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values and fields. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows Q2, Q3). Minecraft owns the options (the
Cyberblock settings screen, `/cyberblock debug view`); the DLL owns the third-person camera (collision, smoothing,
over-the-shoulder offset, distance, FOV) and tells Minecraft what it did through `vdbg`.

### Phase Q: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `cam` | **extends layer 1, layers 3–6 and re-test 3:** adds `vdbg:[collide, smooth, age]`, the camera's own numbers: `collide` m (the distance after camera collision: nearest hit minus 0.25 m, capped at the wanted distance), `smooth` m (the distance actually used after smoothing), `age` ms (how long ago the DLL's view last computed the camera pose, its last tick, when this `cam` is sent); Minecraft's view debug overlay shows them | every Cyberpunk frame, only while `/cyberblock debug view` is on (the last `prefs` had `debug:true`); absent otherwise |

### Phase Q: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `prefs` | `dist` m (third-person camera distance, 1.5..8, default 3.2), `shoulder` m (over-the-shoulder offset to the right, −1..1, default 0.45), `fov` (0 = the game's own, or 50..110 degrees), `recenter:bool` (swing the shoulder offset to the centre near a wall, default true), `vdist` m (vehicle camera distance: 0 = the game's own, or 3..14; only used when the game's vehicle camera allows it), `stepup:bool` (step-up assist for stairs of placed blocks, default true), `debug:bool` (`/cyberblock debug view` is on: the DLL adds `vdbg` to every `cam` while it is true; default false) | after each host `hello`, whenever one of the six settings changes in the settings screen, and whenever `/cyberblock debug view` is switched on or off |

### Phase Q semantics and limits
- **Defaults:** the DLL starts every link with `dist 3.2, shoulder 0.45, fov 0, recenter true, vdist 0, stepup true, debug false`
  and goes back to them on each `hello`, until a `prefs` arrives. Minecraft always sends all seven fields.
- **Validation (receiver):** the DLL clamps every number into its range (`dist` 1.5..8, `shoulder` −1..1, `fov` 0 or
  50..110, `vdist` 0 or 3..14; a value above 0 and below the lower bound is raised to it, a negative `fov` or `vdist`
  becomes 0), keeps the current value of a field that is missing, not finite or of the wrong type, and ignores any other
  field. A bad `prefs` is counted and never ends the link.
- **`stepup`:** the DLL passes it to the scripts through the event pump as the event `prefs`; with it on, V is lifted onto a
  single Cyberblock box in the way (at most 1.05 m above V's feet, 2 m of free space above it). Night City geometry is
  never stepped on.
- **`debug`:** Minecraft's `/cyberblock debug view` flag, so the DLL knows when to add `vdbg`; Minecraft sends a whole `prefs`
  each time the flag is switched. Like every field it is optional to the receiver: a `prefs` without it keeps the current flag.
- **`vdbg`:** three finite numbers, `collide` and `smooth` 0..100 m, `age` 0..60000 ms. A `cam` whose `vdbg` is bad is
  still a valid `cam`: only the `vdbg` is ignored (like a bad `flight.ts`), so a debug aid never drops camera frames.
- **Camera `fp`:** in third person the DLL sends `fp:false` and `p` is the camera position, `pl` stays V's feet.

### Phase Q examples
One message per line (JSON Lines). Every `t` of the two Phase Q tables has one line.

Phase Q examples, Cyberpunk to Minecraft:
```jsonl
{"t":"cam","f":2210,"p":[-1245.9,66.9,809.1],"r":[150.0,10.0,0.0],"fov":58.7,"fp":false,"pl":[-1245.5,64.9,812.25],"h":150.0,"cr":false,"air":false,"held":false,"menu":false,"fly":false,"lv":57,"vdbg":[3.2,2.85,14.0]}
```

Phase Q examples, Minecraft to Cyberpunk:
```jsonl
{"t":"prefs","dist":3.2,"shoulder":0.45,"fov":0,"recenter":true,"vdist":0,"stepup":true,"debug":false}
```

## Multiplayer (Phase 1)

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1 (the contracts), section 5 (Phase 1). The wire
between the DLL and its own host's Minecraft client (everything above) **does not change shape** and `proto` stays 1: a
Cyberpunk game still talks to the Minecraft client on `127.0.0.1:25600`, and the client decides what stays on the PC and
what goes to the server. From Phase 1 on, singleplayer goes through exactly the same path: the integrated server receives
the local client's payloads over the in-memory connection, and opening the world to LAN makes it a small multiplayer
server with no other change. Older builds ignore the new `t` values and fields as always.

**Terms.** A **host** is a player whose Minecraft client has a live link to its own `Cyberblock.dll` and whose handshake
was granted the host role; a **guest** is any other player (Minecraft only). A **session** is the server's state for one
host, keyed by the player's UUID. Night City ids (NPC, vehicle, device) are only meaningful together with their owner, the
session whose Cyberpunk game they belong to: the server keys them as `(owner UUID, id)`, so two hosts never collide.

**Classes.** Each message of the wire protocol is one of:
- **LOCAL:** stays between the DLL and its own host's Minecraft client and never leaves the PC;
- **UP:** sent by the DLL, forwarded by the host's client to the server as a `cyberblock:up` payload;
- **DOWN:** built on the server, sent to the owner's client as `cyberblock:down` and forwarded verbatim to its DLL;
- **BOTH:** used locally and also relayed UP.

The client forwards UP messages as they are (JSON text) and forwards DOWN messages to the DLL unchanged. The server runs
the same validators the client ran before (the limits above), plus the envelope checks below.

### Multiplayer: Cyberpunk → Minecraft classes
| `t` | class | After Phase 1, handled by | notes |
|---|---|---|---|
| `hello` | BOTH | client handshake (LOCAL); UP as the "host session (re)started" notice with the DLL's new `ver` | the server then sends `align`, `perks`, `cyberware`, the `effect` set and the block resync DOWN (the order after each host `hello`) |
| `cam` | LOCAL | `HostState`, `PlayerSync`, `FrameExporter` (client) | the server never sees it, it gets the derived `hstate` |
| `ground` | UP | `WorldBridge` per session (server) | the client splits arrays to fit the payload cap |
| `unground` | UP | `WorldBridge` per session (server) | split like `ground` |
| `clear` | UP | `WorldBridge` per session (server) | |
| `state` | BOTH | client HUD and vehicle rules; session `active`, `vehicle` | |
| `view` | LOCAL | client | |
| `hud` | LOCAL | client | |
| `aim` | BOTH | client pick limit (`HostState.aimLimit`); session aim distance for the server-side scan pick and the host's reach check | |
| `key` | LOCAL | `ClientInput`, cyber key listeners | the actions they trigger go to the server through vanilla packets or the typed payloads |
| `slot` | LOCAL | `ClientInput` | |
| `scroll` | LOCAL | `ClientInput` | |
| `cursor` | LOCAL | `ClientInput` | |
| `click` | LOCAL | `ClientInput` | |
| `char` | LOCAL | `ClientInput` | |
| `skey` | LOCAL | `ClientInput` | |
| `dmg` | UP | `HostDamage` for the session's player | |
| `npcs` | BOTH | client radar (LOCAL); `ProxyManager` per session (UP) | the client splits long lists under the payload cap |
| `mobdmg` | UP | `MobWar` | |
| `pcells` | UP | `WorldBridge` temp cells | |
| `unlead` | UP | `ProxyManager` | |
| `killed` | UP | `Kills` | |
| `scaninfo` | UP | `Hacking` (server), routed to the player who asked | a reply: it must carry a request id the session issued |
| `hackresult` | UP | `Hacking` (server), routed to the player who asked | a reply, as `scaninfo` |
| `hackspread` | UP | `Hacking` (server), routed to the player who asked | a reply, as `scaninfo` |
| `look` | LOCAL | unused | |
| `stats` | BOTH | client HUD (LOCAL); UP at 1 Hz at most as the session's `money`, `wanted`, `district`, `time` | shops paid in eddies, Heat, time of day |
| `quest` | LOCAL | client UI (`UiLink`) | the host's own Night City UI |
| `pins` | LOCAL | client UI (`UiLink`) | |
| `prompt` | LOCAL | client UI (`UiLink`) | |
| `choices` | LOCAL | client UI (`UiLink`) | |
| `sub` | LOCAL | client UI (`UiLink`) | |
| `call` | LOCAL | client UI (`UiLink`) | |
| `notify` | LOCAL | client UI (`UiLink`) | |
| `screen` | LOCAL | client UI (`UiLink`) | |
| `reply` | LOCAL | client UI (`UiLink`) | |
| `terrain` | LOCAL | `AtlasStore` (client) | LOCAL until Phase 3, then BOTH: UP into the server's shared Night City map (Phase 3: BOTH, see [Phase 3](#phase-3-the-night-city-replica)) |
| `surveystate` | LOCAL | `SurveyClient` | |
| `env` | UP | `EnvSync` (server), Phase 3 | the primary host's clock and weather |
| `vteleported` | UP | `MeetUp` (server), routed to the request's owner, Phase 3 | a reply: it must carry a request id the session issued |
| `parked` | UP | session (server), **Phase 7** (the hub) | the answer to `park`; the DLL builds it from Phase 3, but no Phase 3 server sends `park`, so until Phase 7 the client relay drops a `parked` (counted) and it has no rate row |
| `devices` | UP | the device layer per session (server), Phase 4 | the devices within 48 m: changes and a full list every 5 s |
| `devstate` | UP | the device layer per session (server), Phase 4 | one device changed state |
| `devresult` | UP | the device layer (server), routed to the request's asker, Phase 4 | a reply: it must carry a request id the session issued |
| `cmd` | LOCAL | `WorldBridge.command` in a dev client | test tools only: never relayed (the relay drops it), a dedicated server refuses it |

The frame export (`Local\CyberblockFrame`) is LOCAL: `FrameExporter` on the client.

### Multiplayer: Minecraft → Cyberpunk classes
| `t` | class | Built by (after Phase 1) | notes |
|---|---|---|---|
| `hello` | LOCAL | client `HostLink` | |
| `status` | LOCAL | client `StatusReporter` | |
| `binds` | LOCAL | client `CyberKeys` | |
| `uiact` | LOCAL | client `UiLink` | |
| `persp` | LOCAL | client `ClientInput` | |
| `flight` | LOCAL | client `Flight` | the client owns Steve's glide physics |
| `prefs` | LOCAL | client `ViewClient` (Phase Q) | the player's own camera options and debug flag; never relayed |
| `survey` | LOCAL | client `SurveyClient` | `/cyberblock survey` becomes a server command that asks the issuing host's client over `cyberblock:down` (`survey_cmd`, below) |
| `block` | DOWN | `WorldBridge` per session | |
| `blocks` | DOWN | `WorldBridge` per session | |
| `align` | DOWN | `WorldBridge` per session | one ground alignment `k` per world: every host uses it |
| `explosion` | DOWN | `ExplosionEvents`: to every active session whose V is within 128 m | subject to the owner's `explosions` toggle when a guest caused it |
| `knock` | DOWN | wind charges, routed like `explosion` | subject to the owner's `explosions` toggle |
| `hit` | DOWN | `ProxyManager` and `ProxyEntity`: to the owner of the proxy | subject to the owner's `damage` toggle when a guest caused it |
| `ignite` | DOWN | as `hit` | `damage` toggle |
| `lead` | DOWN | as `hit` | `damage` toggle |
| `pull` | DOWN | as `hit` | `damage` toggle |
| `mobs` | DOWN | `MobWar` for that host's player | |
| `proj` | DOWN | `Projectiles` for that host's player | |
| `teleport` | DOWN | `ThrownEnderpearlMixin` for that host's player | |
| `effect` | DOWN | `PlayerEffects` for that host's player | |
| `scan` | DOWN | `Hacking`: to the owner of the target (a guest's request too) | `target:null` is only valid for the host's own game; the owner's `hacks` toggle |
| `hack` | DOWN | `Hacking`: to the owner of the target (a guest's request too) | the owner's `hacks` toggle |
| `perks` | DOWN | `PerkShop` for that host | |
| `cyberware` | DOWN | `Ripperdoc` for that host | |
| `cyberfx` | DOWN | `CyberwareEffects` for that host | `perkfx` stays superseded (never sent) |
| `pteleport` | DOWN | `ServerPlayerMixin` | Steve was teleported by a command; documented below |
| `vteleport` | DOWN | `MeetUp` (server), Phase 3 | to the host's session only |
| `park` | DOWN | session (server), **Phase 7** (the hub) | to the host's session only; the DLL and scripts handle it from Phase 3, the server first sends it in Phase 7 |
| `devact` | DOWN | the device layer (server), Phase 4 | to the device's owner only; the owner's `devices` toggle and the device type's bit when a guest asked |

### Multiplayer: new messages
| `t` | class | fields | when |
|---|---|---|---|
| `hstate` | UP (client → server, built by the client; never on the DLL wire) | `active`, `vehicle`, `air`, `cr`, `held`, `fly`, `launch`, `seat` (booleans), `lv` int 0..63, `feetCpZ` a finite number or absent | at 5 Hz and on change, from `cam`, `state` and `Flight`; it replaces the JVM-shared `Bridge.hostAirborne`, `hostCrouching`, `hostHeld`, `hostInVehicle`, `hostFeetCpZ`, `hostStands` and `Cyberblock.launching` on the server |
| `session` | client → DLL | `state` ∈ `none, host, guest, refused`, `reason` (≤ 128 UTF-8 bytes, `""` when there is none), `perms:{explosions, damage, hacks, devices, spawns}` (booleans) | after every handshake result, server disconnect and reconnect |
| `pteleport` | DOWN → DLL | `pos:[x, y, z]` Minecraft position, finite with `\|c\| < 3e7` | a command (`/tp`) moved Steve: the position he was teleported to, three decimals. **Undocumented until Phase 1**; the DLL ignores it today like any unknown `t` |
| `survey_cmd` | DOWN, client only | `op` ∈ `start, redo, stop, status` | `/cyberblock survey` on the server asks the issuing host's client; **the client handles it (`SurveyClient`) and never forwards it to the DLL** |
| `hello` | DLL → client (**extends layer 1**) | adds `ver` (the DLL version, ≤ 32 chars) | on connect, as Minecraft's `hello` already has |

- **`session`:** on `none`, `guest` or `refused` the DLL and the scripts clean Cyberpunk state exactly as on a link drop
  (collision boxes reset, puppets despawned, tethers released, Minecraft pause released, survey stopped, parked V released)
  while keeping the WebSocket. On a change **to** `host` the DLL forgets its probe state and sends `clear`, so the new
  server session receives the whole ground again. `refused` carries the reason to show (a version mismatch, for example).
  `perms` is what that host currently lets guests do to its game (`explosions`, `damage`, `hacks`, `devices`, `spawns`),
  as the server sends it in `hs_ack.perms`. The client cuts `reason` at 128 bytes on a character boundary before it sends
  (the version mismatch text of the handshake is exactly 128 bytes for `X.Y.Z` versions); a receiver that still gets a
  longer one cuts it and never drops the whole message.
- **`hstate`** is the server's only view of the host's body: `cam` never leaves the PC. `lv` is the alignment `k` the DLL
  converted the latest `cam` with; `feetCpZ` is V's feet height in Cyberpunk z (absent while unknown).
- **`pteleport.pos`** is a Minecraft position (the client does not shift it): a receiver maps it with the world's
  alignment like any other position (`cp_to_mc_pos` inverse, [Ground alignment](#ground-alignment-re-test-2)).
- **`survey_cmd`** carries the same four operations as the `/cyberblock survey` command; `redo` is a `start` that lists no
  finished squares (the client builds the `survey` message for the DLL).
- **Validation:** types and limits are checked on both sides; a bad message is dropped and counted, never fatal. Unknown
  `state` or `op` values make the whole message invalid.

### Multiplayer: payloads (client ↔ server)
Registered with Fabric's payload registry and play networking (the 26.3 class names are fixed in task 1-N1). Every payload
is a record with a `StreamCodec`; strings are UTF-8 with a length cap. Receivers run on the server thread (server) or are
moved to the client thread (client). For reference; the owner of the exact caps is the integrator.

| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:hs` | C2S | `proto` int (1), `mod` ≤ 32 (the mod version), `dll` ≤ 32 (`""` = no DLL linked), `wantHost` bool | on join; again when the DLL link comes up, drops or its `ver` changes |
| `cyberblock:hs_ack` | S2C | `proto` int, `server` ≤ 32 (the server's mod version), `ok` bool, `role` ∈ `host, guest`, `reason` ≤ 256 (shown to the player when not empty), `perms` int bitmask, `maxHosts` int, `features` int bitmask | the answer to every `hs` |
| `cyberblock:up` | C2S | `seq` int (per connection, +1 each), `t` ≤ 16, `json` ≤ 30000 bytes | one UP message |
| `cyberblock:down` | S2C | `json` ≤ 1 000 000 bytes | one DOWN message for this client's DLL (or the client-only `survey_cmd`) |
| `cyberblock:roster` | S2C | `list:[{uuid, name, linked, body ∈ male/female, team int (-1 none), pvp, downed, hidden}]` ≤ 200 | on join and on every change |
| `cyberblock:perms` | C2S | `perms` int bitmask, `devices` int bitmask | a host changes what guests may do to its game |
| `cyberblock:content` | S2C | `kind` ≤ 32, `json` ≤ 1 000 000 | content a client needs, on join and after `/reload` |
| `cyberblock:act` | C2S | `t` ≤ 16, `json` ≤ 8000 | a player's request that the server routes to an owner: `scan`, `hack`, ... (each phase lists its `t`) |
| `cyberblock:ui` | S2C | `t` ≤ 16, `json` ≤ 1 000 000 | server data for a screen |

- **Bitmasks.** `perms`: 1 `explosions`, 2 `damage`, 4 `hacks`, 8 `devices`, 16 `spawns`. `features` (phase features the
  server has): 1 replica, 2 devices, 4 figures, 8 hacking, 16 hub, 32 modes.
- **Size caps.** Vanilla limits serverbound custom payloads, so the `up` cap sits below that limit (30000 bytes; task 1-N1
  confirms the 26.3 number and adjusts it). The client relay splits `ground`, `unground` and `terrain` arrays (whole
  quadruples) and `npcs` lists into several UP messages under the cap; any other UP message over the cap is dropped and
  counted.
- **Order.** UP messages of one host arrive in `seq` order and are processed in order on the server thread. The `align`
  immediately followed by `blocks reset:true` rule of [Ground alignment](#ground-alignment-re-test-2) holds: the server
  builds both in one tick into one session's DOWN queue.
- **Down queue.** Per session, bounded at 2048 messages: the newest `mobs` and `proj` replace a queued one of the same
  type; past the bound the oldest droppable message goes and a `blocks` resync is scheduled. A client that is not the
  session's host ignores `down` (counted).

### Multiplayer: handshake and sessions
- **Handshake.** On join the client sends `hs`; the server answers `hs_ack`. A different `proto`, or mod versions that
  differ in major.minor, give `ok:false` and a disconnect with "Cyberblock version mismatch: server X, your mod Y. Update
  from the modpack." A linked DLL whose `ver` differs from the client's mod version plays as a guest with a reason (and the
  client sends `session state:"refused"` to the DLL). Host role refused for another reason (not whitelisted, not in `hosts`,
  `maxHosts` reached, `allowHosts` false) is `role:guest` with the reason; otherwise `role:host` when `wantHost`, else
  `role:guest`. No `hs` within 10 s means no Cyberblock mod: with `requireMod:true` the server disconnects the client. The
  client re-sends `hs` whenever its DLL link comes up, drops or reports another `ver`; a role change is answered with a new
  `hs_ack`. On a lost server connection the client sends `session state:"none"` to the DLL and reconnects after 5, 10, 20,
  40 and then every 60 s for 10 minutes.
- **Session lifecycle.** `NONE` (no granted host role) → `LINKED` (`hs_ack role:host` and a host `hello` UP) → `ACTIVE`
  (`hstate.active`) → back to `LINKED` when Cyberpunk goes Off → end on disconnect, a DLL link loss (`hs` with `dll:""`), a
  role revoke or a server stop. On end the server removes that session's outline barriers, temp cells, proxies and figures,
  mob targets and tethers and forgets its pending requests. After each host `hello` the server sends `align`, `perks`,
  `cyberware`, the `effect` set and the block resync (`align` immediately followed by `blocks reset:true`) in one tick.
- **Several hosts.** The model is multi-host from Phase 1; `maxHosts` (server config) is 1 until a later phase's multi-host
  check passes (Phase 3's `twohosts2` check raises the default to 4).
- **Log lines** (tests grep for them). Server: `handshake: <name> mod X dll Y -> host|guest (<reason>)`, `session: <name>
  started|active|ended (<reason>)`, `relay: <name> dropped <t> x N (<why>)`. Client: `handshake: server X -> <role>`,
  `relay: up N msgs/s, down N msgs/s`, `reconnect: in N s (<reason>)`. DLL: `session: <state> (<reason>)`,
  `hello ver <ver>`. Scripts: `session: cleaned (<reason>)`.

### Multiplayer: security, rate limits and guest toggles
- **Who may send `up`.** Only a player with an active host role. An `up` from anyone else is dropped and counted, and 50 of
  them within a minute kick the player.
- **Envelope checks on every `up`** (server, before any handler): `t` is one of the UP types of the tables above; `json`
  parses and its `t` equals the envelope `t`; the size is within the cap; the per-type token bucket below has a token; for
  types with positions, every position is within the session's **sanity radius** of the host's player (160 blocks
  horizontally, `terrain` 400 blocks); replies (`scaninfo`, `hackresult`, `hackspread`, later `devresult`, `spawnresult`)
  carry a request id the session issued. A failing message or entry is dropped and counted; the counters are logged every
  5 s (`relay: <name> dropped <t> x N (<why>)`).
- **Rate limits** (token buckets per type and session; the server config's `rate` multiplier scales all of them):

| `t` | tokens per second | burst | note |
|---|---|---|---|
| `ground`, `unground`, `clear` | 40 | 120 | at most 20000 cells per second |
| `npcs` | 15 | 30 | |
| `dmg` | 20 | 40 | |
| `aim` | 15 | 30 | |
| `hstate`, `state` | 10 | 20 | |
| `mobdmg`, `pcells` | 30 | 60 | |
| `scaninfo`, `hackresult`, `hackspread` | 40 | 80 | |
| `killed`, `unlead` | 10 | 20 | |
| `stats` | 2 | 4 | |
| `terrain` | 30 | 200 | the burst is 200 during a survey |
| `hello` | 1 | 3 | |
| `devices`, `devstate`, `devresult` | 10 | 40 | Phase 4 and later |
| `env` | 2 | 4 | Phase 3 (1 Hz and on change) |
| `vteleported` | 2 | 4 | Phase 3 (a reply; one Meet Up at a time per host) |

- **Overflow.** A session that keeps overflowing at five times its rate for 10 s is ended and the player kicked with
  "Cyberblock: too many messages from your Cyberpunk link".
- **Host permissions (toggles).** Each host decides what guests may do to its own Cyberpunk game: `explosions` (guest
  explosions and wind charges near this host's V), `damage` (guest hits, fire, leads and rods on its NPCs and cars), `hacks`
  (guest scans and quickhacks), `devices` (guest device control; Phase 4 adds one bit per type), `spawns` (guest spawns of
  Night City NPCs; off by default, the others on). A gated action still happens in the Minecraft world; it just does not
  reach that host's Cyberpunk game, and the guest sees `cyberblock.guest.blocked` on the action bar. The host's own actions
  are always allowed. `/cyberblock guests <toggle> on|off` (the host, for its own game) changes them.
- **`cmd`** is never relayed and a dedicated server refuses it.

### Multiplayer names
- **Payload ids** (`cyberblock:<id>`) — `hs`, `hs_ack`, `up`, `down`, `roster`, `perms`, `content`, `act`, `ui`
- **Message classes** — `LOCAL`, `UP`, `DOWN`, `BOTH`
- **Session states** (`session.state`) — `none`, `host`, `guest`, `refused`
- **Roles** (`hs_ack.role`) — `host`, `guest`
- **Survey commands** (`survey_cmd.op`) — `start`, `redo`, `stop`, `status`
- **Guest toggles** (`session.perms` keys, in bit order) — `explosions`, `damage`, `hacks`, `devices`, `spawns`

### Multiplayer examples
One message per line (JSON Lines). Every new message has at least one line; `session` shows all four states.

Multiplayer examples, Cyberpunk to Minecraft:
```jsonl
{"t":"hello","proto":1,"game":"cp2077","build":"2.31","ver":"1.1.0"}
```

Multiplayer examples, Minecraft to Cyberpunk:
```jsonl
{"t":"session","state":"host","reason":"","perms":{"explosions":true,"damage":true,"hacks":true,"devices":true,"spawns":false}}
{"t":"session","state":"guest","reason":"","perms":{"explosions":false,"damage":false,"hacks":false,"devices":false,"spawns":false}}
{"t":"session","state":"refused","reason":"Cyberblock: your Cyberpunk plugin is 1.0.0 but your mod is 1.1.0; you can play as a guest. Reinstall both from the same release.","perms":{"explosions":false,"damage":false,"hacks":false,"devices":false,"spawns":false}}
{"t":"session","state":"none","reason":"disconnected from the server","perms":{"explosions":false,"damage":false,"hacks":false,"devices":false,"spawns":false}}
{"t":"pteleport","pos":[-1230.5,65.0,820.5]}
```

Multiplayer examples, client to server (`cyberblock:up`):
```jsonl
{"t":"hstate","active":true,"vehicle":false,"air":false,"cr":false,"held":false,"fly":false,"launch":false,"seat":false,"lv":57,"feetCpZ":64.9}
{"t":"hstate","active":false,"vehicle":true,"air":false,"cr":true,"held":true,"fly":false,"launch":false,"seat":true,"lv":0}
```

Multiplayer examples, server to client only (`cyberblock:down`):
```jsonl
{"t":"survey_cmd","op":"start"}
```

## Phase 3 (the Night City replica)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values and fields. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows 3) with the dated Phase 0 amendment after it.
Night City becomes visible to guests as **replica blocks**: the server builds solid material blocks from the host's probes, saves
them in the world and keeps them while the host is away. Everything below is already in Minecraft convention (positions get the
[ground alignment](#ground-alignment-re-test-2) offset on the Cyberpunk side, as before).

### Phase 3: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `ground` | **extends layer 1:** adds optional `m:[code, …]`, one material code (0..26) per quadruple of `c`, in the same order; a missing `m` means every code is 0 (unknown) | probe deltas (new solid ranges), UP |
| `unground` | **extends layer 1:** adds optional `seen:true` when the cells were observed empty inside the probe radius (a column hit again without the old range); absent: the cells only left the radius | probe deltas, UP |
| `env` | `time:[h, m]` (`h` 0..23, `m` 0..59) from the game time, `weather` ∈ the weather names below (`other` when the preset is not mapped) | 1 Hz and on change, deduplicated, UP |
| `vteleported` | `id` (the `vteleport` request id), `ok:bool`, `reason` (`""` when `ok`, else `no ground`, `timeout` or `blocked (<why>)`), `p:[x, y, z]` the snapped feet position (V's position when it failed) | the answer to every `vteleport`, UP |
| `parked` | `on:bool` (the state now), `ok:bool`, `reason` (`""` when `ok`) | the answer to every `park`, UP |

### Phase 3: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `vteleport` | `id` int ≥ 0 (a request id the session issued), `p:[x, y, z]` the Minecraft position to put V's feet at, `yaw`, `why` ∈ `meetup`, `holo`, `hub_exit` | Meet Up (Phase 3), the Holo-Table and the hub exit (later phases); DOWN to that host only |
| `park` | `on:bool` | the server parks or releases that host's V (invisible, invulnerable, unseen by NPCs, saving locked, no movement or camera); DOWN to that host only. The DLL and scripts handle it from Phase 3; the server first sends it with the Phase 7 hub |

### Phase 3 semantics and limits
- **Replica blocks.** The server turns each `ground` range into `cyberblock:nc_<material>` blocks in **air cells only** (never over
  a player's block); the block comes from the range's `m` code through the code table below (`material_palette` decides code 0).
  A range with no `m` is code 0. `unground` removes replica blocks **only with `seen:true`**: leaving the probe radius keeps them
  (the map grows as a host explores). `clear` never removes a replica block (it only ends outline barriers and temp cells), and the
  replica is saved in the world, so it stays when the host's Cyberpunk is closed. Replica blocks are host ground: they are never
  sent to any host as player `block` or `blocks`. An active host's own client does not draw them (Night City is the real thing
  there); guests and inactive hosts see them.
- **Material codes.** 0 unknown; 1 `concrete_light`, 2 `concrete_dark`, 3 `asphalt`, 4 `sidewalk`, 5 `glass`, 6 `metal`, 7 `brick`,
  8 `tile`, 9 `ground`, 10 `water`; 11 to 26 `nc_neon_<dye>` in the vanilla dye order (white, orange, magenta, light_blue, yellow,
  lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black), light level 15. The scripts send the raw name of the
  probe hit (`TraceResult.material`, or the hit mesh's `meshAppearance` when it is `None`); the DLL maps names to codes with one fixed
  rule: the lower-case name is matched by substring against each code's list, tried in the order of `shared/vectors.json`
  `materials.order` (a dye neon, any other neon, glass, water, metal, brick, tile, asphalt, sidewalk, dark concrete, light concrete,
  ground); the first match wins and an unmatched name is 0 (logged once per name). `tools/cbmath.py` is the reference
  (`material_code`, `material_block`); `materials` in `shared/vectors.json` lists the codes, their substrings and 38 name cases that
  every copy checks. **The substring lists are a working table**: Phase 0's CET lines B1 to B5 show the real names and the integrator
  corrects the table once.
- **Doors and devices.** A probe ray whose hit entity is a `Device` is cast again past it, so doors and gates never enter the replica.
- **Time and weather.** From the **primary host** (the first active session) the server stops the daylight cycle and sets the day time
  to `((h·60 + m) / 1440 · 24000 + 18000) mod 24000` (06:00 is tick 0, noon 6000, 00:00 18000; `shared/vectors.json`
  `clock`), and the weather from `env.weather`: `rain` is rain, `storm` is thunder, every other name is clear. A second host
  never changes them; with no host the world keeps the last values.
- **Meet Up.** `vteleport` and `vteleported` are a request and its reply: the server issues `id`, the DLL answers exactly once with the
  same `id`. `vteleported.p` is where V arrived and is **exempt from the sanity radius** (it may be far from where V was); the host's next
  `hstate` follows it. The DLL refuses in a vehicle, a scene or combat (`blocked (<why>)`), waits for the world to stream in at the
  target (`timeout` after 15 s) and snaps V to the ground below (`no ground` when there is none). `why` only labels the request.
- **Park.** `park on:true` makes V invisible, invulnerable, unseen by NPCs (or the fast-travel fallback), locks saving, movement and
  camera; `on:false` undoes it. The DLL answers with `parked` carrying the state it is in. Phase 3 builds the Cyberpunk side only: the
  server sends `park` and relays `parked` from Phase 7 (the hub), which also adds the `parked` rate row.
- **Validation (receivers).** `m` has exactly one whole number 0..26 per quadruple of `c` (a bad `m` drops the codes, not the ranges: the
  ranges become code 0); `seen` is `true` or absent; `env.time` is `[0..23, 0..59]` and `weather` one of the names below (an unknown name is
  `other`); `vteleport.p` is finite with `|c| < 3e7`, `yaw` finite, `id` a whole number ≥ 0, `why` one of the three names;
  `vteleported.id` a whole number ≥ 0, `reason` at most 128 UTF-8 bytes and `""` when `ok` is true; `parked.reason` likewise. A bad
  message is counted and dropped, never fatal. Rate limits are in the [rate table](#multiplayer-security-rate-limits-and-guest-toggles).
- **Features.** `hs_ack.features` bit 1 (replica) and bit 4 (figures) are set by a Phase 3 server.

### Phase 3: payloads (client ↔ server)
The shared Night City map: from Phase 3 `terrain` is **BOTH** (a host also uploads its cells, `terrain` UP as in the Atlas), and the server
keeps the cells in `<world>/cyberblock/nightcity-map/` in the Atlas region format (tiles of 64 × 64 two-metre cells, one big-endian short
per cell, regions of 8 × 8 tiles). A client that needs tiles it does not have asks for them:

| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:act` `t:"maptile"` | C2S | `tiles:[tx, tz, …]` 1 to 32 tiles (2 to 64 whole numbers, `\|c\| ≤ 1048576`), `have:[v, …]` one whole number 0..2^32 − 1 per tile, in the same order: the version this client holds (0 = none; a missing `have` means all 0) | the guest Atlas or radar needs tiles, or polls the ones it draws (each at most every 30 s) |
| `cyberblock:ui` `t:"maptile"` | S2C | `tx`, `tz`, `v` (the tile's version: the CRC32 of its 8192 bytes, 1..2^32 − 1; **0** when the server knows nothing there), `c` (standard base64 of the tile's 8192 raw bytes: 4096 big-endian shorts, `c[z · 64 + x]` from the north-west corner, 0 unknown, else `((y + 4000 + 1) << 1) \| water`; **absent when `v` is 0**) | one message per asked tile whose version differs from its `have`; nothing for the others |

- **Limits.** A request is the whole message: no request id (the answers carry `tx`/`tz`). Each player may receive 64 tiles per second
  (burst 128); only tiles actually sent use a token, and when the bucket is empty the rest of that request is dropped (the client asks
  again on its next poll). A request that breaks a limit (an odd or too long `tiles`, a `have` of another length, a coordinate that is not
  whole or past the limit, a version outside 0..2^32 − 1) is dropped whole.
- **Versions.** A tile whose version equals `have` is not sent again, so polling costs nothing until the map changes; asking for a tile
  the server does not have with `have` 0 also sends nothing. A receiver drops an answer whose `c` is not base64 of exactly 8192 bytes, or
  that lacks `c` with `v` > 0.
- `ui` is server-to-client by section 1.3, so the request travels as `act`. The server side is 3R's `NightCityMap`, the client side 3C's
  `AtlasWire` and `ServerMap`.

### Phase 3 names
- **Material codes** (`ground.m`, `shared/vectors.json` `materials.codes`) — `unknown`, `concrete_light`, `concrete_dark`, `asphalt`, `sidewalk`, `glass`, `metal`, `brick`, `tile`, `ground`, `water`, then `neon_<dye>` ×16
- **Weather names** (`env.weather`) — `clear`, `cloudy`, `rain`, `storm`, `fog`, `smog`, `sandstorm`, `other`
- **Teleport reasons** (`vteleport.why`) — `meetup`, `holo`, `hub_exit`
- **Teleport results** (`vteleported.reason`) — `""`, `no ground`, `timeout`, `blocked (<why>)`

### Phase 3 log lines
In-game checks grep for them (Re-test 3 in `docs/TESTING-PLAN.md`). The exact text belongs to the packages that print them (3R, 3E on
the server, 3S and 3D in Cyberpunk); the integrator copies the final lines into Re-test 3.
- Each feature that has a fallback prints **one line saying which runtime path is active**: the material source (`material`, `meshAppearance` or
  heuristic), the weather reader, the teleport method and the park method. Scripts print `park: on|off (<reason>)`.
- The server logs replica changes, Meet Up requests and the environment changes of the primary host.

### Phase 3 examples
One message per line (JSON Lines). Every new `t` has a line; `ground` and `unground` show the new fields.

Phase 3 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"ground","c":[10,-21,62,63,11,-21,62,63,12,-21,64,70],"m":[3,3,7]}
{"t":"unground","c":[10,-21,64,66],"seen":true}
{"t":"env","time":[22,30],"weather":"rain"}
{"t":"vteleported","id":4,"ok":true,"reason":"","p":[-1230.5,64.0,820.5]}
{"t":"parked","on":true,"ok":true,"reason":""}
```

Phase 3 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"vteleport","id":4,"p":[-1230.5,64.0,820.5],"yaw":90.0,"why":"meetup"}
{"t":"park","on":true}
```

Phase 3 examples, client to server (`cyberblock:act`, `t:"maptile"`):
```jsonl
{"tiles":[-2,5,-1,5,0,0],"have":[0,0,17]}
```

The answers to that request: tile (-2, 5) in full (its long `c` holds 8192 bytes), nothing for (-1, 5) (unknown, asked with `have` 0)
and `v` 0 for (0, 0) (the client held version 17, the server knows nothing there now).

Phase 3 examples, server to client (`cyberblock:ui`, `t:"maptile"`):
```jsonl
{"tx":-2,"tz":5,"v":1372718144,"c":"H8Ifwh/CH8Ifwh/CH8Ifwh+7AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}
{"tx":0,"tz":0,"v":0}
```

## Phase 4 (the device layer)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows 4), section 1.11 and section 8, with the dated Phase 0
amendment after section 1.9 (`size` omitted, `st` ranges, `fl` bits, `by` rule, the `act` list, the `unsupported` answer). Night
City's **devices** (doors, gates, lights, cameras, terminals, vending machines, traffic lights, lifts, turrets, screens, containers)
become Minecraft blocks on the server, and a player's action on such a block reaches the real device in its owner's Cyberpunk game.
Positions and yaw are already in Minecraft convention (the DLL applies the [ground alignment](#ground-alignment-re-test-2) offset
before it sends, as for `npcs`).

### Phase 4: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `devices` | `list:[{id:"<uint64 as decimal string>", cls, kind, p:[x,y,z], yaw, st, fl}]`, at most 64 entries, nearest first; `cls` is the Cyberpunk class name (1 to 48 characters), `kind` one of the device kinds below, `p` the device's origin in Minecraft convention, `yaw` its facing, `st` its state, `fl` its flag bits; there is no `size` (Minecraft takes the box from `device_map`) | the devices within 48 m of V: every entry that is new or changed, at most every 0.5 s, plus **the full list every 5 s**; UP |
| `devstate` | `id` the device's id, `st`, `fl`, `by` ∈ `npc`, `host`, `guest`, `script` | one device changed state (and who caused it); UP |
| `devresult` | `id` (the `devact` request id), `ok:bool`, `reason` (`""` when `ok`, else at most 128 UTF-8 bytes) | the answer to every `devact`; UP |

### Phase 4: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `devact` | `id` int ≥ 0 (a request id the session issued), `dev` the device's id (the decimal string of `devices[].id`), `act` one of the device acts below | a player used a device block (a guest, or the host's own Steve); DOWN to the device's owner only |

### Phase 4 semantics and limits
- **Kinds and toggle bits** (`devices[].kind`, the toggle bits of `cyberblock:perms.devices`): `door` 1, `gate` 2, `light` 4, `camera` 8, `terminal` 16,
  `vending` 32, `traffic` 64, `elevator` 128, `turret` 256, `screen` 512, `container` 1024 (megaplan section 1.11). The scripts' class
  table decides the kind of each class (`Door` is `door`; garage doors are `Door` types and road barriers `RoadBlock`, both `gate`;
  `ElectricLight` is `light`; `SurveillanceCamera` `camera`; `Terminal` and `Computer` `terminal`; `VendingMachine` `vending`;
  `TrafficLight` `traffic`; `LiftDevice` `elevator`; `SecurityTurret` `turret`; `TV` and the billboard devices `screen`).
- **State** (`st`, a whole number -2 to 1): a door's is `EDoorStatus` (`SEALED` -2, `LOCKED` -1, `CLOSED` 0, `OPENED` 1), every other device's is
  `EDeviceStatus` (`DISABLED` -2, `UNPOWERED` -1, `OFF` 0, `ON` 1). **Flags** (`fl`, a whole number 0 to 31): 1 locked, 2 secure, 4 quest-locked
  (a sealed door or a disabled device), 8 broken, 16 powered (the state is not `UNPOWERED`). Phase 4 carries nothing else about a device (a
  lift's floor, a traffic light's colour or a camera's alarm have no field in this contract).
- **Ids.** `devices[].id`, `devstate.id` and `devact.dev` are the device's id as a decimal string, exactly like `npcs[].id` (`0` is never a
  device). The scripts use the device's entity-id hash when it is stable and the hash of its position rounded to 25 cm plus its class when it
  is not (a moved device is then a new device); ids are only meaningful together with the owner session. `devact.id` and `devresult.id` are
  a request id (an int ≥ 0 the server issued for that owner), echoed back.
- **The list.** A `devices` message is not marked as full or partial: every entry in it is the device's current data, and **the server keeps
  a device alive for as long as it shows up in some `devices` list**. The full list every 5 s therefore keeps every device within 48 m
  alive, and a device that is in no list for 10 s is gone (it left the 48 m, or Night City unloaded it). An entry that fails its limits is
  dropped (counted) and the others are kept; a list over 64 entries is cut at 64 (nearest first). The server builds nothing from `devices`
  for a session that is not active.
- **`devstate`** says that a device changed and who did it: `host` when the executor is V, `npc` when it is a person of Night City (a
  pedestrian opening a door shows in Minecraft), `script` when there is none, and `guest` when the change came from a guest's `devact` (the
  DLL remembers that request for 2 s). Minecraft updates the block's state from `devstate`, never from its own guess.
- **`devact`.** The `act` list is `open`, `close`, `lock`, `unlock`, `on`, `off`, `toggle`, `call`, `buy`, `disable`, `enable`, `friendly`,
  `breach`, `distract`; any other value is an invalid message (dropped and counted). The DLL runs **`open`, `close`, `toggle`, `lock`,
  `unlock`, `on`, `off`, `disable`, `enable`, `distract` and `friendly`** through the device's own persistent-state actions and answers
  `devresult ok:true` once it did (and sends the `devstate`); `call`, `buy` and `breach` are not Cyberpunk actions (a lift call is unproven,
  vending is a Minecraft shop paid in eddies, Breach is a Minecraft minigame whose daemons send the supported acts), so the DLL answers
  `{ok:false, reason:"unsupported"}`. The server greys those controls (`cyberblock.device.unsupported`) and does not send them. A device
  that is quest-locked is never touched (`reason` `quest locked`), and a device that is gone answers `device gone`; the reason is free
  text of at most 128 UTF-8 bytes, `""` exactly when `ok` is true.
- **Who may send it.** `devact` is DOWN to the device's **owner only**. A guest's request needs the owner's `devices` toggle **and** the bit of the
  device's kind in `cyberblock:perms.devices`; a host acting on its own game is always allowed; a refused request still leaves the Minecraft
  block as it was and shows `cyberblock.guest.blocked`. The block changes only after `devresult ok:true` (or the next `devstate`), so Minecraft
  never shows a state Night City refused. `/cyberblock guests devices <type> on|off` flips one kind's bit (and `/cyberblock guests devices on|off`
  the whole layer).
- **Validation (receivers).** `devices.list` has at most 64 entries; `id` is a decimal string below 2^64; `cls` 1 to 48 characters; `kind` one of
  the eleven names; `p` finite with `|c| < 3e7`; `yaw` finite; `st` -2..1; `fl` 0..31; `devstate.by` one of the four names; `devresult.reason` at most
  128 UTF-8 bytes and `""` when `ok` is true; `devact.id` a whole number ≥ 0; `devact.dev` a decimal string; `devact.act` one of the fourteen names. A
  bad message is counted and dropped, never fatal. The rate limit (10 per second, burst 40) and the sanity radius of
  [Multiplayer: security](#multiplayer-security-rate-limits-and-guest-toggles) apply to `devices` and `devstate`; `devresult` also needs a request
  id the session issued.
- **Doors are not walls.** A probe ray that hits a device is cast again past it (Phase 3), and the server never makes a replica block inside a
  device's box: the device is the block, the replica stays out of its way. Quest-locked devices show greyed (`cyberblock.device.quest_locked`).
- **Features.** `hs_ack.features` bit 2 (devices) is set by a Phase 4 server.

### Phase 4: payloads (client ↔ server)
| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:act` `t:"devact"` | C2S | `pos:[x, y, z]` the block the player used (three whole numbers, `\|c\| ≤ 30000000`), `act` one of the fourteen device acts | a player right-clicks a device block (a door, a lamp switch, a camera, a lift call button, a turret); `json` is at most 8000 bytes like every `act` |

- **Which device.** The server finds the device whose block stands at `pos` (any block of a multi-block device counts) and takes its owner and id from
  its own registry, so the client never names an owner or an id: a `pos` with no device block is dropped (counted). The server turns the request into the
  DOWN `devact` above (with a fresh request id remembered for the asker) after the permission check.
- **Answers.** A `devresult` is routed to the asker the request id was issued for; `ok:false` shows its reason on the action bar. The next
  `devstate` (or the `devresult ok:true`) updates the block for everyone.

### Phase 4 names
- **Device kinds** (`devices[].kind`, in bit order) — `door`, `gate`, `light`, `camera`, `terminal`, `vending`, `traffic`, `elevator`, `turret`, `screen`, `container`
- **Device acts** (`devact.act`) — `open`, `close`, `lock`, `unlock`, `on`, `off`, `toggle`, `call`, `buy`, `disable`, `enable`, `friendly`, `breach`, `distract`
- **Supported acts** (the DLL runs them) — `open`, `close`, `toggle`, `lock`, `unlock`, `on`, `off`, `disable`, `enable`, `distract`, `friendly`
- **State changers** (`devstate.by`) — `npc`, `host`, `guest`, `script`
- **Flag bits** (`devices[].fl`) — 1 locked, 2 secure, 4 quest-locked, 8 broken, 16 powered

### Phase 4 log lines
In-game checks grep for them (Re-test 4 in `docs/TESTING-PLAN.md`). The exact text belongs to the packages that print them (4S and 4D in Cyberpunk,
4M on the server); the integrator copies the final lines into Re-test 4.
- Each device feature that has a fallback prints **one line saying which runtime path is active**: where the **ids** come from (the entity hash or the
  position fallback), how **changes** are found (the events of `Device` or the 2 Hz poll within 48 m), and whether the **device-box filter** for probe
  hits is needed (rays that hit a device are cast past it, or hits inside a known device box are dropped).
- The server logs the devices it placed and removed, and every `devact` it routed or refused.

### Phase 4 examples
One message per line (JSON Lines). Every new `t` has a line; the list shows a door, a light, a camera, a vending machine, a lift and a sealed (quest-locked) door.

Phase 4 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"devices","list":[{"id":"11529215046068469763","cls":"Door","kind":"door","p":[-1238.5,64.0,811.5],"yaw":90.0,"st":0,"fl":16},{"id":"11529215046068469764","cls":"ElectricLight","kind":"light","p":[-1236.5,66.0,809.5],"yaw":0.0,"st":1,"fl":16},{"id":"11529215046068469765","cls":"SurveillanceCamera","kind":"camera","p":[-1233.5,67.0,812.5],"yaw":-135.0,"st":1,"fl":16},{"id":"11529215046068469766","cls":"VendingMachine","kind":"vending","p":[-1241.5,64.0,806.5],"yaw":180.0,"st":1,"fl":16},{"id":"11529215046068469767","cls":"LiftDevice","kind":"elevator","p":[-1230.5,64.0,815.5],"yaw":0.0,"st":0,"fl":16},{"id":"11529215046068469768","cls":"Door","kind":"door","p":[-1244.5,64.0,811.5],"yaw":90.0,"st":-2,"fl":20}]}
{"t":"devstate","id":"11529215046068469763","st":1,"fl":16,"by":"guest"}
{"t":"devresult","id":7,"ok":true,"reason":""}
```

Phase 4 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"devact","id":7,"dev":"11529215046068469763","act":"open"}
```

Phase 4 examples, client to server (`cyberblock:act`, `t:"devact"`):
```jsonl
{"pos":[-1239,64,811],"act":"open"}
```

## Shared memory `Local\CyberblockFrame`
Exactly the reference `FrameExporter` layout (see its Javadoc) with magic `0x52464243` ("CBFR"). The
reference flags (zero-to-one depth, bottom-up rows, reversed-Z) are kept.

Reference Javadoc, reproduced for convenience
(`third_party/universal-modder/examples/minecraft-gta5-passthrough/mc/src/client/java/dev/rehan/passthrough/client/FrameExporter.java`,
all little-endian; `tools/shm.py` implements exactly this):
```
header (4096 bytes)
  0 int magic   4 int version (1)   8 int header bytes (4096)   12 int slot count (3)
  16 long slot stride   24 int max width   28 int max height
  32 long publish counter (bumped after each completed slot)   40 int latest slot (-1: none yet)   44 int Minecraft pid
  256 + 128 * i: slot i
    +0 long seq (odd while being written)   +8 long Minecraft frame   +16 long host frame
    +24 int width   +28 int height   +32 float near   +36 float far   +40 float vertical fov (degrees)
    +44 int flags: 1 = depth in [0, 1] (zZeroToOne), 2 = rows bottom-up, 4 = reversed Z (1 = near, 0 = far/empty)
    +48 double camera x, +56 y, +64 z   +72 float yaw   +76 pitch   +80 roll   +84 int first person
    +88 long capture time (System.nanoTime)   +96 long publish time
slot i data at 4096 + i * stride, each layer width * height * 4 bytes:
  world colour RGBA8 (premultiplied alpha), world depth float32, overlay RGBA8 (hand + HUD, premultiplied alpha)
```
Readers use the seqlock: read the slot's `seq` (skip if odd), copy, re-read `seq`; if it changed, discard.
