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
| `npcs` | `list:[{id:"<uint64 as decimal string>", p:[x,y,z] feet, yaw, kind:"human"\|"vehicle", hostile:bool, dead:true (optional)}]` | 10 Hz while active: everything alive within 48 m, nearest first, at most 48 entries; an id missing from the list is gone (layer 2). Phase 3: an NPC that died (or is defeated) or a vehicle that was destroyed, whoever caused it, is listed once more after the living with `dead:true` (its last sighting; the key is only ever `true` and absent otherwise), and Minecraft leaves a body for 3 s or a wreck for 5 s; `killed` stays the report of V's own kills (XP). Phase 5 adds optional tags to each entry (see [Phase 5](#phase-5-figures-and-skins)) |

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
| `cyberware` | **re-test 2:** `list:[{id, on}]`, `id` one of the 19 cyberware ids (below; `firewall` is the 18th, Phase 6, and `netrunner` the 19th, Phase 8), `on` 0 or 1, at most 32 entries | after each host `hello`, on join, after every install or removal; every `on` 0 on disconnect. Kept while the host is not Active |
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
  - **Re-test 2:** `align.k` and `cam.lv` are integers 0..63; `cyberware.list` has ≤ 32 entries (the 19 ids are
    each sent once; Phase 6 added `firewall` as the 18th and Phase 8 `netrunner` as the 19th); `cyberfx.seconds` is 0..30; `flight.ts` is finite, 0..1e13; `flight.kind` is `glide` or `launch`.
  - `hack.hack` must be one of the hack names and every `perks[].id` one of the perk ids below; anything else drops the
    message (or the entry).
  - **Validation of the re-test 2 messages (receiver):**
    - `align.k` must be an integer 0..63; a bad message is counted and the old `k` is kept;
    - `cyberware[].id` must be one of the 19 cyberware ids and `on` 0 or 1; a bad entry is dropped, the others stay;
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
- **Cyberware ids** (`cyberware[].id`, re-test 2, the fixed order of the list) — `mantis_blades`, `gorilla_arms`, `monowire`, `projectile_launch_system`, `reinforced_tendons`, `fortified_ankles`, `lynx_paws`, `sandevistan`, `berserk`, `cyberdeck`, `kerenzikov`, `optical_camo`, `subdermal_armor`, `kiroshi_optics`, `biomonitor`, `second_heart`, `ram_upgrade`, `firewall`, `netrunner`
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
{"t":"cyberware","list":[{"id":"mantis_blades","on":1},{"id":"gorilla_arms","on":0},{"id":"monowire","on":0},{"id":"projectile_launch_system","on":0},{"id":"reinforced_tendons","on":1},{"id":"fortified_ankles","on":0},{"id":"lynx_paws","on":0},{"id":"sandevistan","on":1},{"id":"berserk","on":0},{"id":"cyberdeck","on":0},{"id":"kerenzikov","on":0},{"id":"optical_camo","on":1},{"id":"subdermal_armor","on":0},{"id":"kiroshi_optics","on":1},{"id":"biomonitor","on":0},{"id":"second_heart","on":1},{"id":"ram_upgrade","on":0},{"id":"firewall","on":0},{"id":"netrunner","on":0}]}
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
| `parked` | UP | session (server), **Phase 7** (the hub) | the answer to `park`; the DLL builds it from Phase 3, but no Phase 3 server sends `park`, so a Phase 3 relay drops a `parked` (counted) and has no rate row; from Phase 7 the client relay and the server accept it (7H adds it to the client's UP set and a `RelayGuard` row, 2 per second, burst 4; the hub reads it: see [Phase 7](#phase-7-npc-interaction-the-afterlife-hub-and-the-holo-table)) |
| `devices` | UP | the device layer per session (server), Phase 4 | the devices within 48 m: changes and a full list every 5 s |
| `devstate` | UP | the device layer per session (server), Phase 4 | one device changed state |
| `devresult` | UP | the device layer (server), routed to the request's asker, Phase 4 | a reply: it must carry a request id the session issued |
| `spawnresult` | UP | the Overlord's spawns (server), routed to the request's owner, Phase 8 | a reply: it must carry a request id the session issued |
| `feed` | BOTH | the host's own client shows it (LOCAL); UP into the server's photo feed (`FeedHub`), Phase 6 | at most 1 per second while a feed is requested; the server forwards it to the viewers as `cyberblock:ui` `feed` |
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
| `park` | DOWN | session (server), **Phase 7** (the hub) | to the host's session only; the DLL and scripts handle it from Phase 3, the server first sends it in Phase 7 (with every `hub`) |
| `hub` | DOWN | `HubTravel` (server), Phase 7 | to the host's session only: a host entered or left the Afterlife hub, the DLL switches the compositor to full-screen Minecraft or back |
| `devact` | DOWN | the device layer (server), Phase 4 | to the device's owner only; the owner's `devices` toggle and the device type's bit when a guest asked |
| `rhack` | DOWN | the interaction map routing (server), Phase 6 | to the target's owner only; the owner's `hacks` toggle (and `devices` for device effects) when a guest asked |
| `daemon` | DOWN | the Breach results (server), Phase 6 | to the breached device's owner only; the owner's `hacks` toggle when a guest breached |
| `feedreq` | DOWN | the photo feed hub (server), Phase 6 | to that host's session only: on with the first viewer, off after the last |
| `spawn` | DOWN | the Overlord's spawns (server), Phase 8 | to the owner's session only; the owner's `spawns` toggle, after the server's own fairness checks and warning |
| `heat` | DOWN | Server Heat (server), Phase 8 | to that host's session only: the tier changed, and again after every host `hello`; the owner's `spawns` toggle |
| `attitude` | DOWN | Gang Wars reputation (server), Phase 8 | to that host's session only; the owner's `spawns` toggle |
| `glitch` | DOWN | the player hacks (server), Phase 8 | to the victim's session only (a linked player hit by Reboot Optics) |

### Multiplayer: new messages
| `t` | class | fields | when |
|---|---|---|---|
| `hstate` | UP (client → server, built by the client; never on the DLL wire) | `active`, `vehicle`, `air`, `cr`, `held`, `fly`, `launch`, `seat` (booleans), `lv` int 0..63, `feetCpZ` a finite number or absent | at 5 Hz and on change, from `cam`, `state` and `Flight`; it replaces the JVM-shared `Bridge.hostAirborne`, `hostCrouching`, `hostHeld`, `hostInVehicle`, `hostFeetCpZ`, `hostStands` and `Cyberblock.launching` on the server |
| `session` | client → DLL | `state` ∈ `none, host, guest, refused`, `reason` (≤ 128 UTF-8 bytes, `""` when there is none), `perms:{explosions, damage, hacks, devices, spawns}` (booleans) | after every handshake result, server disconnect and reconnect |
| `pteleport` | DOWN → DLL | `pos:[x, y, z]` Minecraft position, finite with `\|c\| < 3e7` | a command (`/tp`) moved Steve: the position he was teleported to, three decimals. **Undocumented until Phase 1**; the DLL ignores it today like any unknown `t` |
| `survey_cmd` | DOWN, client only | `op` ∈ `start, redo, stop, status` | `/cyberblock survey` on the server asks the issuing host's client; **the client handles it (`SurveyClient`) and never forwards it to the DLL** |
| `hello` | DLL → client (**extends layer 1**) | adds `ver` (the DLL version, ≤ 32 chars) | on connect, as Minecraft's `hello` already has |

- **`session`:** on `none`, `guest` or `refused` the DLL and the scripts clean Cyberpunk state exactly as on a link drop
  (collision boxes reset, puppets despawned, tethers released, Minecraft pause released, survey stopped, parked V released;
  Phase 6: every pending blackout, traffic and vehicle hack effect restored and its save lock released, see "Phase 6 semantics and limits")
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
| `feed` | 2 | 4 | Phase 6 (at most 1 per second while a photo feed is requested) |
| `parked` | 2 | 4 | Phase 7 (a reply to `park`; one hub entry or exit at a time per host) |
| `spawnresult` | 10 | 40 | Phase 8 (a reply to `spawn`; the server caps simultaneous spawns) |

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
  `breach`, `distract`; the server never sends another value, and a DLL that gets one anyway answers `unsupported`. The DLL runs **`open`, `close`, `toggle`, `lock`,
  `unlock`, `on`, `off`, `disable`, `enable`, `distract` and `friendly`** through the device's own persistent-state actions and answers
  `devresult ok:true` once it did (and sends the `devstate`); `call`, `buy` and `breach` are not Cyberpunk actions (a lift call is unproven,
  vending is a Minecraft shop paid in eddies, Breach is a Minecraft minigame whose daemons send the supported acts), so the DLL answers
  `{ok:false, reason:"unsupported"}`. The server does not send them: it greys those controls (`cyberblock.device.unsupported`) for
  `call` and `breach`, and a vending machine opens its Minecraft shop instead of sending `buy`. A device
  that is quest-locked is never touched (`reason` `quest locked`), and a device that is gone answers `device gone`; the reason is free
  text of at most 128 UTF-8 bytes, `""` exactly when `ok` is true.
- **Who may send it.** `devact` is DOWN to the device's **owner only**. A guest's request needs the owner's `devices` toggle **and** the bit of the
  device's kind in `cyberblock:perms.devices`; a host acting on its own game is always allowed; a refused request still leaves the Minecraft
  block as it was and shows `cyberblock.guest.blocked`. The block changes only after `devresult ok:true` (or the next `devstate`), so Minecraft
  never shows a state Night City refused. `/cyberblock guests devices <type> on|off` flips one kind's bit (and `/cyberblock guests devices on|off`
  the whole layer).
