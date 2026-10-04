# Adding your own content to Cyberblock (a datapack guide)

Cyberblock keeps its content as **data**, not code: the lines people say, the skins they wear, what a vending machine sells,
the places on the Holo-Table, the Breach Protocol daemons and the Overlord's events are all small JSON files. A friend adds
to them, or changes them, with an ordinary Minecraft **datapack**: no programming, no rebuild, and `/reload` shows the
change.

This guide walks through one worked example, [`examples/content-datapack/`](../examples/content-datapack/), a datapack called
**Neon Crew** that adds one of each: a dialogue line, a skin, a shop, a landmark, a Breach daemon and an Overlord event.
Copy it, rename the namespace and change the words.

- [1. The five minute version](#1-the-five-minute-version)
- [2. How a content file works](#2-how-a-content-file-works)
- [3. Recipes](#3-recipes): [dialogue](#a-a-dialogue-line) · [skin](#b-a-skin) · [shop](#c-a-shop) · [landmark](#d-a-landmark) ·
  [Breach daemon](#e-a-breach-daemon) · [Overlord event](#f-an-overlord-event)
- [4. Textures and lang files](#4-textures-and-lang-files)
- [5. Checking, reloading and troubleshooting](#5-checking-reloading-and-troubleshooting)
- [6. Every content kind](#6-every-content-kind)

---

## 1. The five minute version

1. Copy `examples/content-datapack/` to your world's `datapacks/` folder and rename it (for example `neon_crew`). A server
   keeps it in `<world>/datapacks/`; a single-player world in `saves/<world>/datapacks/`.
2. Run `/datapack list`. If the pack is listed as available and not enabled, `/datapack enable "file/neon_crew"`.
3. Run `/reload`. The server log prints one line per kind, for example `content: loaded landmark 15 (cyberblock, neon_crew)`
   (the 14 shipped landmarks plus yours), and `/cyberblock status` lists how many entries of each kind are loaded.
4. Walk up to a vending machine, open the Holo-Table, or ask the Overlord Console for events: your content is there.

To check a pack **before** you load it, from the Cyberblock repository:

```
uv run python -m tools.content_check --dir path/to/neon_crew
```

It validates every file against the schema of its kind, checks that references (shops, roles, spawn entries) point at files
that exist, checks that `pack.mcmeta` is usable, and prints one line per problem. Exit code 0 means nothing is wrong.

---

## 2. How a content file works

**Where files live.** Every datapack file is `data/<namespace>/cyberblock/<kind>/<name>.json`. The `<namespace>` is yours (use
one word, lower case, such as `neon_crew`); the mod's own files are in the namespace `cyberblock`, inside the mod jar.

```
neon_crew/                          <- the datapack folder, goes in <world>/datapacks/
  pack.mcmeta                       <- required: Minecraft ignores a folder without it
  data/
    neon_crew/cyberblock/
      dialogue/bartender_extra.json
      skin_map/neon_runner.json
      shop/neon_stall.json
      landmark/rooftop_diner.json
      daemon/unlatch.json
      event/flash_blackout.json
    cyberblock/cyberblock/
      device_map/vending_machine.json   <- same id as a shipped file: replaces it
  assets/neon_crew/...              <- textures and lang: see section 4 (not part of a datapack)
```

**The id** of a file is `<namespace>:<name>`: `data/neon_crew/cyberblock/shop/neon_stall.json` is `neon_crew:neon_stall`.
Other files point at it by that id (a vending machine lists the shops it sells by id).

**`pack.mcmeta`** is the one file Minecraft needs to treat the folder as a datapack:

```json
{"pack": {"description": "Neon Crew", "min_format": 121, "max_format": 121}}
```

`121` is the data pack format of Minecraft 26.3, the version Cyberblock runs on.

**Every file is one JSON object** that starts with `"schema": 1`. Text has a length cap (counted in bytes), colours are
`"#RRGGBB"`, positions are Minecraft `[x, y, z]`, and references are ids. The exact rules of each kind are in
`shared/content-schemas/<kind>.schema.json`; the `description` at the top of each schema explains every field in plain
words.

**Replace or add.** A new id adds an entry. A file with the **same id as a shipped file** replaces it (put it in the
`cyberblock` namespace, with the shipped file's name). Where a kind says so (dialogue, skins) several entries combine
instead; the recipes say which.

**A bad file never stops the game.** The server skips it and logs one line, `content: <kind> <id> skipped: <why>`, and keeps
every other file. Fix the file and `/reload`.

---

## 3. Recipes

### a. A dialogue line

Kind `dialogue`. Each NPC role (`bartender`, `fixer`, `medic`, `mechanic`, `ripperdoc`, `vendor`) speaks the lines of its own
file **and of every file whose `role` names it**, so a datapack adds lines beside the shipped ones without copying them:

```json
{
  "schema": 1,
  "role": "cyberblock:bartender",
  "lines": {
    "greeting": ["The Neon Crew drinks here on Fridays, {player}. Pull up a stool."],
    "farewell": ["Tell them the Neon Crew sent you."],
    "bark": ["Somebody keeps leaving glow sticks in the ice bucket. I have stopped asking."],
    "rumour": ["There is a crew painting rooftops in colours the cameras cannot name."]
  }
}
```

- `lines` maps a **context** to its lines (1 to 32 per context, at most 200 bytes each). The screen picks the context from
  what just happened and a line at random: `greeting`, `farewell`, `bark` (idle remarks), `rumour`, `purchase`, `sale`,
  `no_funds`, `gig_offer`, `gig_done`, `drink`, `healed`, `repaired`, `installed`, `surgery_ok`, `surgery_fail`.
- The schema asks every file for `greeting`, `farewell` and `bark`, so an add-on file brings one of each next to the new
  line.
- A line may use `{player}`, `{item}`, `{price}`, `{title}`, `{reward}`, `{implant}` and `{drink}`; the screen fills them in.
- Write your own words. Never copy lines from the game.
- To **replace** a role's lines instead, ship a file with the shipped id (`data/cyberblock/cyberblock/dialogue/bartender.json`).

### b. A skin

Kind `skin_map`. A row says which skin and player model a Night City person wears. A person matches a row when **every key
the row has** holds for them; of the matching rows the highest `priority` wins.

```json
{
  "schema": 1,
  "texture": "neon_crew:textures/entity/npc/neon_runner.png",
  "model": "slim",
  "faction": ["Maelstrom", "Scavengers"],
  "gender": "female",
  "priority": 10
}
```

- Match keys: `role`, `faction`, `gender` (`male` or `female`) and `archetype`. A value is one name or a list. The names are
  the game's own (`faction`: `Maelstrom`, `NCPD`, `TygerClaws`, ...); the shipped files in `skin_map/` show the spellings.
- `model` is `classic` (4 px arms) or `slim` (3 px arms). Use `classic` for a row without `gender`.
- `priority` above the shipped rows (they use 0) makes your row win; without it ties are broken by how many keys match.
- `texture` is the id of a 64x64 skin PNG. The picture comes from a resource pack, see [section 4](#4-textures-and-lang-files).
- Rows with `"cyber": "male"` or `"female"` instead of match keys are the skins of linked players.

Vehicles work the same way with kind `vehicle_map` (which of the twelve vehicle models a vehicle class uses, and its colour).

### c. A shop

Kind `shop`. A stock list paid in **eddies** (the `cyberblock:eddies` item; a credchip is worth 100):

```json
{
  "schema": 1,
  "title_key": "neon_crew.shop.neon_stall",
  "restock": 900,
  "stock": [
    {"item": "minecraft:glow_berries", "count": 8, "price": 6, "limit": 8},
    {"item": "minecraft:sea_lantern", "count": 2, "price": 30, "limit": 4}
  ],
  "sell": [{"item": "minecraft:glow_ink_sac", "count": 4, "price": 5}]
}
```

- Each `stock` row sells `count` of `item` for `price` eddies, at most `limit` times between restocks (leave `limit` out
  for unlimited). `sell` rows are what the shop buys. `restock` is the seconds between restocks (0: never).
- `title_key` is a **lang key** for the shop's title (see [section 4](#4-textures-and-lang-files)).
- A shop does nothing until something sells it. A **vending machine** picks from the `shops` list of its `device_map` file, so
  the example ships a copy of the shipped file `cyberblock:vending_machine` with the new shop added to the list
  (`data/cyberblock/cyberblock/device_map/vending_machine.json`). Because it has the shipped id, it replaces the original.
  A **vendor in the Afterlife** sells the shop named in its `npc_role` (`shop`, or `shop_by_tag`).

### d. A landmark

Kind `landmark`. A named place the Holo-Table can send a player to and a fixer's gig can name:

```json
{
  "schema": 1,
  "name": "Neon Crew Rooftop Diner",
  "district": "Watson",
  "kind": "viewpoint",
  "pos": [210, 38, -120],
  "yaw": 180,
  "description": "A rooftop booth lit by glow sticks, open to anyone who climbs the fire escape.",
  "approx": true
}
```

- `pos` is a **Minecraft** position in the replica of Night City. Cyberpunk's position `(x, y, z)` is the Minecraft position
  `(x, z, -y)`, so a spot at Cyberpunk `(210, 120, 38)` is `[210, 38, -120]`.
- `yaw` is the way the player faces on arrival: 0 south, 90 west, 180 north, 270 east.
- `kind` is `district`, `plaza`, `market`, `transit`, `viewpoint` or `wilds`.
- `approx: true` says the position was guessed. The table snaps the arrival to the ground and refuses the spawn when there is
  none (the player is never stuck); a gig counts an approximate point by x and z only. Measure the spot in game and set
  `approx` to `false` (or leave it out).

### e. A Breach daemon

Kind `daemon`. A daemon is one sequence the player must enter in a Breach Protocol puzzle, and the real effect it has. The
effect (`action`) is one of a **closed list** the game scripts implement; a datapack can re-skin an action (a new name, text,
sequence, kinds, weight and minimum security) but cannot invent a new action.

```json
{
  "schema": 1,
  "title_key": "neon_crew.daemon.unlatch",
  "text_key": "neon_crew.daemon.unlatch.text",
  "sequence": ["FF", "1C", "55"],
  "kinds": ["door", "gate"],
  "action": "open_door",
  "tiers": [{"seconds": 0, "radius": 8}, {"seconds": 0, "radius": 14}, {"seconds": 0, "radius": 24}],
  "weight": 4,
  "min_security": 1
}
```

- `sequence` is 2 to 4 of the six grid codes `1C`, `55`, `BD`, `E9`, `7A`, `FF`.
- `action` is one of `open_door`, `disable_cameras`, `reveal_enemies`, `overload`, `disable_turrets`, `lights_out`, `distract`,
  `traffic_switch`, `make_friendly`.
- `kinds` lists the device kinds whose Breach may offer it (`door`, `gate`, `light`, `camera`, `terminal`, `vending`,
  `traffic`, `elevator`, `turret`, `screen`, `container`). `weight` (1 to 10) is how often it is picked; `min_security` (1 to 3)
  is the lowest device security it is offered at.
- `tiers` are exactly three rows, strength 1, 2, 3. Copy the shipped action's rows: the game sends only the strength, so
  changing them here changes nothing in game.
- Give it a sequence no shipped daemon uses, so the player can tell which daemon a run solves.

### f. An Overlord event

Kind `event`. One thing the Overlord Console can trigger on a spot of the match area for Threat Points:

```json
{
  "schema": 1,
  "name": "Flash Blackout",
  "description": "Every light in the area snaps off for a few seconds. Short, cheap and rude.",
  "cost": 12,
  "warning_s": 4,
  "cooldown_s": 45,
  "radius": 14,
  "scales_with_intensity": false,
  "night_city": true,
  "effect": {"type": "blackout", "seconds": 10}
}
```

- `cost` is in Threat Points. `warning_s` (never below 3) is how long players get the sound and marker before it lands;
  `cooldown_s` is how long before it can be queued again; `radius` is the reach in blocks.
- `effect` is one of seven shapes, chosen by `type`: `blackout`, `emp`, `airstrike`, `traffic_jam`, `supply_drop`, `weather`
  and `outbreak`. Each has its own fields (the schema lists them). An `outbreak` names a `spawn_palette` entry in `spawn`.
- The fairness rules are not data: an event never breaks blocks, a supply drop cannot be opened by the Overlord who paid for
  it, and every effect names the Overlord as its cause.

---

## 4. Textures and lang files

A datapack carries **data**. Pictures (a skin PNG) and words for lang keys (`title_key`, `text_key`) are **resource-pack
assets**, which a datapack folder does not deliver.

- The example's `assets/neon_crew/` folder holds the skin at `textures/entity/npc/neon_runner.png` (a plain 64x64 placeholder
  drawn for the example; make your own) and `lang/en_us.json` with the three keys the example uses.
- To use them, make a **resource pack** next to the datapack: a folder in `resourcepacks/` with its own `pack.mcmeta` (see
  `respack/pack.mcmeta` in the repository: resource pack format 97) and the `assets/` folder inside it.
- On a dedicated server, the server owner puts the same assets in the server's resource pack, which clients download when
  they join (`respack/`, built by `uv run python -m tools.respack`).
- Without the pictures a client shows Minecraft's missing-texture skin; without the lang entries it shows the raw key. The data
  itself loads either way.

Use only art you made or may share. Never put game assets in a pack.

---

## 5. Checking, reloading and troubleshooting

| What you see | What it means |
|---|---|
| `content: loaded <kind> N (cyberblock, neon_crew)` | the kind loaded, N entries, from these namespaces |
| `content: <kind> <id> skipped: <why>` | that one file was refused; the reason names the field |
| `content: kind <kind> has no loader` | a mod bug: tell the maintainer (every kind should have a loader) |
| `FAIL <file>: <where>: <what>` from `content_check` | the file breaks the schema; `<where>` is the path to the field |
| `refers to <kind> "<id>", which does not exist` | a reference (a shop, a role) points at a missing file |
| `pack.mcmeta: missing` | Minecraft ignores a datapack folder without one |

- `/reload` re-reads every file. Edit, save, `/reload`, look again. Clients get the kinds they need (the material palette of
  the replica) in the same moment; the rest is used on the server and reaches players through the screens.
- `/cyberblock status` (as an operator) lists how many entries of each kind are loaded, on a line starting `content:`.
- If an edit does not show, check the log for a `skipped` line first, then that the id is what you think it is (the folder
  name is the namespace, the file name is the name).
- A file larger than 1 000 000 bytes is refused. `content_check` also refuses a duplicate key and a stray comma, which are easy to miss by eye.

---

## 6. Every content kind

| Kind | Holds | Schema |
|---|---|---|
| `material_palette` | Cyberpunk surface material (or a heuristic class) to replica block | `material_palette.schema.json` |
| `device_map` | Cyberpunk device class to device kind, block, size, vending shops | `device_map.schema.json` |
| `shop` | stock and sell lists, prices, restock times | `shop.schema.json` |
| `skin_map` | NPC role, faction, gender, archetype to skin and model; cyber skins | `skin_map.schema.json` |
| `vehicle_map` | vehicle class to one of the 12 vehicle models and its colour | `vehicle_map.schema.json` |
| `daemon` | Breach daemon sequences and the action each does | `daemon.schema.json` |
| `breach_tier` | grid size, buffer and timer by device security and Firewall rank | `breach_tier.schema.json` |
| `dialogue` | original lines per NPC role | `dialogue.schema.json` |
| `contract` | fixer gigs: text, objective, reward | `contract.schema.json` |
| `ripperdoc` | implant catalogue, prices, surgery risk | `ripperdoc.schema.json` |
| `landmark` | places for the Holo-Table and reach gigs | `landmark.schema.json` |
| `npc_role` | which screen a role opens, its services, where it lives in the hub | `npc_role.schema.json` |
| `event` | Overlord events: cost, warning, effect | `event.schema.json` |
| `spawn_palette` | Overlord spawn entries and their Threat Point costs | `spawn_palette.schema.json` |
| `heat_tier` | Server Heat tiers 1 to 5 | `heat_tier.schema.json` |
| `match_mode` | match modes: rules, time limits, zone settings | `match_mode.schema.json` |

The schemas are in `shared/content-schemas/`. The Afterlife hub layout (`hub`) is also a content file, but it is built once
into the hub dimension and is not reloaded.
