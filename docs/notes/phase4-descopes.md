# Phase 4 (the device layer): descopes and risks

Plan: `docs/superpowers/plans/2026-10-04-multiplayer-megaplan.md` section 8 (deliverables, "Descopes and risks") and the dated Phase 0
amendment after section 1.9. Written by package 4P with the docs; the integrator adds what the other packages found.

## Descoped by the Phase 0 research (written fallbacks, applied now)
- **Lift call and vending purchase are greyed.** `call` and `buy` are not Cyberpunk actions: a lift call is unproven, vending is a Minecraft
  shop paid in eddies. The server greys the controls (`cyberblock.device.unsupported`) and never sends them; a DLL that gets one anyway
  answers `unsupported`. `breach` belongs to Phase 6 (a Minecraft minigame whose daemons send the supported acts).
- **No size.** The plugin cannot read a device's box (no general bounding-box API in 2.31), so `devices` carries no `size`: Minecraft
  takes it from `device_map`.
- **Position-based ids when needed.** If the game's entity hash is not steady (CET C5), the id is the hash of the position rounded to 25 cm
  plus the class: a device that moves is then a new device.
- **Polling fallback.** If no change event arrives (CET C3/C4), the plugin polls twice a second within 48 m: NPC changes still show, later.
- **Containers.** `gameContainerObjectBase` is not a device class: containers are read-only crates unless the loot can be read.

## Limits of this version of the contract
- **Only `st` and `fl` travel.** A lift's floor, a traffic light's colour, a camera's alarm and a turret's target have no field in the
  `devices` shape of megaplan section 1.9, so those blocks show on/off and locked/open only. The in-game check "a camera's cone turns red when it
  spots the guest" therefore depends on a field that does not exist yet: Re-test 4 (P4-4) says so and asks for what the cone does.
- **`disable` reads as quest-locked.** The amendment makes a `DISABLED` device carry flag 4 (quest-locked), and a quest-locked device is never
  touched, so a device switched off with `disable` cannot be enabled again by Minecraft. The server's rules use `on` and `off` for cameras and
  turrets for that reason; the fake host lets `enable` undo its own `disable` only.
- **Guest requests are `by:guest` only through a 2 s window.** The DLL cannot tell which player asked: it remembers a `devact` request for 2 s
  and calls the resulting change `guest`. A change that happens later is `host`, `npc` or `script`.
- **Reach and rate.** A device use farther than 8 blocks is refused; two uses by one player within 4 ticks count once.

## Risks
- **The first real run is the test.** Nothing of the device layer has run against a live Cyberpunk game: the action names, the events and
  the id stability are the Phase 0 paper verdicts. Re-test 4 (P4-9) prints which path is active.
- **`devact` payload.** Megaplan section 1.3 names the `cyberblock:act` type `devact` but not its fields; 4P documented what the server accepts
  (`pos`, `act` or `use`, `i` and `n` for `buy`) from 4M's receiver. A later change on either side must change `shared/protocol.md` and
  `tests/test_protocol_md.py` together.
- **Devtools command.** `tools.mpcheck devices` drives the guest with `/cyberblock test device <x> <y> <z> <act>` (4M); without it that scenario
  cannot run.
- **Rate.** `devices` and `devstate` share the 10 per second (burst 40) bucket: 64 devices with a change every 0.5 s fit, a crowd of NPC doors may not.

## Contract additions made by 4P
- `devices` has no full or partial mark: every entry is current data and the server keeps a device alive while some list names it (10 s).
- `devact.reason` texts used by the scripts and the fake host: `unsupported`, `quest locked`, `device gone` (free text, at most 128 bytes).
- The `cyberblock:act` `devact` payload (`pos`, `act`, `i`, `n`) and the act `use`.