- **Validation (receivers).** `devices.list` has at most 64 entries; `id` is a decimal string below 2^64; `cls` 1 to 48 characters; `kind` one of
  the eleven names; `p` finite with `|c| < 3e7`; `yaw` finite; `st` -2..1; `fl` 0..31; `devstate.by` one of the four names; `devresult.reason` at most
  128 UTF-8 bytes and `""` when `ok` is true; `devact.id` a whole number ≥ 0; `devact.dev` a decimal string; `devact.act` a string (a name outside the fourteen is answered `unsupported`, not dropped). A
  bad message is counted and dropped, never fatal. The rate limit (10 per second, burst 40) and the sanity radius of
  [Multiplayer: security](#multiplayer-security-rate-limits-and-guest-toggles) apply to `devices` and `devstate`; `devresult` also needs a request
  id the session issued.
- **Doors are not walls.** A probe ray that hits a device is cast again past it (Phase 3), and the server never makes a replica block inside a
  device's box: the device is the block, the replica stays out of its way. Quest-locked devices show greyed (`cyberblock.device.quest_locked`).
- **Features.** `hs_ack.features` bit 2 (devices) is set by a Phase 4 server.

### Phase 4: payloads (client ↔ server)
| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:act` `t:"devact"` | C2S | `pos:[x, y, z]` the vending machine's block (three whole numbers, `\|c\| ≤ 30000000`), `act` exactly `buy`, `line` the id of the shop line to buy (a string of 1 to 32 characters, one of the `id`s of the `shop` the server sent) | the vending screen's purchase, and nothing else; `json` is at most 8000 bytes like every `act` |
| `cyberblock:ui` `t:"shop"` | S2C | `pos:[x, y, z]` the machine's block, `title` at most 64 characters (`""`: the screen's own "Vending machine"), `eddies` the player's Minecraft eddies as the server counts them (a whole number ≥ 0), `result` the answer to the player's last purchase, one of the shop results below (`""` none), `lines:[{id, item, n, price, stock}]` at most 27 lines: `id` 1 to 32 characters, `item` an item id (`namespace:path`), `n` how many one purchase gives (1..64), `price` in eddies (0..1000000), `stock` how many purchases are left (-1 not known, else 0..9999) | a player right-clicks a vending machine (the server opens its shop), and again after each purchase with the new balance, counts and result |

- **Right-clicks send no payload.** A right-click on a device block (a door, a lamp switch, a camera, a lift call button, a turret, a vending
  machine) is handled on the server through Minecraft's own use path: the server finds the device whose block stands there (any block of a
  multi-block device counts) and takes its owner and id from its own registry, so the client never names an owner or an id. A second use by the
  same player within 4 ticks is ignored. After the permission check the server turns the use into the DOWN `devact` above (with a fresh request id
  remembered for the asker), opens the shop of a vending machine (paid in Minecraft eddies, never sent DOWN), or greys the control (`call`,
  `breach`, a quest-locked device, a locked door that would open: a message on the action bar, nothing is sent).
- **Which device (the `buy` payload).** `cyberblock:act` `t:"devact"` is only the vending screen's purchase. Any other `act`, a `pos` with no device
  block, or one farther than 8 blocks from the player's eyes is dropped (logged at debug level, not counted, no message). The server checks the
  line, the price and the player's eddies itself (the client is never trusted with a price or a balance), pays in Minecraft eddies, and sends the
  `shop` again with the `result`; a purchase never goes DOWN.
- **Shop results** (`shop.result`) — `""` (no purchase yet), `ok`, `poor` (not enough eddies), `stock` (sold out), `full` (no room), `far`
  (out of reach), `gone` (the machine or its line left), `refused` (the host's toggles); the screen shows `cyberblock.shop.result.<code>`.
  A `shop` that breaks a limit is dropped by the client.
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
{"pos":[-1242,64,806],"act":"buy","line":"0"}
```

Phase 4 examples, server to client (`cyberblock:ui`, `t:"shop"`):
```jsonl
{"pos":[-1242,64,806],"title":"","eddies":85,"result":"ok","lines":[{"id":"0","item":"minecraft:honey_bottle","n":1,"price":15,"stock":-1},{"id":"1","item":"minecraft:bread","n":2,"price":10,"stock":4}]}
```

## Phase 5 (figures and skins)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new fields. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (row 5) and section 1.8 (row 5), with the dated Phase 0
amendment (items 7 of both) as the working contract. Night City's people and cars carry **tags** so the server can pick a skin or a
vehicle model for each proxy (`skin_map` and `vehicle_map` are content, megaplan 1.7) and the client can pose it. There is no new `t`:
the layer 2 `npcs` list gains optional keys per entry, and the redscript side hands them to the DLL through a new native,
`Cyberblock_SubmitNpcs2` (the old `Cyberblock_SubmitNpcs` stays and sends the old entries).

### Phase 5: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `npcs` | **extends layer 2 and Phase 3:** each `list` entry adds optional `role` (vendor type name), `fac` (faction), `arch` (archetype), `g` (0 unknown, 1 male, 2 female), `anim` (bit mask 0..255, see the names below), `vc` (vehicle class `<type>/<model>`, vehicles only), `col` (`#RRGGBB`, vehicles only) and `hp` (0..1 fraction of health) | as before: 10 Hz while active, UP |

### Phase 5 semantics and limits
- **Where the tags come from** (Phase 0 check (f)). `role` is the vendor type name (`RipperDoc`, `Clothes`, …) and absent for everyone
  else: fixers, bartenders, medics and mechanics come from the server's content (`npc_role`, `skin_map`), not from the game. `fac` is the
  faction (`Character_Record.Affiliation().EnumName()`), `arch` the archetype (`ArchetypeData().Type().EnumName()`), `g` the resolved
  gender name (`GetResolvedGenderName`: male 1, female 2, anything else 0). `vc` is `<type>/<model>` from `Vehicle_Record.Type()` and
  `Model()` (for example `Car/Turbo`, `Bike/Arch`). `hp` comes from the Health stat pool; a figure whose health is unknown has no `hp`.
- **`anim` bits.** Set by the scripts: 4 aim, 8 fire, 16 ragdoll, 32 dead, 64 crouch, 128 combat. **Bits 1 (walk) and 2 (run) are set by
  the DLL**, because the game has no script reader for them: from the horizontal speed between two submits (about 0.1 s apart) of the
  same id. Walk starts above 0.6 m/s and ends below 0.3 m/s, run starts above 3.2 m/s and drops back to walk below 2.6 m/s (two
  thresholds each, so a standing or cruising figure does not flicker); faster than 15 m/s is a respawn or a fast travel and sets
  nothing, and so does a gap of more than 1 s between two sightings or the first sighting. A dead or ragdolling figure never walks, and
  a vehicle has `g` 0 and `anim` 0 (Minecraft takes a car's speed from its positions).
- **Colour.** `col` is only present when the car's appearance matches a `VehicleColorTemplate` (`MainColor`: three numbers). The scripts
  send the triple packed in one number (first × 65536 + second × 256 + third, each 0..255, −1 for no template) and **the DLL converts it
  once**, reading the triple as hue, saturation and brightness (each 0..255, hue 0..255 is 0..360 degrees) until Phase 0's CET line F2
  says otherwise; it logs `npcs: colour format hsb (unconfirmed)` once. `shared/vectors.json` `colour` pins the conversion for both
  readings. A car without `col` is drawn in its class's default colour from `vehicle_map`.
- **Limits and validation (DLL and receivers).** `role`, `fac`, `arch` and `vc` are 1 to 32 characters of `A-Z a-z 0-9 _ . / -`; anything
  else is dropped (the key is left out, never cut short). `g` is 0, 1 or 2. `anim` is a whole number 0..255. `col` is `#` and six
  hex digits. `hp` is a number 0..1. `vc` and `col` belong to `kind:"vehicle"`. All keys are optional and unknown ones are ignored: a
  list from an older DLL (no tags) must still produce proxies. The tag arrays of `Cyberblock_SubmitNpcs2` are empty (the key is left
  out of every entry) or as long as `ids`; any other length drops the whole list (one warning). At most 48 entries as before; an entry
  is at most about 330 bytes, so a full list stays under the 30000-byte payload cap.
- **Fallbacks** (Phase 0 f, applied now). No role from the game: skins by faction and threat plus the content roles. No walk or run
  reader: the DLL derives them from deltas (above). No colour: the class default colour. The scripts send the tags they can read; the DLL
  never invents one.
- **The natives.** `Cyberblock_SubmitNpcs2(ids, feet, yaws, vehicles, hostile, dead, roles, factions, genders, archetypes, anims, vclass,
  colours, hp)`: the six arrays of `Cyberblock_SubmitNpcs` followed by `roles`, `factions`, `archetypes`, `vclass` (`array<CName>`),
  `genders`, `anims`, `colours` (`array<Int32>`) and `hp` (`array<Float>`), as the megaplan's Phase 0 amendment names them
  (item 7 of section 1.8). `genders` carries 0, 1 or 2, `colours` the packed triple or −1.

### Phase 5 names
- **Animation bits** (`npcs[].anim`, bit 1 first) — `walk`, `run`, `aim`, `fire`, `ragdoll`, `dead`, `crouch`, `combat`
- **Genders** (`npcs[].g`, 0 first) — `unknown`, `male`, `female`

### Phase 5 log lines
`Cyberblock.log` says which runtime path is active, so the in-game test can tell fallback from full tags (Re-test 5 in
`docs/TESTING-PLAN.md`). Each line has one source:
- the scripts (`Npcs.reds`): `npcs: tags full (…)` when at least one person entry carries both a gender and an archetype,
  `npcs: tags faction only (…)` otherwise (the fallback); printed when people are near, once and again when the path changes.
  Lists with only cars print no tags line.
- the DLL: `npcs: colour format hsb (unconfirmed)` once, at the first list.
- the DLL, every 10 s: `npcs: N people (faction N, role N, archetype N; gender male N female N unknown N; walk N run N), N vehicles (colour N)`.
- the DLL: `Cyberblock_SubmitNpcs2: arrays differ in length (…)` once, when a list is dropped.

### Phase 5 examples
One message per line (JSON Lines): a gunner who is walking, aiming and firing, a female ripperdoc, a car with a colour, and a
person who just died (listed once more after the living, as in Phase 3).

Phase 5 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"npcs","list":[{"id":"9007199254740993","p":[-1243.0,64.9,808.5],"yaw":90.0,"kind":"human","hostile":true,"fac":"Maelstrom","arch":"Gunner","g":1,"anim":141,"hp":0.75},{"id":"18446744073709551615","p":[-1240.25,64.9,815.0],"yaw":-45.0,"kind":"human","hostile":false,"role":"RipperDoc","fac":"Civilian","arch":"Civilian","g":2,"anim":0,"hp":1.0},{"id":"4503599627370497","p":[-1252.0,64.9,820.5],"yaw":180.0,"kind":"vehicle","hostile":false,"fac":"Civilian","g":0,"anim":0,"vc":"Car/Turbo","col":"#3C4032","hp":1.0},{"id":"4503599627370499","p":[-1236.5,64.9,812.0],"yaw":0.0,"kind":"human","hostile":true,"dead":true,"fac":"NCPD","arch":"Gunner","g":2,"anim":32,"hp":0.0}]}
```

## Phase 6 (hacking and the interaction map)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows 6), section 1.8 (row 6), section 1.17 and section 10, with the
dated Phase 0 amendment (fallback (h): only streamed targets) as the working contract. A player on the Minecraft side hacks Night City with
redstone blocks and screens: a remote hack (the Vehicle Panel, the Traffic Tool, the Net Map), a Breach Protocol whose solved daemons do real
things in the owner's game, the **photo feed** of the host's own view, and the Comms Panel. Hacks that Cyberpunk already knows (`hack`, `scan`)
keep their rows above. Where the plan leaves a shape open (the optional `p`, `r` and `secs` of `rhack`, the reserved daemon id `alarm`, the
payloads) this section fixes it; the integrator confirms it against the packages 6B, 6I and 6R.

### Phase 6: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `hackresult` | **extends layers 3–6 and re-test 3:** it is also the answer to every `rhack` and every `daemon` (the same `id`, `ok:bool`, `reason` at most 64 characters, `""` on success); `upload` and `kind` are never sent for them | reply to every `rhack` and `daemon`; UP |
| `feed` | `w` exactly 128, `h` exactly 128, `px` standard base64 of exactly 16384 map-colour bytes (row by row, the top row first) | at most once a second and only while a feed is requested (`feedreq` `on:true`); BOTH: the host's own client shows it locally and relays it UP |

### Phase 6: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `rhack` | `id` (a request id, a whole number ≥ 0 the session issued), `target` (the decimal id of a car or a camera device, or `null` for an area), `hack` one of the remote hack names below; a **vehicle** hack also carries `secs` (a whole number 1..600, how long the effect lasts; the server sends 30); an **area** hack also carries `p:[x,y,z]` (the centre, Minecraft convention), `r` (a whole number 1..150 metres) and `secs` (a whole number, 1..600 for `traffic_switch`, `gridlock` and `blackout`, 0..600 for `outline` and `ping_area`, where 0 is a one-shot) | a player used the Vehicle Panel, the Traffic Tool or the Net Map; DOWN to the target's owner only |
| `daemon` | `id` (a request id), `dev` (the decimal id of the device that was breached), `list` (1 to 3 distinct daemon ids), `strength` (1..3) | a Breach was solved (or failed: the list is then exactly `["alarm"]`); DOWN to the device's owner only |
| `feedreq` | `on:bool` | the first viewer of the host's photo feed appears (`true`) and the last one is gone (`false`, also when the owner's `hacks` toggle removed the last guest viewer); DOWN to that host only |

### Phase 6 semantics and limits
- **The remote hacks and what each one needs.** The five **vehicle** hacks `veh_lock`, `veh_engine_off`, `veh_brake`, `veh_start`
  and `veh_jam` take a car's id as `target` and `secs` (the server sends 30) and carry no `p` or `r`. `cam_feed` takes a camera device's id as `target` and carries no
  `p`, `r` or `secs`: Cyberpunk answers `ok` and the camera's position and facing are already in the `devices` list, so Minecraft draws the
  replica from there (a true Cyberpunk camera view would take over the host's screen and is not built). The five **area** hacks
  `traffic_switch`, `gridlock`, `blackout`, `outline` and `ping_area` have `target:null` and **always** carry `p`, `r` and `secs`: `traffic_switch`
  switches the lights of the district around `p` for `secs` seconds (the server sends 60), `gridlock` stops the cars on the road segment around `p` (the server sends `secs` 60),
  `blackout` switches the district's devices off for `secs` seconds and back (the server sends `r` up to 150 and `secs` 180), `outline`
  reveals the figures of the area as the Ping chip does (`hackspread` `why:"ping"`, at most 16) and `ping_area` pings the area once (`secs` 0). The
  scripts use the traffic and district calls where they are proven; the written fallback is the Phase 4 devices (the traffic lights and the
  devices within 150 m of V).
- **Every effect ends; nothing a hack does outlives it.** The five vehicle hacks, `traffic_switch`, `gridlock` and `blackout` are **timed**: when
  their `secs` run out the scripts put back what the hack changed (the car's doors, engine, brake and jam as they were before the first hack on it,
  `veh_start` included; the lights and devices as they were). A second hack of the same kind on the same target restarts its timer and keeps the
  first saved state. `secs` 0 is accepted only for `outline` and `ping_area`, which change nothing that lasts. The scripts also restore **every
  pending effect at once** when the session goes to `none`, `guest` or `refused` and on a link drop (part of section 1.2's cleanup, before the
  `session: cleaned (<reason>)` line), and **a save never holds one**: while any timed effect is pending the scripts hold a save lock (source
  `n"CyberblockHack"`, the same mechanism as the parked V's `n"CyberblockPark"`), released when the last one is restored. V's own car and the car V
  sits in are not exempt (a guest braking the host's car is the headline check), but every effect on them is timed and restored the same way.
- **Range (the fallback of Phase 0 check (h), the working contract).** A remote action only reaches what the DLL streams: a `target` that is in no
  `npcs` or `devices` list of the session, or a `target` or `p` farther than 50 m from V, is answered
  `{ok:false, reason:"cyberblock.hack.out_of_range"}`. The server checks the same range before it sends, so a guest normally sees the
  message on the action bar and the DLL is never asked. A `hack` name outside the eleven is answered `unsupported`.
- **Daemons.** `daemon.list` holds ids of the server's `daemon` content (`[a-z0-9_]`, 1 to 32 characters, for example `open_door`,
  `disable_cameras`, `reveal_enemies`, `overload`); each id's real action runs at `strength` 1..3 (more and longer sequences solved = stronger).
  The id **`alarm`** is reserved for a failed Breach: it is always sent alone, and it raises the alarm of the device's area (a camera or
  combat alert). A daemon id the scripts do not know is answered `{ok:false, reason:"unsupported daemon"}` and the others of the list still
  run; the answer is one `hackresult` for the whole `daemon`, `ok` only when every id ran.
- **Ids and answers.** `rhack.id` and `daemon.id` come from the same per-owner request counter as `scan`, `hack` and `devact`; the `hackresult`
  echoes it and **must carry an id the session issued** (the existing envelope rule). Every `rhack` and every `daemon` is answered by exactly
  one `hackresult` (a message with a bad type is dropped and counted, as every receiver does). `hackresult.reason` for `rhack` and `daemon` is free text of
  at most 64 characters (`device gone`, `vehicle gone`, `unsupported`, `unsupported daemon`, `cyberblock.hack.out_of_range`).
- **Who may send them.** Both are DOWN to the **owner only**, for a guest's request too. A guest needs the owner's `hacks` toggle for every one
  (`/cyberblock guests hacks on|off`), and the ones that switch devices (`blackout`, `cam_feed` and a daemon on a device) also the owner's
  `devices` toggle; the host's own Steve is always allowed. A refused request changes nothing in Cyberpunk and shows `cyberblock.guest.blocked`.
  A Breach solved by a guest sends the `daemon` only after the server validated every pick (the client is never trusted with a result).
- **Who may watch the photo feed.** A guest's `feedreq` needs the owner's `hacks` toggle; the host's own client is always allowed. A blocked
  `feedreq` adds no viewer and shows `cyberblock.guest.blocked`. When the owner turns `hacks` off, the server removes that host's guest viewers
  **at once**: it stops forwarding `cyberblock:ui` `feed` to them and, when no viewer is left, sends the DLL `feedreq` `on:false`.
- **The photo feed.** `feedreq` `on:true` starts it: within 1 s of the request the DLL copies Cyberpunk's picture, scales it to 128 × 128, maps every
  pixel to the Minecraft map palette and sends `feed`, then at most one `feed` per second until `feedreq` `on:false` (a `session` that is not `host`
  also stops it). One `px` byte is `base × 4 + shade` (shade 0..3); the bytes 0..3 are the transparent colour (a pixel the DLL could not map). If
  the copy costs more than **0.5 ms of frame time** the DLL stops sending (`feed` is then simply absent and it logs one line) and Minecraft draws a
  top-down replica view instead (the written fallback: the replica render, low-resolution by design).
- **Validation (receivers).** `rhack.id` and `daemon.id` whole numbers ≥ 0; `target` a decimal string below 2^64 (not `0`) or `null`, exactly the
  `null` of the area hacks; `hack` one of the eleven names (any other string is answered `unsupported`, not dropped); `p` finite with `|c| < 3e7`;
  `r` 1..150; `secs` 1..600 on a vehicle hack and on `traffic_switch`, `gridlock` and `blackout`, 0..600 on `outline` and `ping_area`, absent on
  `cam_feed`; `daemon.list` 1 to 3 distinct strings of `[a-z0-9_]{1,32}` (a list that is not a list is dropped), `strength` 1..3 (a
  whole number); `feedreq.on` a boolean; `feed.w` and `feed.h` exactly 128 and `px` valid base64 of exactly 16384 bytes. A bad message is counted
  and dropped, never fatal. The rate limit of `feed` (2 per second, burst 4) and the sanity radius of
  [Multiplayer: security](#multiplayer-security-rate-limits-and-guest-toggles) apply to the UP `feed`; `hackresult` keeps its own (40 per second, burst 80).
- **Features and the implant.** `hs_ack.features` bit 8 (hacking) is set by a Phase 6 server. The cyberware list gains an 18th id, `firewall` (megaplan
  1.17): it has no effect in Cyberpunk, so a DLL that does not know it drops that one entry as the protocol says; in Minecraft it raises the Breach tier
  when its owner defends (Phase 8's player hacks).
- **Test commands** (dedicated test server with `-Dcyberblock.devtools=true`, run as the guest with `execute as`): `/cyberblock test breach <x> <y> <z>
  solve|fail` opens the Breach of the Net Port block at that position and plays it (`solve`: the server's own solution, `fail`: a wrong pick until the
  buffer is empty), the same server path as a right-click and the picks. The `breach` scenario of `tools/mpcheck.py` needs it.

### Phase 6: payloads (client ↔ server)
| Id | Direction | Fields | When |
|---|---|---|---|
| `cyberblock:act` `t:"rhack"` | C2S | `hack` one of the remote hack names; for a **vehicle** hack `target` (a whole number ≥ 0: the Minecraft entity id of the car figure); for `cam_feed` `pos:[x, y, z]` (the camera device's block, three whole numbers, `\|c\| ≤ 30000000`); for an **area** hack `pos` (the centre block) and `r` (a whole number 1..150); nothing else | a Vehicle Panel, Traffic Tool or Net Map action; at most 4 per second per player |
| `cyberblock:act` `t:"breach"` | C2S | `op` ∈ `pick`, `abort`; `id` (the puzzle id of the `breach` screen data, a whole number ≥ 0); for `pick` also `cell` (0..48, the grid index row by row) | the Breach screen: one pick per message, or the player closes it |
| `cyberblock:act` `t:"feedreq"` | C2S | `owner` (the host's player UUID as 36 lower-case characters with hyphens), `on:bool` | a Net Map or map item starts or stops watching that host's photo feed; the viewer repeats `on:true` every 5 s while it shows it; a guest needs the owner's `hacks` toggle |
| `cyberblock:act` `t:"comms"` | C2S | `to` (a player UUID, same form), `text` (0 to 80 characters), `pos` (`null`, or a block position of three whole numbers, `\|c\| ≤ 30000000`); `text` and `pos` are not both empty | the Comms Panel sends a message and/or a waypoint; at most one every 2 s |
| `cyberblock:ui` `t:"breach"` | S2C | `id` (the puzzle id, whole), `dev` (the device's decimal id), `size` 5..7, `grid` (`size`² codes, row by row, each one of `1C`, `55`, `BD`, `E9`, `7A`, `FF`), `buffer` 4..8, `picks` (the cells picked so far, at most `buffer`), `seqs:[{id, codes, ok}]` 1 to 3 daemon sequences (`id` a daemon id, `codes` 2 to `buffer` codes, `ok` true when the picks contain it in order), `total` (the timer in seconds, 1..300), `left` (seconds left, 0..`total`), `state` ∈ `run`, `solved`, `failed`, `timeout`, `aborted`, `strength` (0..3, 0 until the end) | a player right-clicks a Net Port, and again after every valid pick and at the end |
| `cyberblock:ui` `t:"feed"` | S2C | `owner` (the host's UUID), `w` exactly 128, `h` exactly 128, `px` (base64 of 16384 map-colour bytes, as in the DLL's `feed`) | the server forwards the host's `feed` to every player who asked for it (at most one per second per host) |
| `cyberblock:ui` `t:"comms"` | S2C | `from` (the sender's name, 1 to 32 characters), `text` (0 to 80 characters), `pos` (`null` or a block position) | the target's client shows a toast and, with `pos`, a locator-bar waypoint (Minecraft's own HUD, nothing goes to Cyberpunk) |

- **The server decides everything.** `rhack` names no owner and no Night City id: the server finds the car's owner and id from its own figure (as `scan`
  and `hit` do), the camera's from its device registry, and for an area it sends one DOWN `rhack` to every active session whose V is within 50 m of
  the centre and whose `hacks` toggle allows it. The client never names an owner or an id, except `feedreq.owner` and `comms.to`, which are checked against
  the roster (a host who is not active, or a `to` who is not online, is dropped); a guest's `feedreq` also needs that host's `hacks` toggle.
- **Breach.** The server builds the puzzle (`breach_tier` and `daemon` content, a seed of its own), sends `breach` and validates **every** pick: the first
  pick is in row 0, then the pick alternates column, row, column, ... of the previous pick, no cell twice. A pick that breaks the rule or comes after
  the end is ignored (the screen data is sent again). The right-click on a Net Port block sends no payload (Minecraft's own use path).
  `solved` sends the `daemon`; `failed`, `timeout` and an `aborted` Breach after the first pick send the `daemon` with `["alarm"]` (a failed Breach
  also costs Heat, Phase 8's meter). The puzzle's timer starts when the screen opens.
- **An abort is not a free re-roll.** `op:"abort"` (or the screen closed) **after the first pick** is a failure: `state:"aborted"`, the alarm and
  the Heat. An abort before the first pick sends nothing, but the server **keeps that puzzle** (the same grid, daemons and running timer) for that
  player and device until it is solved, failed or timed out, so the next right-click opens it again as it was and its timeout is a failure too.
  After a failure the same player cannot breach that device again for **10 s** (a right-click shows `cyberblock.breach.cooldown` on the action
  bar). Every `act` is at most 8000 bytes.
- **Dropped quietly.** An `act` of these four `t` that breaks a limit above or comes from a player that cannot act on that target is dropped
  (logged at debug level, not counted). **The player-reach rule covers only the acts that stand for a hand use:** a Breach pick (the player's
  eyes within 8 blocks of the Net Port block) and a Vehicle Panel opened by right-clicking a car figure (the car figure within 50 m of the
  player when the panel opens). **Net Map and Traffic Tool `rhack` acts (the `cam_feed` `pos`, an area's `pos`, a vehicle target picked on the
  map) and the `comms` `pos` are not checked against the player's position**: the `rhack` ones are bounded only by the V-based 50 m rule above
  and by the owning host's sanity radius (160 blocks), the `comms` `pos` only by the block limits, so a guest can use them from anywhere.

### Phase 6 names
- **Remote hack names** (`rhack.hack`, the 1.9 row 6 list) — `veh_lock`, `veh_engine_off`, `veh_brake`, `veh_start`, `veh_jam`, `traffic_switch`, `gridlock`, `blackout`, `cam_feed`, `outline`, `ping_area`
- **Area hacks** (carry `p`, `r` and `secs`) — `traffic_switch`, `gridlock`, `blackout`, `outline`, `ping_area`
- **Breach codes** (`breach.grid`) — `1C`, `55`, `BD`, `E9`, `7A`, `FF`
- **Breach states** (`breach.state`) — `run`, `solved`, `failed`, `timeout`, `aborted`
- **Breach ops** (`act` `t:"breach"`, `op`) — `pick`, `abort`
- **Reserved daemon id** (`daemon.list`) — `alarm`

### Phase 6 log lines
In-game checks grep for them (Re-test 6 in `docs/TESTING-PLAN.md`). The exact text belongs to the packages that print them (6R, 6B and 6I on the
server, 6S and 6D in Cyberpunk); the integrator copies the final lines into Re-test 6.
- Each Phase 6 feature that has a fallback prints **one line saying which runtime path is active**: the traffic path (the traffic system or the Phase 4
  devices), the blackout path (the district or the devices within 150 m of V), the vehicle calls, and the feed path (the compositor copy, or the
  fallback with the time it cost).
- The server logs every remote hack it routed or refused (`rhack: <name> -> <owner> (<hack>)`), every Breach (start, size, solved daemons, failure) and every
  redstone block that froze on a loop (`cyberblock.redstone.loop`); the DLL logs `feedreq on|off`.

### Phase 6 examples
One message per line (JSON Lines). Every new `t` has a line; `rhack` shows its three forms (a car, a camera, an area) and `daemon` a solved Breach and
the failure alarm. The ids are the ones of the Phase 4 and Phase 5 examples: the car, the camera `...765` and the Net Port `...770`.

Phase 6 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"hackresult","id":21,"ok":true,"reason":""}
{"t":"hackresult","id":22,"ok":false,"reason":"cyberblock.hack.out_of_range"}
{"t":"feed","w":128,"h":128,"px":"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MDEyMzAxMjM0NTY3NDU2Nzg5Ojs4OTo7PD0+Pzw9Pj9AQUJDQEFCQ0RFRkdERUZHSElKS0hJSktMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZykqKygpKisoLS4vLC0uLywxMjMwMTIzMDU2NzQ1Njc0OTo7ODk6Ozg9Pj88PT4/PEFCQ0BBQkNARUZHREVGR0RJSktISUpLSE1OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkKisoKSorKCkuLywtLi8sLTIzMDEyMzAxNjc0NTY3NDU6Ozg5Ojs4OT4/PD0+Pzw9QkNAQUJDQEFGR0RFRkdERUpLSElKS0hJTk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGUrKCkqKygpKi8sLS4vLC0uMzAxMjMwMTI3NDU2NzQ1Njs4OTo7ODk6Pzw9Pj88PT5DQEFCQ0BBQkdERUZHREVGS0hJSktISUpPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZjQ1Njc0NTY3ODk6Ozg5Ojs8PT4/PD0+P0BBQkNAQUJDREVGR0RFRkdISUpLSElKS0xNTk9MTU5PUFFSU1BRUlNUVVZXVFVWV1hZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzNTY3NDU2NzQ5Ojs4OTo7OD0+Pzw9Pj88QUJDQEFCQ0BFRkdERUZHRElKS0hJSktITU5PTE1OT0xRUlNQUVJTUFVWV1RVVldUWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3A2NzQ1Njc0NTo7ODk6Ozg5Pj88PT4/PD1CQ0BBQkNAQUZHREVGR0RFSktISUpLSElOT0xNTk9MTVJTUFFSU1BRVldUVVZXVFVaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcTc0NTY3NDU2Ozg5Ojs4OTo/PD0+Pzw9PkNAQUJDQEFCR0RFRkdERUZLSElKS0hJSk9MTU5PTE1OU1BRUlNQUVJXVFVWV1RVVltYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyNDU2NzQ1Njc4OTo7ODk6Ozw9Pj88PT4/QEFCQ0BBQkNERUZHREVGR0hJSktISUpLTE1OT0xNTk9QUVJTUFFSU1RVVldUVVZXWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnM1Njc0NTY3NDk6Ozg5Ojs4PT4/PD0+PzxBQkNAQUJDQEVGR0RFRkdESUpLSElKS0hNTk9MTU5PTFFSU1BRUlNQVVZXVFVWV1RZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcDY3NDU2NzQ1Ojs4OTo7ODk+Pzw9Pj88PUJDQEFCQ0BBRkdERUZHREVKS0hJSktISU5PTE1OT0xNUlNQUVJTUFFWV1RVVldUVVpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3BxNzQ1Njc0NTY7ODk6Ozg5Oj88PT4/PD0+Q0BBQkNAQUJHREVGR0RFRktISUpLSElKT0xNTk9MTU5TUFFSU1BRUldUVVZXVFVWW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJAQUJDQEFCQ0RFRkdERUZHSElKS0hJSktMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f0FCQ0BBQkNARUZHREVGR0RJSktISUpLSE1OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98QkNAQUJDQEFGR0RFRkdERUpLSElKS0hJTk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH1DQEFCQ0BBQkdERUZHREVGS0hJSktISUpPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9fkBBQkNAQUJDREVGR0RFRkdISUpLSElKS0xNTk9MTU5PUFFSU1BRUlNUVVZXVFVWV1hZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/QUJDQEFCQ0BFRkdERUZHRElKS0hJSktITU5PTE1OT0xRUlNQUVJTUFVWV1RVVldUWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3xCQ0BBQkNAQUZHREVGR0RFSktISUpLSElOT0xNTk9MTVJTUFFSU1BRVldUVVZXVFVaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fUNAQUJDQEFCR0RFRkdERUZLSElKS0hJSk9MTU5PTE1OU1BRUlNQUVJXVFVWV1RVVltYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+TE1OT0xNTk9QUVJTUFFSU1RVVldUVVZXWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiotNTk9MTU5PTFFSU1BRUlNQVVZXVFVWV1RZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiE5PTE1OT0xNUlNQUVJTUFFWV1RVVldUVVpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJT0xNTk9MTU5TUFFSU1BRUldUVVZXVFVWW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYpMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi01OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouITk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiIlPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJilhZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5RaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlVtYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpdZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlFpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZZkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio2VmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKFnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChomRlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6BmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoWdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGicHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq9xcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrHJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra5wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur3Fyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+scnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK1zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrnx9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7h+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4uX98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5urt9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uH5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubqIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx4mKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMWLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxoiJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8SKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExYuIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tOVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0JaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DRl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dKUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS05WWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NGXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0qChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHyMnKy8jJysvMzc7PzM3Oz9DR0tPQ0dLT1NXW19TV1tfY2drb2Nna29zd3t/c3d7foaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8TJysvIycrLyM3Oz8zNzs/M0dLT0NHS09DV1tfU1dbX1Nna29jZ2tvY3d7f3N3e39yio6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExcrLyMnKy8jJzs/Mzc7PzM3S09DR0tPQ0dbX1NXW19TV2tvY2drb2Nne39zd3t/c3aOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGy8jJysvIycrPzM3Oz8zNztPQ0dLT0NHS19TV1tfU1dbb2Nna29jZ2t/c3d7f3N3eoKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tPU1dbX1NXW19jZ2tvY2drb3N3e39zd3t+hoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0NXW19TV1tfU2drb2Nna29jd3t/c3d7f3KKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DR1tfU1dbX1NXa29jZ2tvY2d7f3N3e39zdo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dLX1NXW19TV1tvY2drb2Nna39zd3t/c3d6sra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS09TV1tfU1dbX2Nna29jZ2tvc3d7f3N3e3+Dh4uPg4eLj5OXm5+Tl5ufo6err6Onq662ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQ1dbX1NXW19TZ2tvY2drb2N3e39zd3t/c4eLj4OHi4+Dl5ufk5ebn5Onq6+jp6uvorq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NHW19TV1tfU1drb2Nna29jZ3t/c3d7f3N3i4+Dh4uPg4ebn5OXm5+Tl6uvo6err6OmvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0tfU1dbX1NXW29jZ2tvY2drf3N3e39zd3uPg4eLj4OHi5+Tl5ufk5ebr6Onq6+jp6qytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHyMnKy8jJysvMzc7PzM3Oz9DR0tPQ0dLT1NXW19TV1tfY2drb2Nna29zd3t/c3d7f4OHi4+Dh4uPk5ebn5OXm5+jp6uvo6errra6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8TJysvIycrLyM3Oz8zNzs/M0dLT0NHS09DV1tfU1dbX1Nna29jZ2tvY3d7f3N3e39zh4uPg4eLj4OXm5+Tl5ufk6err6Onq6+iur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExcrLyMnKy8jJzs/Mzc7PzM3S09DR0tPQ0dbX1NXW19TV2tvY2drb2Nne39zd3t/c3eLj4OHi4+Dh5ufk5ebn5OXq6+jp6uvo6a+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGy8jJysvIycrPzM3Oz8zNztPQ0dLT0NHS19TV1tfU1dbb2Nna29jZ2t/c3d7f3N3e4+Dh4uPg4eLn5OXm5+Tl5uvo6err6OnquLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tPU1dbX1NXW19jZ2tvY2drb3N3e39zd3t/g4eLj4OHi4+Tl5ufk5ebn6Onq6+jp6uvs7e7v7O3u7wQFBgcEBQYHCAkKCwgJCgu5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0NXW19TV1tfU2drb2Nna29jd3t/c3d7f3OHi4+Dh4uPg5ebn5OXm5+Tp6uvo6err6O3u7+zt7u/sBQYHBAUGBwQJCgsICQoLCLq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DR1tfU1dbX1NXa29jZ2tvY2d7f3N3e39zd4uPg4eLj4OHm5+Tl5ufk5err6Onq6+jp7u/s7e7v7O0GBwQFBgcEBQoLCAkKCwgJu7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dLX1NXW19TV1tvY2drb2Nna39zd3t/c3d7j4OHi4+Dh4ufk5ebn5OXm6+jp6uvo6erv7O3u7+zt7gcEBQYHBAUGCwgJCgsICQq4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS09TV1tfU1dbX2Nna29jZ2tvc3d7f3N3e3+Dh4uPg4eLj5OXm5+Tl5ufo6err6Onq6+zt7u/s7e7vBAUGBwQFBgcICQoLCAkKC7m6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQ1dbX1NXW19TZ2tvY2drb2N3e39zd3t/c4eLj4OHi4+Dl5ufk5ebn5Onq6+jp6uvo7e7v7O3u7+wFBgcEBQYHBAkKCwgJCgsIuru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NHW19TV1tfU1drb2Nna29jZ3t/c3d7f3N3i4+Dh4uPg4ebn5OXm5+Tl6uvo6err6Onu7+zt7u/s7QYHBAUGBwQFCgsICQoLCAm7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0tfU1dbX1NXW29jZ2tvY2drf3N3e39zd3uPg4eLj4OHi5+Tl5ufk5ebr6Onq6+jp6u/s7e7v7O3uBwQFBgcEBQYLCAkKCwgJCg=="}
```

Phase 6 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"rhack","id":21,"target":"4503599627370497","hack":"veh_brake","secs":30}
{"t":"rhack","id":22,"target":"11529215046068469765","hack":"cam_feed"}
{"t":"rhack","id":23,"target":null,"hack":"blackout","p":[-1236.5,64.0,810.5],"r":60,"secs":180}
{"t":"daemon","id":24,"dev":"11529215046068469770","list":["open_door","disable_cameras"],"strength":2}
{"t":"daemon","id":25,"dev":"11529215046068469770","list":["alarm"],"strength":1}
{"t":"feedreq","on":true}
```

Phase 6 examples, client to server (`cyberblock:act`, `t:"rhack"`):
```jsonl
{"hack":"veh_brake","target":212}
{"hack":"cam_feed","pos":[-1233,67,812]}
{"hack":"blackout","pos":[-1236,64,810],"r":60}
```

Phase 6 examples, client to server (`cyberblock:act`, `t:"breach"`):
```jsonl
{"op":"pick","id":12,"cell":3}
{"op":"abort","id":12}
```

Phase 6 examples, client to server (`cyberblock:act`, `t:"feedreq"`):
```jsonl
{"owner":"3f2a8d1c-5b7e-4c1a-9d3e-0a1b2c3d4e5f","on":true}
```

Phase 6 examples, client to server (`cyberblock:act`, `t:"comms"`):
```jsonl
{"to":"3f2a8d1c-5b7e-4c1a-9d3e-0a1b2c3d4e5f","text":"Meet me at the Afterlife","pos":[-1240,64,806]}
```

Phase 6 examples, server to client (`cyberblock:ui`, `t:"breach"`):
```jsonl
{"id":12,"dev":"11529215046068469770","size":5,"grid":["1C","55","BD","E9","7A","55","1C","E9","BD","FF","E9","7A","55","1C","BD","BD","FF","1C","55","7A","7A","E9","FF","BD","1C"],"buffer":6,"picks":[2,7,6],"seqs":[{"id":"open_door","codes":["BD","1C"],"ok":false},{"id":"disable_cameras","codes":["E9","BD","FF"],"ok":false}],"total":45,"left":31.5,"state":"run","strength":0}
```

Phase 6 examples, server to client (`cyberblock:ui`, `t:"feed"`):
```jsonl
{"owner":"3f2a8d1c-5b7e-4c1a-9d3e-0a1b2c3d4e5f","w":128,"h":128,"px":"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MDEyMzAxMjM0NTY3NDU2Nzg5Ojs4OTo7PD0+Pzw9Pj9AQUJDQEFCQ0RFRkdERUZHSElKS0hJSktMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZykqKygpKisoLS4vLC0uLywxMjMwMTIzMDU2NzQ1Njc0OTo7ODk6Ozg9Pj88PT4/PEFCQ0BBQkNARUZHREVGR0RJSktISUpLSE1OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkKisoKSorKCkuLywtLi8sLTIzMDEyMzAxNjc0NTY3NDU6Ozg5Ojs4OT4/PD0+Pzw9QkNAQUJDQEFGR0RFRkdERUpLSElKS0hJTk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGUrKCkqKygpKi8sLS4vLC0uMzAxMjMwMTI3NDU2NzQ1Njs4OTo7ODk6Pzw9Pj88PT5DQEFCQ0BBQkdERUZHREVGS0hJSktISUpPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZjQ1Njc0NTY3ODk6Ozg5Ojs8PT4/PD0+P0BBQkNAQUJDREVGR0RFRkdISUpLSElKS0xNTk9MTU5PUFFSU1BRUlNUVVZXVFVWV1hZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzNTY3NDU2NzQ5Ojs4OTo7OD0+Pzw9Pj88QUJDQEFCQ0BFRkdERUZHRElKS0hJSktITU5PTE1OT0xRUlNQUVJTUFVWV1RVVldUWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3A2NzQ1Njc0NTo7ODk6Ozg5Pj88PT4/PD1CQ0BBQkNAQUZHREVGR0RFSktISUpLSElOT0xNTk9MTVJTUFFSU1BRVldUVVZXVFVaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcTc0NTY3NDU2Ozg5Ojs4OTo/PD0+Pzw9PkNAQUJDQEFCR0RFRkdERUZLSElKS0hJSk9MTU5PTE1OU1BRUlNQUVJXVFVWV1RVVltYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyNDU2NzQ1Njc4OTo7ODk6Ozw9Pj88PT4/QEFCQ0BBQkNERUZHREVGR0hJSktISUpLTE1OT0xNTk9QUVJTUFFSU1RVVldUVVZXWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnM1Njc0NTY3NDk6Ozg5Ojs4PT4/PD0+PzxBQkNAQUJDQEVGR0RFRkdESUpLSElKS0hNTk9MTU5PTFFSU1BRUlNQVVZXVFVWV1RZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcDY3NDU2NzQ1Ojs4OTo7ODk+Pzw9Pj88PUJDQEFCQ0BBRkdERUZHREVKS0hJSktISU5PTE1OT0xNUlNQUVJTUFFWV1RVVldUVVpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3BxNzQ1Njc0NTY7ODk6Ozg5Oj88PT4/PD0+Q0BBQkNAQUJHREVGR0RFRktISUpLSElKT0xNTk9MTU5TUFFSU1BRUldUVVZXVFVWW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJAQUJDQEFCQ0RFRkdERUZHSElKS0hJSktMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f0FCQ0BBQkNARUZHREVGR0RJSktISUpLSE1OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98QkNAQUJDQEFGR0RFRkdERUpLSElKS0hJTk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH1DQEFCQ0BBQkdERUZHREVGS0hJSktISUpPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9fkBBQkNAQUJDREVGR0RFRkdISUpLSElKS0xNTk9MTU5PUFFSU1BRUlNUVVZXVFVWV1hZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/QUJDQEFCQ0BFRkdERUZHRElKS0hJSktITU5PTE1OT0xRUlNQUVJTUFVWV1RVVldUWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3xCQ0BBQkNAQUZHREVGR0RFSktISUpLSElOT0xNTk9MTVJTUFFSU1BRVldUVVZXVFVaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fUNAQUJDQEFCR0RFRkdERUZLSElKS0hJSk9MTU5PTE1OU1BRUlNQUVJXVFVWV1RVVltYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+TE1OT0xNTk9QUVJTUFFSU1RVVldUVVZXWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiotNTk9MTU5PTFFSU1BRUlNQVVZXVFVWV1RZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiE5PTE1OT0xNUlNQUVJTUFFWV1RVVldUVVpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJT0xNTk9MTU5TUFFSU1BRUldUVVZXVFVWW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYpMTU5PTE1OT1BRUlNQUVJTVFVWV1RVVldYWVpbWFlaW1xdXl9cXV5fYGFiY2BhYmNkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi01OT0xNTk9MUVJTUFFSU1BVVldUVVZXVFlaW1hZWltYXV5fXF1eX1xhYmNgYWJjYGVmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouITk9MTU5PTE1SU1BRUlNQUVZXVFVWV1RVWltYWVpbWFleX1xdXl9cXWJjYGFiY2BhZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiIlPTE1OT0xNTlNQUVJTUFFSV1RVVldUVVZbWFlaW1hZWl9cXV5fXF1eY2BhYmNgYWJnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJilhZWltYWVpbXF1eX1xdXl9gYWJjYGFiY2RlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXWVpbWFlaW1hdXl9cXV5fXGFiY2BhYmNgZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5RaW1hZWltYWV5fXF1eX1xdYmNgYWJjYGFmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlVtYWVpbWFlaX1xdXl9cXV5jYGFiY2BhYmdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWWFlaW1hZWltcXV5fXF1eX2BhYmNgYWJjZGVmZ2RlZmdoaWpraGlqa2xtbm9sbW5vcHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpdZWltYWVpbWF1eX1xdXl9cYWJjYGFiY2BlZmdkZWZnZGlqa2hpamtobW5vbG1ub2xxcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlFpbWFlaW1hZXl9cXV5fXF1iY2BhYmNgYWZnZGVmZ2RlamtoaWpraGlub2xtbm9sbXJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVW1hZWltYWVpfXF1eX1xdXmNgYWJjYGFiZ2RlZmdkZWZraGlqa2hpam9sbW5vbG1uc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZZkZWZnZGVmZ2hpamtoaWprbG1ub2xtbm9wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio2VmZ2RlZmdkaWpraGlqa2htbm9sbW5vbHFyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgZmdkZWZnZGVqa2hpamtoaW5vbG1ub2xtcnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKFnZGVmZ2RlZmtoaWpraGlqb2xtbm9sbW5zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChomRlZmdkZWZnaGlqa2hpamtsbW5vbG1ub3BxcnNwcXJzdHV2d3R1dnd4eXp7eHl6e3x9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjZWZnZGVmZ2RpamtoaWpraG1ub2xtbm9scXJzcHFyc3B1dnd0dXZ3dHl6e3h5ent4fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6BmZ2RlZmdkZWpraGlqa2hpbm9sbW5vbG1yc3BxcnNwcXZ3dHV2d3R1ent4eXp7eHl+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoWdkZWZnZGVma2hpamtoaWpvbG1ub2xtbnNwcXJzcHFyd3R1dnd0dXZ7eHl6e3h5en98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGicHFyc3BxcnN0dXZ3dHV2d3h5ent4eXp7fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq9xcnNwcXJzcHV2d3R1dnd0eXp7eHl6e3h9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrHJzcHFyc3Bxdnd0dXZ3dHV6e3h5ent4eX5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytc3BxcnNwcXJ3dHV2d3R1dnt4eXp7eHl6f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra5wcXJzcHFyc3R1dnd0dXZ3eHl6e3h5ent8fX5/fH1+f4CBgoOAgYKDhIWGh4SFhoeIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur3Fyc3BxcnNwdXZ3dHV2d3R5ent4eXp7eH1+f3x9fn98gYKDgIGCg4CFhoeEhYaHhImKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+scnNwcXJzcHF2d3R1dnd0dXp7eHl6e3h5fn98fX5/fH2Cg4CBgoOAgYaHhIWGh4SFiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK1zcHFyc3Bxcnd0dXZ3dHV2e3h5ent4eXp/fH1+f3x9foOAgYKDgIGCh4SFhoeEhYaLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrnx9fn98fX5/gIGCg4CBgoOEhYaHhIWGh4iJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7fX5/fH1+f3yBgoOAgYKDgIWGh4SFhoeEiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7h+f3x9fn98fYKDgIGCg4CBhoeEhYaHhIWKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4uX98fX5/fH1+g4CBgoOAgYKHhIWGh4SFhouIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6fH1+f3x9fn+AgYKDgIGCg4SFhoeEhYaHiImKi4iJiouMjY6PjI2Oj5CRkpOQkZKTlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5urt9fn98fX5/fIGCg4CBgoOAhYaHhIWGh4SJiouIiYqLiI2Oj4yNjo+MkZKTkJGSk5CVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uH5/fH1+f3x9goOAgYKDgIGGh4SFhoeEhYqLiImKi4iJjo+MjY6PjI2Sk5CRkpOQkZaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5f3x9fn98fX6DgIGCg4CBgoeEhYaHhIWGi4iJiouIiYqPjI2Oj4yNjpOQkZKTkJGSl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubqIiYqLiImKi4yNjo+MjY6PkJGSk5CRkpOUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx4mKi4iJiouIjY6PjI2Oj4yRkpOQkZKTkJWWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEiouIiYqLiImOj4yNjo+MjZKTkJGSk5CRlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMWLiImKi4iJio+MjY6PjI2Ok5CRkpOQkZKXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxoiJiouIiYqLjI2Oj4yNjo+QkZKTkJGSk5SVlpeUlZaXmJmam5iZmpucnZ6fnJ2en6ChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHiYqLiImKi4iNjo+MjY6PjJGSk5CRkpOQlZaXlJWWl5SZmpuYmZqbmJ2en5ydnp+coaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8SKi4iJiouIiY6PjI2Oj4yNkpOQkZKTkJGWl5SVlpeUlZqbmJmam5iZnp+cnZ6fnJ2io6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExYuIiYqLiImKj4yNjo+MjY6TkJGSk5CRkpeUlZaXlJWWm5iZmpuYmZqfnJ2en5ydnqOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGlJWWl5SVlpeYmZqbmJmam5ydnp+cnZ6foKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tOVlpeUlZaXlJmam5iZmpuYnZ6fnJ2en5yhoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0JaXlJWWl5SVmpuYmZqbmJmen5ydnp+cnaKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DRl5SVlpeUlZabmJmam5iZmp+cnZ6fnJ2eo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dKUlZaXlJWWl5iZmpuYmZqbnJ2en5ydnp+goaKjoKGio6SlpqekpaanqKmqq6ipqqusra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS05WWl5SVlpeUmZqbmJmam5idnp+cnZ6fnKGio6ChoqOgpaanpKWmp6SpqquoqaqrqK2ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQlpeUlZaXlJWam5iZmpuYmZ6fnJ2en5ydoqOgoaKjoKGmp6SlpqekpaqrqKmqq6iprq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NGXlJWWl5SVlpuYmZqbmJman5ydnp+cnZ6joKGio6ChoqekpaanpKWmq6ipqquoqaqvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0qChoqOgoaKjpKWmp6SlpqeoqaqrqKmqq6ytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHyMnKy8jJysvMzc7PzM3Oz9DR0tPQ0dLT1NXW19TV1tfY2drb2Nna29zd3t/c3d7foaKjoKGio6ClpqekpaanpKmqq6ipqquora6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8TJysvIycrLyM3Oz8zNzs/M0dLT0NHS09DV1tfU1dbX1Nna29jZ2tvY3d7f3N3e39yio6ChoqOgoaanpKWmp6SlqquoqaqrqKmur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExcrLyMnKy8jJzs/Mzc7PzM3S09DR0tPQ0dbX1NXW19TV2tvY2drb2Nne39zd3t/c3aOgoaKjoKGip6SlpqekpaarqKmqq6ipqq+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGy8jJysvIycrPzM3Oz8zNztPQ0dLT0NHS19TV1tfU1dbb2Nna29jZ2t/c3d7f3N3eoKGio6ChoqOkpaanpKWmp6ipqquoqaqrrK2ur6ytrq+wsbKzsLGys7S1tre0tba3uLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tPU1dbX1NXW19jZ2tvY2drb3N3e39zd3t+hoqOgoaKjoKWmp6SlpqekqaqrqKmqq6itrq+sra6vrLGys7CxsrOwtba3tLW2t7S5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0NXW19TV1tfU2drb2Nna29jd3t/c3d7f3KKjoKGio6ChpqekpaanpKWqq6ipqquoqa6vrK2ur6ytsrOwsbKzsLG2t7S1tre0tbq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DR1tfU1dbX1NXa29jZ2tvY2d7f3N3e39zdo6ChoqOgoaKnpKWmp6SlpquoqaqrqKmqr6ytrq+sra6zsLGys7Cxsre0tba3tLW2u7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dLX1NXW19TV1tvY2drb2Nna39zd3t/c3d6sra6vrK2ur7CxsrOwsbKztLW2t7S1tre4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS09TV1tfU1dbX2Nna29jZ2tvc3d7f3N3e3+Dh4uPg4eLj5OXm5+Tl5ufo6err6Onq662ur6ytrq+ssbKzsLGys7C1tre0tba3tLm6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQ1dbX1NXW19TZ2tvY2drb2N3e39zd3t/c4eLj4OHi4+Dl5ufk5ebn5Onq6+jp6uvorq+sra6vrK2ys7CxsrOwsba3tLW2t7S1uru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NHW19TV1tfU1drb2Nna29jZ3t/c3d7f3N3i4+Dh4uPg4ebn5OXm5+Tl6uvo6err6OmvrK2ur6ytrrOwsbKzsLGyt7S1tre0tba7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0tfU1dbX1NXW29jZ2tvY2drf3N3e39zd3uPg4eLj4OHi5+Tl5ufk5ebr6Onq6+jp6qytrq+sra6vsLGys7CxsrO0tba3tLW2t7i5uru4ubq7vL2+v7y9vr/AwcLDwMHCw8TFxsfExcbHyMnKy8jJysvMzc7PzM3Oz9DR0tPQ0dLT1NXW19TV1tfY2drb2Nna29zd3t/c3d7f4OHi4+Dh4uPk5ebn5OXm5+jp6uvo6errra6vrK2ur6yxsrOwsbKzsLW2t7S1tre0ubq7uLm6u7i9vr+8vb6/vMHCw8DBwsPAxcbHxMXGx8TJysvIycrLyM3Oz8zNzs/M0dLT0NHS09DV1tfU1dbX1Nna29jZ2tvY3d7f3N3e39zh4uPg4eLj4OXm5+Tl5ufk6err6Onq6+iur6ytrq+srbKzsLGys7Cxtre0tba3tLW6u7i5uru4ub6/vL2+v7y9wsPAwcLDwMHGx8TFxsfExcrLyMnKy8jJzs/Mzc7PzM3S09DR0tPQ0dbX1NXW19TV2tvY2drb2Nne39zd3t/c3eLj4OHi4+Dh5ufk5ebn5OXq6+jp6uvo6a+sra6vrK2us7CxsrOwsbK3tLW2t7S1tru4ubq7uLm6v7y9vr+8vb7DwMHCw8DBwsfExcbHxMXGy8jJysvIycrPzM3Oz8zNztPQ0dLT0NHS19TV1tfU1dbb2Nna29jZ2t/c3d7f3N3e4+Dh4uPg4eLn5OXm5+Tl5uvo6err6OnquLm6u7i5uru8vb6/vL2+v8DBwsPAwcLDxMXGx8TFxsfIycrLyMnKy8zNzs/Mzc7P0NHS09DR0tPU1dbX1NXW19jZ2tvY2drb3N3e39zd3t/g4eLj4OHi4+Tl5ufk5ebn6Onq6+jp6uvs7e7v7O3u7wQFBgcEBQYHCAkKCwgJCgu5uru4ubq7uL2+v7y9vr+8wcLDwMHCw8DFxsfExcbHxMnKy8jJysvIzc7PzM3Oz8zR0tPQ0dLT0NXW19TV1tfU2drb2Nna29jd3t/c3d7f3OHi4+Dh4uPg5ebn5OXm5+Tp6uvo6err6O3u7+zt7u/sBQYHBAUGBwQJCgsICQoLCLq7uLm6u7i5vr+8vb6/vL3Cw8DBwsPAwcbHxMXGx8TFysvIycrLyMnOz8zNzs/MzdLT0NHS09DR1tfU1dbX1NXa29jZ2tvY2d7f3N3e39zd4uPg4eLj4OHm5+Tl5ufk5err6Onq6+jp7u/s7e7v7O0GBwQFBgcEBQoLCAkKCwgJu7i5uru4ubq/vL2+v7y9vsPAwcLDwMHCx8TFxsfExcbLyMnKy8jJys/Mzc7PzM3O09DR0tPQ0dLX1NXW19TV1tvY2drb2Nna39zd3t/c3d7j4OHi4+Dh4ufk5ebn5OXm6+jp6uvo6erv7O3u7+zt7gcEBQYHBAUGCwgJCgsICQq4ubq7uLm6u7y9vr+8vb6/wMHCw8DBwsPExcbHxMXGx8jJysvIycrLzM3Oz8zNzs/Q0dLT0NHS09TV1tfU1dbX2Nna29jZ2tvc3d7f3N3e3+Dh4uPg4eLj5OXm5+Tl5ufo6err6Onq6+zt7u/s7e7vBAUGBwQFBgcICQoLCAkKC7m6u7i5uru4vb6/vL2+v7zBwsPAwcLDwMXGx8TFxsfEycrLyMnKy8jNzs/Mzc7PzNHS09DR0tPQ1dbX1NXW19TZ2tvY2drb2N3e39zd3t/c4eLj4OHi4+Dl5ufk5ebn5Onq6+jp6uvo7e7v7O3u7+wFBgcEBQYHBAkKCwgJCgsIuru4ubq7uLm+v7y9vr+8vcLDwMHCw8DBxsfExcbHxMXKy8jJysvIyc7PzM3Oz8zN0tPQ0dLT0NHW19TV1tfU1drb2Nna29jZ3t/c3d7f3N3i4+Dh4uPg4ebn5OXm5+Tl6uvo6err6Onu7+zt7u/s7QYHBAUGBwQFCgsICQoLCAm7uLm6u7i5ur+8vb6/vL2+w8DBwsPAwcLHxMXGx8TFxsvIycrLyMnKz8zNzs/Mzc7T0NHS09DR0tfU1dbX1NXW29jZ2tvY2drf3N3e39zd3uPg4eLj4OHi5+Tl5ufk5ebr6Onq6+jp6u/s7e7v7O3uBwQFBgcEBQYLCAkKCwgJCg=="}
```

Phase 6 examples, server to client (`cyberblock:ui`, `t:"comms"`):
```jsonl
{"from":"CbGuest1","text":"Meet me at the Afterlife","pos":[-1240,64,806]}
```

## Phase 7 (NPC interaction, the Afterlife hub and the Holo-Table)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values, actions and fields. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows 7, with the 2026-10-04 amendment of the vendor `reply` and `uiact`
rows), section 1.8 (row 7) and section 11, with the dated Phase 0 amendment (fallbacks (f) and (g)). Three things cross the DLL wire in Phase 7: **the
Afterlife hub** (`hub` with the Phase 3 `park`: the server tells a host's DLL to switch to full-screen Minecraft and to put V away safely), **the stock of the
Night City vendor or ripperdoc the host opened** (so Minecraft can draw its screen) and **the purchases and sales back** (paid with the host's real Cyberpunk
money, megaplan 1.16), plus the Holo-Table's list of the host's fast-travel points. The role screens of figures, the hub's Minecraft shops and the Holo-Table
(7N, 7H, 7T) are Minecraft and server only: they use `cyberblock:act` and `cyberblock:ui` payloads that their packages define and never reach a DLL; a
Holo-Table spawn of a linked player is the existing [`vteleport` with `why:"holo"`](#phase-3-minecraft--cyberpunk), in the order given below.

### Phase 7: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `reply` | **extends re-test 3 and the Atlas:** `kind` also ∈ `vendor`, **sent unrequested**. `id` = the host's vendor session (one more each time a vendor is taken over; the resend after a trade keeps it); `pins` is `[]` or **one pin, the vendor itself**: `kind` ∈ `vendor`, `ripperdoc`, `name` its shop name (≤ 64), `p` where it stands, `i:-1`; `groups` are the **stock categories** (`hash` the category's index, `title` its name, `tracked:false`): `kind:"buy"` with `done:false` for the vendor's stock, `kind:"sell"` with `done:true` for V's items the vendor takes; `lines` are the **items**: `hash` (the low 32 bits of the item's TweakDB id, what `uiact buy` and `sell` send back), `text` (≤ 256, `<name> x<n>` for a stack) and **`state` = the unit price in eddies** (a whole number 0..2147483647) instead of the 0, 1, 2 of the other kinds. The limits of [Re-test 3](#re-test-3-semantics-and-limits) hold (≤ 128 groups, ≤ 512 lines in all, ≤ 256 KB) | the host opened a vendor or a ripperdoc in Night City (Cyberblock On, Minecraft linked), and again after every trade; LOCAL (the host's own Night City interface: never relayed) |

### Phase 7: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `hub` | `in:bool` (`true` the host entered the Afterlife hub, `false` it left) | the server, DOWN to that host's DLL only; always together with a `park` (see the order below) |
| `uiact` | **extends re-test 3:** `a` also ∈ `buy`, `sell`: `hash` = the line's `hash` from the vendor `reply` (an absent or zero `hash` makes them invalid), `id` = the line's **index among the lines of its side** (the buy or the sell groups, in reply order), `arg` = the **quantity** as a decimal string (`"1"`, with Shift `"10"`; `""` or not a number means 1). `cpmenu` also takes `arg:"vendor"` (`id` the vendor session): this vendor again as Cyberpunk's own screen, for installing cyberware and buyback. `atlasreq` with `arg:"fasttravel"` asks for the Holo-Table's fast-travel list; `fasttravel` (`id` = the `i` of a pin of that list, `arg` = its `name`) fast travels the host | a Minecraft vendor or ripperdoc screen (7V), or the Holo-Table's fast-travel list; LOCAL |

### Phase 7 semantics and limits
- **The hub.** `hub in:true` switches the compositor to **full-screen Minecraft** (the loading-cover path: no depth blending, Cyberpunk's picture is
  hidden) and `in:false` switches back. The hub is a Minecraft dimension (`cyberblock:afterlife`): entering moves Steve there, V stays where
  it was. The server sends **both messages in the same tick: on entering `hub in:true` then `park on:true`, on leaving `park on:false` then
  `hub in:false`** (V is released under the cover, then Night City's picture returns; 7H's `HubRules.sync`). `park` is the
  [Phase 3 message](#phase-3-minecraft--cyberpunk) (V invisible, invulnerable, unseen by NPCs, saving locked, no movement or camera); the scripts park V on the
  `hub` event as well and try again every 1 s while the hub lasts (7S `Hub.reds`), and the DLL answers every `park` with `parked`, as before. Hosts only: a
  guest has no DLL, so no `hub` and no `park` is ever sent for it.
- **The cover only stays up over a parked V.** The server refuses an entry (a chat message, nobody moves) while the player is locked by the join password
  (`JoinLock`: the command, the hub portal and the pause screen's button alike), while the session says the game holds V (a scene or a ride) and while V is
  in a vehicle. When the DLL still answers the hub's `park on:true` with **`parked ok:false`** (V cannot be parked now: not live, a vehicle, a scene, a
  teleport, saving; the reason is `blocked (<why>)`), the server **takes the player out of the hub at once**, back to where they came from (the leaving pair:
  `park on:false`, `hub in:false`), and shows the reason; the cover is never left over an unparked V for longer than that one answer. No answer within 10 s
  is logged as an unconfirmed park path (a Phase 3 DLL answers every `park` from the scripts' `park:` log lines).
- **A new session hears the hub again.** What a DLL was told ends with its session: the DLL drops its cover by itself (and the scripts release V) on a link
  drop, on every `hello` and when its `session` leaves `host`, with no message needed. So on **every host session start** (the `hello` after a link drop, a
  Cyberpunk restart or a reconnect) the server counts the DLL as out of the hub, and a player who is still in `cyberblock:afterlife` gets the entering pair
  again, once (7H: `HostSessions.onStart`). Cyberblock going Off releases V (the compositor draws nothing while Off) but keeps the DLL's hub, so V is parked
  again at On. A `hub` or `park` that arrives without a host session is ignored.
- **Leaving by a Holo-Table spawn.** The Holo-Table stands in the hub, so a linked host's spawn from it is a hub exit first: Steve is moved out to the spot,
  the server sends the leaving pair (`park on:false`, `hub in:false`: V released, the picture back), and **only then** the `vteleport why:"holo"` (7T sends it
  1.5 s after the exit). Never the other way round: the scripts refuse a `vteleport` while V is parked (`blocked (parked)`), and releasing a park may move V
  back to where V was parked (the (g) fast-travel fallback), which would undo an earlier teleport. On `vteleported ok:true` Steve goes to V's snapped spot; on
  `ok:false` the host stays out of the hub with V where it was, and the reason is shown. `why:"hub_exit"` stays reserved (Phase 7 does not use it). A fast
  travel picked at the table runs once the park is released, the same way.
- **`parked` is relayed from Phase 7.** The client relay (its UP set) and the server (`RelayGuard`, rate row: 2 per second, burst 4,
  [the table](#multiplayer-security-rate-limits-and-guest-toggles); a `HostSessions` listener of 7H's hub) accept the DLL's `parked`, the answer to the
  last `park` (it carries no request id). The server acts on it only for a player in the hub: `ok:false` takes the player out (above), the first answer of a
  server run is logged as the park path (Phase 0 check (g)).
- **Vendor stock (Phase 0 check (f)).** The research verdict reads the stock with `MarketSystem.GetVendorItemsForSale(vendor, false)`; the scripts read the
  same stock through the vendor screen's own `VendorDataManager`, and that read is the runtime feature check. When the host opens a vendor or a ripperdoc
  while Cyberblock is On and Minecraft is linked, the scripts send the `reply`; Minecraft opens its vendor screen, and once Minecraft reports a screen open
  Cyberpunk's own screen closes. **No Minecraft screen within 1.5 s, a stock that reads empty, a stash, the prologue ripperdoc, the ripperdoc tutorials and
  anything quest-locked keep Cyberpunk's own screen: the pass-through stays** (logged). A ripperdoc's stock is a vendor stock like any other; installing
  cyberware stays Cyberpunk's own screen (`cpmenu arg:"vendor"`). The DLL builds the `reply` like the journal's (control characters stripped, text cut at a
  character boundary, extra entries dropped and counted). **A receiver tells the sides apart by `kind:"sell"` or `done:true`: a group with neither is the
  vendor's stock**, whatever its `kind` says (a DLL that knows only the Re-test 3 group kinds files `buy` and `sell` as `other`).
- **Buying and selling.** One `uiact buy` or `sell` per click, for `arg` units. They are paid from and into the host's **real Cyberpunk money**, never
  Minecraft eddies (megaplan 1.16): the scripts find the line by `id` and `hash` (by `hash` alone when the index no longer matches), clamp the quantity to the
  stack and to what the money allows, re-read the real price and trade through Cyberpunk's own vendor path, so Minecraft is never trusted with a price.
  0.3 s later the result is a `notify` (`money`: "Bought ..." or "Sold ..." with the eddies; `warning`: "Not enough eddies", "The vendor cannot pay for
  that", a trade that changed no money) and the stock is sent again with the same `id`; a line that is no longer in the stock only sends the stock again.
  The new balance also arrives as the next `stats.money`. Inside the DLL `buy` and `sell` are `uiact` actions the scripts read (`Cyberblock_NextUiAction`),
  never events (megaplan 1.8 row 7 as amended).
- **Fast travel (the Holo-Table).** `uiact atlasreq arg:"fasttravel"` is answered with an `atlas` `reply` of the known fast-travel points only, each `i` its
  index while the host can fast travel now and −1 otherwise (with an `info` toast saying why); `uiact fasttravel` (`id` that `i`, `arg` the pin's `name`: the
  index counts only while the name still matches) fast travels the host the game's own way. A runtime check (no job locks fast travel, V on foot, out of
  combat, not held by a scene) replaces the `FastTravelEnabled` switch of Re-test 3; a refusal is a `warning` toast "Not now: <why>". The Atlas itself stays a
  map (every `i` −1). The wire does not change.
- **Validation (receivers).** `hub.in` is a boolean (anything else is dropped and counted); `uiact.a` outside the twelve names makes the whole message invalid, as
  before; `buy` and `sell` need a `hash` of 1..2^32 − 1 and an `id` ≥ 0; a vendor `reply` follows the Re-test 3 limits plus: `pins` empty or one pin of `kind`
  `vendor` or `ripperdoc`; each group's `kind` a string, read as above; `tracked` and `done` booleans; `state` a whole number 0..2147483647 for its lines. A bad
  message is counted and dropped, never fatal.
- **Features.** `hs_ack.features` bit 16 (hub) is set by a Phase 7 server.

### Phase 7 differences from megaplan section 1 (for the integrator)
Section 1 is the contract; these readings differ from its text or add to it, and the integrator amends section 1 (or the packages) at the merge.
- **The vendor `reply` is LOCAL, not UP.** Section 1.9 row 7 classes `reply` (ext) `kind:"vendor"` as UP. It is the host's own Night City screen
  (section 1.2: UI messages are LOCAL; task 7-V1), so no relay and no `RelayGuard` row may carry it: a host's shop never reaches the server.
- **`groups[].kind` `buy` and `sell`.** Section 1.9 row 7 (amended 2026-10-04) says the DLL files them as `other`; 7D keeps `buy` and `sell` in a vendor
  reply (an unknown kind becomes `buy`). Either works, because receivers read the sell side from `kind:"sell"` or `done:true`.
- **The hub sends `park` too.** Section 1.9 row 7 says that on `hub` "the scripts park V". The server also sends the Phase 3 `park` with every `hub`, in the
  order above, and the scripts park V on either one (7S: `Hub.reds` on `hub`, `Park.reds` on `park`); the DLL's `parked` answers the `park`.
- **New hub rules.** The refused-park exit, the second entering pair on a session start and the order of a Holo-Table spawn out of the hub are this
  section's requirements for 7H (`HubTravel`: the exit on `parked ok:false` is not in 7H's branch yet), 7T (the `vteleport` after the leaving pair) and 7S
  (`Teleport.reds` refuses while parked; `Park.reds` releases before any teleport).

### Phase 7 names
- **Vendor group kinds** (`reply.groups[].kind` for `kind:"vendor"`) — `buy`, `sell`
- **Reply kinds** (Phase 7 adds one to the three of Re-test 3) — `vendor`
- **UI actions** (Phase 7 adds two to the ten of Re-test 3) — `buy`, `sell`

### Phase 7 log lines
In-game checks grep for them (Re-test: Wave C, Phase 7 in `docs/TESTING-PLAN.md`). The exact text belongs to the packages that print them (7S, 7V and 7D in
Cyberpunk, 7H and 7V on the server and the client); the integrator copies the final lines into the Re-test.
- Each feature that has a fallback prints **one line saying which runtime path is active**: whether a vendor's stock was read or the pass-through stayed
  (`vendors: path: ...`, `vendors: <name> pass-through (<why>)`), whether fast travel from the Holo-Table is on or off (`fast travel: ...`), and the park
  method (the Phase 3 `park: on|off (<reason>)` line, now also written for every hub entry and exit; its text says when the unseen-by-NPCs part is
  unavailable, Phase 0 check (g)).
- The server logs every hub entry and exit (who, how: the command, the portal block, the pause screen), every pair it sends (`hub: <name> <- hub in:<b>,
  park on:<b>`), the park path once a run (`hub: park path: ...`) and every refused park (`hub: <name> V not parked (<reason>)`).

### Phase 7 examples
One message per line (JSON Lines). The vendor `reply` shows both sides of a gun shop; the `uiact` lines show a purchase, a sale of up to ten, the
fast-travel list, a fast travel and the way back to Cyberpunk's own vendor screen.

Phase 7 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"reply","kind":"vendor","id":3,"groups":[{"hash":2,"title":"Pistols","kind":"buy","tracked":false,"done":false,"lines":[{"hash":90210,"text":"Kessler K9 pistol x2","state":1200},{"hash":90211,"text":"Dune Runner revolver","state":2400}]},{"hash":9,"title":"Your items","kind":"sell","tracked":false,"done":true,"lines":[{"hash":4000000001,"text":"Broken radio","state":35}]}],"pins":[{"p":[-1201.5,64.0,845.0],"kind":"vendor","name":"Watson Arms","i":-1}]}
```

