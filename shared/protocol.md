# Cyberblock wire protocol v1 and shared-memory frame

Contract between the Minecraft mod (`mc/`), `Cyberblock.dll` (`cp/plugin/`) and the test tools (`tools/`).
Change it only through the integrator. Coordinate conversion lives in `tools/cbmath.py`
(Cyberpunk `(x, y, z)`, Z-up → Minecraft `(x, z, -y)`; back: Minecraft `(x, y, z)` → Cyberpunk `(x, -z, y)`);
everything on the wire is already in Minecraft convention, in both directions.

## Wire protocol v1 (WebSocket on `127.0.0.1:25600`, Minecraft is the server)

Messages are JSON text frames, one object per frame, discriminated by `t`.

### Cyberpunk → Minecraft (all values already Minecraft convention)
| `t` | fields | when |
|---|---|---|
| `hello` | `proto:1, game:"cp2077", build:"2.31"` | on connect |
| `cam` | `f` frame (long), `p:[x,y,z]` camera, `r:[yaw,pitch,roll]` deg, `fov` vertical deg, `fp:true`, `pl:[x,y,z]` feet, `h` body yaw deg | every Cyberpunk frame while active |
| `ground` | `c:[x,z,yBottom,yTop, …]` inclusive | probe deltas (new solid ranges) |
| `unground` | `c:[x,z,yBottom,yTop, …]` | probe deltas (ranges no longer solid / out of radius) |
| `clear` | — | on activate, deactivate, teleport > 64 m |
| `state` | `active:bool, vehicle:bool` | on change |
| `view` | `w, h` | on connect and when Cyberpunk's backbuffer size changes |
| `aim` | `d` metres from the camera to the Night City surface under the crosshair, `-1` if none within 8 m | on change (2 cm), 10 Hz; Minecraft caps block targeting at `d` |
| `hud` | `hidden:bool` | on change (hidden in vehicles) |
| `key` | `k` ∈ `use, attack, pick, inventory, drop, swap, chat, command, escape`, `down:bool` | input |
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
| `hit` | `target:"<id>"`, `dmg` (Minecraft health points after armour), `kind` ∈ `melee, arrow, trident, mob, explosion, fire`, `impulse:[x,y,z]` m/s | a proxy takes damage (layer 2) |
| `ignite` | `target:"<id>"`, `seconds` | a proxy is set on fire: fire charge, flint & steel, lava, fire aspect, flaming arrow (layer 2) |

Unknown `t` values are ignored by both sides (forward compatibility for layers 2–6).

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
- **Block cells** are Minecraft cells; the DLL maps a block `(bx, by, bz)` to the Cyberpunk box `(bx, -(bz+1), by)`
  … `(bx+1, -bz, by+1)` (`tools/cbmath.py` `mc_block_to_cp_aabb`).

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
