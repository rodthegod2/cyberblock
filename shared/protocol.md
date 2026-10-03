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
cyberware, four binds).

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
| `hud` | `hidden:bool` | on change (hidden in vehicles) |
| `key` | `k` ∈ `use, attack, pick, inventory, drop, swap, chat, command, escape`, `down:bool` | input (layers 3–6 add more values of `k`, see below) |
| `slot` | `n` 0–8 | number keys |
| `scroll` | `d` int (wheel notches, +1 = up) | wheel |
| `cursor` | `x, y` in 0..1 of the window | only while `status.screen` |
| `click` | `b` 0 left, 1 right, 2 middle, `down:bool` | only while `status.screen` |
| `dmg` | `frac` 0..4 (fraction of V's max health the hit would have taken), `kind` ∈ `bullet, explosion, melee, fall, vehicle, fire, other`, `from:[x,y,z]` or `null` | each damage event on V while active (layer 2) |
| `npcs` | `list:[{id:"<uint64 as decimal string>", p:[x,y,z] feet, yaw, kind:"human"\|"vehicle", hostile:bool}]` | 10 Hz while active: everything alive within 48 m, nearest first, at most 48 entries; an id missing from the list is gone (layer 2) |

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
  while forwarding.

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
