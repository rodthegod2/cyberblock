# Adding your own content (a datapack guide for friends)

Almost everything in Cyberblock that is a list of things is **data**, not code: the lines the people of Night City say, the skins
their figures wear, the shops, the places the Holo-Table can send you to, the Breach Protocol daemons, the Overlord's events, the
Heat tiers and more. A friend adds to them with an ordinary Minecraft **datapack**, with no programming, and `/reload` shows the
result. This page shows how, with one worked example for each of the six things people most often want to add.

The rule behind it is the same for every kind: **the mod ships defaults, a datapack adds files, and a datapack file with the same id
as a default replaces it.** A bad file is skipped with one log line and never stops the server.

**A worked example** to copy: `examples/content-datapack/` in the repository, a datapack called **Neon Crew**
that adds one of each (a dialogue line, a skin, a shop, a landmark, a Breach daemon and an Overlord event), with a valid
`pack.mcmeta` and an `assets/` folder for the resource-pack half (the skin picture and the lang keys). Copy it into
`<world>\datapacks\`, rename the namespace and change the words.

## Where the files go

```
<world>\datapacks\<your pack>\
    pack.mcmeta                                   (as in any datapack)
    data\<your namespace>\cyberblock\<kind>\<name>.json
```

- **`<your namespace>`** is a short lower-case name of your own (`friend`, your nickname). It keeps your ids apart from the
  mod's (`cyberblock:...`). The id of a file is `<namespace>:<name>`: `friend:corner_store`.
- **`<kind>`** is one of the kinds in the table below, **`<name>`** is the file name without `.json` (lower case letters, digits,
  `_`, `.`, `-`).
- `pack.mcmeta` is the usual datapack file: `{"pack": {"description": "My pack", "min_format": 121, "max_format": 121}}`.
  `121` is the data pack format of Minecraft 26.3. Write **both** `min_format` and `max_format`: Minecraft refuses a pack that
  has only one of them, and `tools.content_check` reports it too. Cyberblock does not care about it, Minecraft does.
- On a dedicated server the world folder is on the server, so the **server owner** puts the datapack there (a hosting panel's
  file manager is enough). A datapack in a singleplayer world or a world opened to LAN is yours to drop in. Only an
  operator can `/reload`.

## The format, for every kind

- One JSON object per file, UTF-8, at most 1 000 000 bytes, with `"schema": 1`.
- Strings have byte caps (a dialogue line 200 bytes, a name 48), colours are `#RRGGBB`, positions are Minecraft `[x, y, z]`, and
  references are ids (`friend:corner_store`).
- No duplicate keys, no comments: it is strict JSON.
- Every kind has a schema in `shared/content-schemas/<kind>.schema.json`. Its `description` field is the plain-language
  reference for the kind (every field, its limits and what the game does with it); open it when a field here is not enough.
- **Original text and art only.** Never paste dialogue, quest text or textures from either game.

## Check it before you load it

From a checkout of the repository:

```
uv run python -m tools.content_check --dir "<your pack folder or its data folder>"
```

It checks your files together with the shipped defaults (so a reference to `cyberblock:bartender` is found), prints one line per
problem as `<file>: <where>: <what>`, and exits 0 when everything is fine. The same tool checks the defaults in every test run.

## Load it