Phase 7 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"hub","in":true}
{"t":"hub","in":false}
{"t":"uiact","a":"buy","arg":"1","hash":90210,"id":0}
{"t":"uiact","a":"sell","arg":"10","hash":4000000001,"id":0}
{"t":"uiact","a":"atlasreq","arg":"fasttravel","hash":0,"id":32}
{"t":"uiact","a":"fasttravel","arg":"Watson Docks","hash":0,"id":4}
{"t":"uiact","a":"cpmenu","arg":"vendor","hash":0,"id":3}
```

## Phase 8 (roles and modes)

Same wire, same conventions; `proto` stays 1 and older builds ignore the new `t` values and ids. Plan:
`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 1.9 (rows 8), section 1.8 (row 8), section 1.17 and section 12, with the dated Phase 0
amendment (fallbacks (d) and (e): there is no turret character record, and the wanted level and the attitudes are written through the prevention and attitude
systems) as the working contract. Almost all of Phase 8 is Minecraft and server only: the Hacker in the Van, the Overlord Console, matches, PvP, Gang Wars,
the player hacks, the Server Heat meter, Downed and revive and the spectator mode use `cyberblock:act` and `cyberblock:ui` payloads that their packages define,
the `roster` fields (`team`, `pvp`, `downed`, `hidden`) and the Minecraft world, and never reach a DLL. **Five things cross the DLL wire:** Night City enemies
that the Overlord (or an event) spawns near a host (`spawn`, answered by `spawnresult`), the host's real wanted level (`heat`), the attitude of Night City's
factions toward V (`attitude`), the glitch of a hacked linked player (`glitch`), and one more cyberware id (`netrunner`). Where the plan leaves a shape open
(the values of `rec` and `faction`, the reasons of `spawnresult`, the rate of `spawnresult`) this section fixes it; the integrator confirms it against the
packages 8D, 8S, 8T, 8X, 8M, 8O and 8V.

### Phase 8: Cyberpunk → Minecraft
| `t` | fields | when |
|---|---|---|
| `spawnresult` | `id` (the request id of the `spawn` it answers, a whole number ≥ 0), `ok:bool`, `n` (a whole number 0..8: how many were created; 1..8 when `ok`, 0 when not), `reason` (at most 64 characters, `""` when `ok`) | the reply to every `spawn`, UP; it must carry an id the session issued |

### Phase 8: Minecraft → Cyberpunk
| `t` | fields | when |
|---|---|---|
| `spawn` | `id` (a request id, a whole number ≥ 0 the session issued), `rec` (what to spawn: one of the four record names below, as a string of `a-z`, `0-9` and `_`, 1 to 32 characters), `p:[x, y, z]` (where, Minecraft convention, finite with `\|c\| < 3e7`), `n` (a whole number 1..8: how many), `hostile:bool` (`true` they fight V at once, `false` they stand about), `faction` (the attitude group of the gang or force, 0 to 32 characters of `A-Z`, `a-z`, `0-9` and `_`, `""` for the scripts' default) | the Overlord (or an event or a Heat response) spawns Night City enemies near a host: DOWN to that host's session only, after the server's own fairness checks and warning (see below) |
| `heat` | `tier` (a whole number 0..5: the Server Heat tier the host's wanted level follows) | the Server Heat tier changed, and again after every host `hello`: DOWN to that host's session only |
| `attitude` | `faction` (an attitude group name, 1 to 32 characters of `A-Z`, `a-z`, `0-9` and `_`), `toward` (how that faction treats V: one of `friendly`, `neutral`, `hostile`) | a Gang Wars team's reputation changed how Night City's factions treat its host: DOWN to that host's session only |
| `glitch` | `seconds` (a finite number 0..10; `0` ends a glitch) | Reboot Optics hit a linked player: DOWN to the victim's session only; the DLL shows the ReShade glitch for that long |

### Phase 8 semantics and limits
- **What stays off the wire.** The Van (its drones are Minecraft entities, its hacks are the Phase 6 `rhack` and the old `hack`), the Overlord Console, a match's
  teams and zones, PvP and its 15 second lock, the Gang Wars scoreboard, the player hacks against Minecraft-only players, Downed and the Trauma Team flyer and
  the spectator and caster modes are Minecraft and server features. A hit on a linked player lands on V through Steve's hearts (the one-health rule), so it
  needs no message. Only what Night City itself has to do or show goes DOWN.
- **Spawns (Phase 0 check (d)).** `spawn` goes to the owner's session only; the scripts answer with exactly **one `spawnresult` for every `spawn`**. `rec` is
  `ganger`, `ncpd`, `maxtac` or `drone` (the plan's `turret` is **not** a `rec`: a turret is a device, not a character, so turrets are Minecraft-side only, the
  written Phase 0 narrowing). The scripts map them to character records of the check (a `ganger` of the gang the attitude group `faction` names, `""` the
  scripts' default gang; an `ncpd` patrol; a `maxtac` trooper; a `drone`) and spawn `n` of them around `p` (the plugin snaps them to the ground). A `rec` outside
  the four is answered `ok:false` with `reason:"unsupported"` (not dropped), like an unknown `hack`. `hostile:true` puts them in combat with V (hostile
  attitude toward V's group and a combat start, the method of Phase 0 check (d)); `hostile:false` leaves them standing about. Every spawned NPC is tagged for the
  session and never saved, and **every one is deleted when the session ends** (part of section 1.2's cleanup, below).
- **`spawnresult`.** `ok:true` means at least one was created (`n` 1..8, fewer than asked when some failed); `ok:false` means none (`n` 0) and says why in `reason`
  (free text of at most 64 characters, for example `unsupported`, `no ground`, `too many` for the scripts' cap of live spawned NPCs, `cyberblock.spawn.out_of_range`).
  The envelope rule of the plan (section 1.6) holds: a `spawnresult` whose `id` the session did not issue is dropped and counted. Rate row: 10 per second,
  burst 40 ([the table](#multiplayer-security-rate-limits-and-guest-toggles)).
- **Fairness and warnings belong to the server.** The 3 second warning (a sound and a marker) before spawns near a player, the minimum distance, the cap on
  simultaneous spawns, the host-set intensity and the Threat Points are the Overlord's rules (8O) and apply only to players **in an active match who opted in**.
  The DLL checks none of them: it sees a `spawn` at the moment the warning ends, and a spawn is never sent into a game whose owner is not in that match.
- **Who may, and the fallback.** The host's `spawns` toggle (`/cyberblock guests spawns on|off`, **off by default**) gates `spawn`, and with it `heat` and
  `attitude` (all three change what Night City does to that host): with the toggle off, or for a Minecraft-only player, the same enemies are **Minecraft squads**
  (skinned mobs) and nothing reaches the DLL. The **fallback of Phase 0 check (d)** is the same swap: a `spawnresult` with `ok:false` makes the server fill that
  spawn with a Minecraft squad instead, so a match never lacks its enemies. The scripts and the DLL write **one line saying which path is active**
  (`spawn: path: ...`, see the log lines).
- **Heat (Phase 0 check (e)).** `heat` carries the Server Heat tier 0..5 (megaplan 8-T1: 1 more NCPD patrols, 2 NCPD chases the loudest players, 3 heavy units,
  4 Max-Tac hunts the top chaos player, 5 full response). For a host the tier is mapped to **its real wanted level**: the scripts queue `SetWantedLevel` with that
  many stars, so NCPD and Max-Tac are the game's own; `stats.wanted` shows the result at the next `stats`. The server sends `heat` when the tier changes
  and **again after every host `hello`** (a restarted Cyberpunk starts calm, so the new session hears the tier it should have). The scripts remember the stage
  the game had before the first `heat` of a session and put it back when the session leaves `host`. When the write is not available (no write is the
  written fallback of check (e)) they log one line, ignore `heat`, and the hunting is done by spawns or Minecraft squads.
- **Attitude (Phase 0 check (e)).** `attitude` sets, for the session, how the named attitude group treats V's group (the non-persistent
  `AttitudeSystem.SetAttitudeRelation`; the `...Persistent` variants are never used: nothing is saved). `faction` is a group name of the check: `tygerClaws`,
  `maelstrom`, `valentinos`, `6thStreet`, `animals`, `voodooBoys`, `scavenger`, `wraiths`, `aldecaldos`, `kangtao`, `kurtz_army`, `arasaka`, `militech`, `police`,
  `traumateam` or `civilian` (`player` is V's own group and never a `faction`); a name outside them is ignored and logged. `toward` ∈ `friendly`, `neutral`,
  `hostile`. The scripts remember each relation they changed and **restore it** when the session leaves `host`. **No attitude write is the written fallback:**
  the call is ignored (one log line) and a Gang Wars team's reputation does not change how Night City treats its hosts, which the docs say.
- **Glitch.** `glitch` starts the ReShade glitch (the `cb_glitch` uniform and pass of `cp/shaders`) for `seconds` seconds; a new `glitch` replaces the running one,
  `0` ends it. It is sent only to the victim of Reboot Optics when that victim is a linked player; a Minecraft-only victim gets Minecraft's own overlay and
  nothing crosses the wire. The glitch ends by itself, with the session and with a link drop.
- **The session ends everything.** On a `session` that is not `host`, a `hello` and a link drop (section 1.2's cleanup, before `session: cleaned (<reason>)`)
  the scripts delete every NPC they spawned, restore every attitude and the wanted stage and end the glitch; a `spawn`, `heat`, `attitude` or `glitch` that
  arrives without a host session is ignored.
- **Validation (receivers).** `spawn.id` a whole number ≥ 0; `rec` a string of 1 to 32 characters of `[a-z0-9_]` (an unknown one is answered, not dropped);
  `p` three finite numbers with `|c| < 3e7`; `n` a whole number 1..8; `hostile` a boolean; `faction` a string of 0 to 32 characters of `[A-Za-z0-9_]`;
  `heat.tier` a whole number 0..5; `attitude.faction` a string of 1 to 32 characters of `[A-Za-z0-9_]` and `toward` one of the three; `glitch.seconds` a finite
  number 0..10 (not a boolean); `spawnresult.id` a whole number ≥ 0, `ok` a boolean, `n` a whole number 0..8 (≥ 1 exactly when `ok`), `reason` at most 64 characters
  (`""` exactly when `ok`). A bad message is counted and dropped, never fatal.
- **Features and the implant.** `hs_ack.features` bit 32 (modes) is set by a Phase 8 server. The cyberware list gains a 19th id, `netrunner` (megaplan 1.17):
  it has no effect in Cyberpunk, so a DLL that does not know it drops that one entry as the protocol says; in Minecraft it **resists player hacks** (the
  effects it cannot stop are shorter), as the 18th id `firewall` **blocks** them, with a cooldown, and raises the Breach tier of its defender.
- **Test commands** (dedicated test server with `-Dcyberblock.devtools=true`; `tools/mpcheck.py`'s `match`, `heat` and `overlord` scenarios assume them, 8M, 8T and
  8O add them): `/cyberblock test heat set <0..100>` sets the Server Heat meter and applies its tier at once; `/cyberblock test match start <mode>` run as a
  Cyberpunk-linked player creates and starts a match of that `match_mode` id around that player (the creator is in it with PvP on for its players), `/cyberblock
  test match join <creator> [overlord]` run as another player joins it (as a teammate, or as the Overlord), and `/cyberblock test match end` run as the creator ends
  it; `/cyberblock test overlord spawn <palette id> <n> <player>` run as the Overlord spawns `n` of a `spawn_palette` entry near that player through the same
  server path as the Overlord Console (warning, fairness and routing included). The real commands of the game are `/cyberblock spectate` and the host's `/cyberblock
  guests spawns on|off`.

### Phase 8 differences from megaplan section 1 (for the integrator)
Section 1 is the contract; these readings differ from its text or add to it, and the integrator amends section 1 (or the packages) at the merge.
- **No `turret` in `spawn.rec`.** Section 1.9 row 8 lists `turret` among the records; Phase 0 check (d) found no character record for it (a turret is a `SecurityTurret`
  device), so it is Minecraft-side only and `rec` has four values. 8S, 8D and 8O drop it; the Overlord's turret is a Minecraft entity.
- **`attitude` says `toward`.** Section 1.8 row 8 names the event `attitude` `(faction, delta)`; the wire of section 1.9 carries `toward` ∈ `friendly`, `neutral`, `hostile`.
  The DLL hands the scripts the faction and the stance; the wire follows 1.9.
- **`spawnresult.n` is the native's `count`.** The wire field is `n` (1.9), the native `Cyberblock_SpawnResult(id, ok, count, reason)` (1.8) calls it `count`: the DLL maps one to the other.
- **A rate for `spawnresult`.** Section 1.6 names no rate for it: this section sets 10 per second, burst 40, as the other replies of that size (`devresult`); 8D's `RelayGuard` row and the client's UP set add it.
- **The `spawns` toggle also gates `heat` and `attitude`.** Section 1.6 says `spawns` gates "spawns of Night City NPCs and events into this game"; the real wanted level and the
  attitudes change that game just as much, so they follow the same switch (off by default). If 8T decides to send `heat` to every host, this section and Re-test 8 change to say so.
- **`heat` is sent again after every `hello`.** Not in section 1; the rule of the existing restore messages (`align`, `perks`, `cyberware`, `effect`) applied to the meter.
- **The devtools commands** above are this section's request to 8M, 8T and 8O; the names are the integrator's to confirm (one table in `tools/mpcheck.py`, `PHASE8_COMMANDS`).

### Phase 8 names
- **Spawn records** (`spawn.rec`, the 1.9 row 8 list after Phase 0 check (d)) — `ganger`, `ncpd`, `maxtac`, `drone`
- **Attitude stances** (`attitude.toward`) — `friendly`, `neutral`, `hostile`
- **Attitude groups** (`attitude.faction`, Phase 0 check (e)) — `tygerClaws`, `maelstrom`, `valentinos`, `6thStreet`, `animals`, `voodooBoys`, `scavenger`, `wraiths`, `aldecaldos`, `kangtao`, `kurtz_army`, `arasaka`, `militech`, `police`, `traumateam`, `civilian`
- **Heat tiers** (`heat.tier`) — `0`, `1`, `2`, `3`, `4`, `5`

### Phase 8 log lines
In-game checks grep for them (Re-test 8 in `docs/TESTING-PLAN.md`). The exact text belongs to the packages that print them (8S and 8D in Cyberpunk, 8T, 8X, 8O, 8M
and 8V on the server and the client); the integrator copies the final lines into Re-test 8.
- Each Phase 8 feature that has a fallback prints **one line saying which runtime path is active**: `spawn: path: ...` (Night City records through Codeware's dynamic
  entities, or Minecraft squads because spawning failed or the spawned NPCs stayed passive), `heat: path: ...` (the wanted level is written, or not: no write is the
  fallback) and `attitude: path: ...` (written, or ignored), plus the glitch pass (`glitch: ...`, the uniform and the seconds).
- The server logs every `spawn` it sent or swapped for a Minecraft squad (who, `rec`, `n`, the owner, why), every Heat tier change (`heat: <old> -> <new>`), every match
  (created, joined, ended), every PvP refusal and every player hack (hacker, victim, the chip, blocked or applied).

### Phase 8 examples
One message per line (JSON Lines). The `spawnresult` lines are a successful answer and a refusal; the `spawn` lines show a hostile ganger squad of a gang, an NCPD
drone that only stands about and the scripts' default gang; the `heat` lines show Max-Tac's tier and the calm after it.

Phase 8 examples, Cyberpunk to Minecraft:
```jsonl
{"t":"spawnresult","id":41,"ok":true,"n":3,"reason":""}
{"t":"spawnresult","id":42,"ok":false,"n":0,"reason":"no ground"}
```

Phase 8 examples, Minecraft to Cyberpunk:
```jsonl
{"t":"spawn","id":41,"rec":"ganger","p":[-1236.5,64.0,812.5],"n":3,"hostile":true,"faction":"maelstrom"}
{"t":"spawn","id":42,"rec":"drone","p":[-1230.0,70.0,818.0],"n":1,"hostile":false,"faction":"police"}
{"t":"spawn","id":43,"rec":"maxtac","p":[-1240.5,64.0,820.5],"n":4,"hostile":true,"faction":""}
{"t":"heat","tier":4}
{"t":"heat","tier":0}
{"t":"attitude","faction":"tygerClaws","toward":"friendly"}
{"t":"glitch","seconds":4.5}
{"t":"glitch","seconds":0}
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