1. Put the pack folder into `<world>\datapacks\`.
2. As an operator type **`/reload`** (a restart does the same). The server log says
   `content: loaded <kind> N (<namespaces>)` once per kind, and `content: <kind> <id> skipped: <why>` for a bad file.
3. Players get what they need through the mod's own channel (`cyberblock:content`): nobody installs anything. **Textures** are
   different: they are resource-pack assets (`assets/<namespace>/textures/...`), so a skin needs a resource pack as well as the
   data file (see "A skin").
4. `/cyberblock status` lists how many files of each kind are loaded.

## The kinds

| Kind | What a file holds |
|---|---|
| `dialogue` | the original lines of one NPC role (greetings, barks, rumours, purchase and farewell lines) |
| `npc_role` | what a figure does when you right-click it: which screen, services, prices, drinks, where it stands in the hub |
| `skin_map` | which skin and player model an NPC figure wears (by role, faction, gender, archetype), and the two cyber skins |
| `vehicle_map` | which of the 12 vehicle models a Night City vehicle is drawn as, and its colour |
| `shop` | a stock list paid in Minecraft eddies, with limits and restock times |
| `device_map` | which Minecraft block a Night City device becomes (and which shops a vending machine sells from) |
| `material_palette` | which replica block a Night City surface becomes |
| `landmark` | a named place the Holo-Table can send you to and a gig can ask you to reach |
| `contract` | one fixer gig: text, objective, reward |
| `ripperdoc` | a clinic's implant catalogue, prices and surgery risk |
| `daemon` | one Breach Protocol daemon: its code sequence, the devices that offer it, the real action it runs |
| `breach_tier` | how hard a Breach is, by the device's security level |
| `event` | one Overlord event: cost, warning, effect |
| `spawn_palette` | one entry of the Overlord's spawn palette (Minecraft mobs and Night City enemies) and the Heat squads |
| `heat_tier` | one tier of the server's Heat meter |
| `match_mode` | a match mode for the Match Terminal (team deathmatch, Gang Wars, ...) |
| `hub` | the layout of the Afterlife hub |

## Recipes

Each recipe shows the file's path inside your pack and its whole content. All of them are checked by the test suite with
`tools.content_check`, so they are valid as they stand. Change the words, keep the shape.

### A dialogue line

A person picks its lines from **every** dialogue file whose `"role"` names its role, so to add lines you write a small file of
your own, in **your namespace**, that names the role. Your lines join the shipped ones: nothing is lost, and a later mod update
that adds lines to the bartender still reaches your server. `greeting`, `farewell` and `bark` are required in every file, so give
at least one line of each; the other contexts (`rumour`, `purchase`, `sale`, `no_funds`, `gig_offer`, `gig_done`, `drink`,
`healed`, `repaired`, `installed`, `surgery_ok`, `surgery_fail`) are optional. A line may use `{player}`, `{item}`, `{price}`,
`{title}`, `{reward}`, `{implant}` and `{drink}`.

`data/friend/cyberblock/dialogue/bartender_more.json`
```json
{
  "schema": 1,
  "role": "cyberblock:bartender",
  "lines": {
    "greeting": ["Pull up a stool, {player}. The neon is free, the drinks are not."],
    "farewell": ["Safe streets, {player}."],
    "bark": ["Somebody keep the music up, the silence is getting ideas."],
    "rumour": ["They say the late train to Arroyo runs on gossip alone."]
  }
}
```

After `/reload` the log line `content: loaded dialogue N (cyberblock, friend)` names your namespace. **Replacing** the shipped
lines instead is the general rule (a file with the same id as a default replaces it: here
`data/cyberblock/cyberblock/dialogue/bartender.json`); do that only when you want the shipped lines gone, because your copy then
has to carry every line you keep and no longer gets the mod's new ones. To give a **new role** its own lines, add an `npc_role`
file and a `dialogue` file with the role's id, and list its `tags` (the vendor types and content roles of the figures that get
this role).

### A skin

A skin is two things: a **data row** that says who wears it, and a **64 x 64 PNG** in a resource pack. The row:

`data/friend/cyberblock/skin_map/tyger_claws_plus.json`
```json
{
  "schema": 1,
  "texture": "friend:textures/entity/npc/tyger_claws_plus.png",
  "model": "classic",
  "faction": "TygerClaws",
  "gender": "male",
  "priority": 10
}
```

- `faction`, `role`, `gender` and `archetype` are the match keys: an NPC wears the row when every key the row has holds for it
  (a key may be one name or a list). Of the matching rows the highest `priority` wins, so a row with `priority` 10 beats the shipped
  rows (priority 0) for the same people; with the same id as a default it replaces that default.
- `texture` is the full resource id: `<namespace>:textures/entity/<anything>.png`. The PNG goes into a resource pack at
  `assets/friend/textures/entity/npc/tyger_claws_plus.png`. Every player needs that pack: the server owner can add it to the
  server's resource pack (see [SERVER.md](SERVER.md)), or you give your friends the pack.
- `model` is `classic` (4 pixel arms) or `slim` (3 pixel arms). A row without `gender` should use `classic`.
- Draw your own skin: no texture from either game.

### A shop

A shop is a stock list paid in Minecraft eddies (the `cyberblock:eddies` item; a credchip `cyberblock:eddie_chip` is 100).

`data/friend/cyberblock/shop/corner_store.json`
```json
{
  "schema": 1,
  "title_key": "friend.shop.corner_store",
  "restock": 900,
  "stock": [
    {"item": "minecraft:bread", "count": 4, "price": 8, "limit": 6},
    {"item": "minecraft:golden_carrot", "count": 2, "price": 22, "limit": 4},
    {"item": "minecraft:torch", "count": 16, "price": 10}
  ],
  "sell": [
    {"item": "minecraft:iron_ingot", "count": 4, "price": 12}
  ]
}
```

- `stock` rows sell `count` of `item` for `price` eddies, at most `limit` times between restocks (no `limit`: unlimited);
  `sell` rows are what the shop buys from you; `restock` is the seconds between restocks (0: never).
- `title_key` is a language key. A key no language file knows is shown as written, so either use a key of the mod
  (`cyberblock.afterlife.shop.arms`) or add the key to a resource pack's `lang` file.
- **Putting the shop somewhere:** a vending machine takes shops from its `device_map` file (`shops`, up to eight), and a person
  takes one from its `npc_role` file (`shop`, or `shop_by_tag` to pick by the vendor's type). Ship a changed copy of that
  file next to your shop (same id as the default replaces it), listing `friend:corner_store`.

### A landmark

A landmark is a named place the Holo-Table can spawn you at, and a fixer's gig can send you to (`reach_landmark`).

`data/friend/cyberblock/landmark/rooftop_garden.json`
```json
{
  "schema": 1,
  "name": "Rooftop Garden",
  "district": "Watson",
  "kind": "viewpoint",
  "pos": [-1200, 90, 640],
  "yaw": 180,
  "description": "A square of green that somebody carried up forty floors, one bucket at a time.",
  "approx": true
}
```

`pos` is a Minecraft position in the replica ([x, y, z]: Cyberpunk's (x, y, z) is Minecraft's (x, z, -y)). Set `approx` to `true`
while you are guessing: the table snaps your arrival to the ground and refuses a spawn with no ground instead of leaving you
stuck. `kind` is one of `district`, `plaza`, `market`, `transit`, `viewpoint`, `wilds`; `yaw` is the way you face (0 south, 90 west,
180 north, 270 east).

### A Breach daemon

A daemon is one run of hex codes the player must put into the Breach buffer, and the real thing it does. A datapack can
**re-skin** a daemon (another name, text, sequence, devices, weight) but cannot invent an action: the actions are a closed list
the scripts implement (`open_door`, `disable_cameras`, `reveal_enemies`, `overload`, `disable_turrets`, `lights_out`, `distract`,
`traffic_switch`, `make_friendly`).

`data/friend/cyberblock/daemon/quiet_hinges.json`
```json
{
  "schema": 1,
  "title_key": "friend.daemon.quiet_hinges",
  "text_key": "friend.daemon.quiet_hinges.text",
  "sequence": ["1C", "BD", "55"],
  "kinds": ["door", "gate"],
  "action": "open_door",
  "tiers": [
    {"seconds": 0, "radius": 8},
    {"seconds": 0, "radius": 15},
    {"seconds": 0, "radius": 25}
  ],
  "weight": 5,
  "min_security": 1
}
```

The codes come from the six of the grid (`1C`, `55`, `BD`, `E9`, `7A`, `FF`), two to four per daemon. `tiers` has exactly three rows
(strength 1, 2 and 3). The numbers in `tiers` are the action's documented defaults mirrored by the scripts: the game sends only the
strength, so changing them changes nothing in game. The schema's `description` lists them.

### An Overlord event

An event is something the Overlord Console can trigger on a picked spot of a match area, paid for with Threat Points.

`data/friend/cyberblock/event/long_blackout.json`
```json
{
  "schema": 1,
  "name": "Long Blackout",
  "description": "The lights of the area stay dark for a long, uncomfortable minute.",
  "cost": 35,
  "warning_s": 6,
  "cooldown_s": 180,
  "radius": 48,
  "scales_with_intensity": true,
  "night_city": true,
  "effect": {
    "type": "blackout",
    "seconds": 75
  }
}
```

`warning_s` can never be below 3: the players in the area always get the warning sound and marker first. `effect.type` is one of
`blackout`, `emp`, `airstrike`, `traffic_jam`, `supply_drop`, `weather` and `outbreak`, and each has its own fields (the schema's
`description` lists them). The Overlord's fairness rules (opt-in, minimum distance, spawn cap, intensity) apply to every event, yours
included. A `supply_drop` is capped at 100 eddies on purpose.

## When something does not show up

| What you see | What it means |
|---|---|
| `content: <kind> <id> skipped: <why>` in the server log | the file is wrong in the way `<why>` says; `tools.content_check` prints the same text |
| nothing at all in the log for your file | it is not at `data/<namespace>/cyberblock/<kind>/<name>.json`, or the datapack is not enabled (`/datapack list` shows it), or a sub-folder holds it (the loader reads only that folder, not below it) |
| your new row is ignored, the shipped one still wins | give it a higher `priority` (`skin_map`, `vehicle_map`) or the same id as the file it should replace |
| a screen shows a raw key such as `friend.shop.corner_store` | the language key has no entry: add it to a resource pack's lang file |
| `pack.mcmeta` refused ("must declare both min_format and max_format") | write both, as in "Where the files go" |
| a texture is missing (purple and black) | the PNG is not in a resource pack the player has, at the path the `texture` field names |
| the change shows for you but not for a friend | they have not rejoined or the server did not `/reload`; content reaches them through `cyberblock:content` |

The full list of kinds, their phases and the rule behind them is section 1.7 of the multiplayer plan
(`docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md`). Hosting a server for friends: [SERVER.md](SERVER.md). Joining one:
[JOIN.md](JOIN.md).
