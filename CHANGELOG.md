# Changelog

All notable changes to Cyberblock are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). One version number covers the DLL, the scripts, the shader, the Minecraft mod
and the tools.

## [1.0.0] - unreleased
Version 1.0.0 is set in the files on 2026-10-03; it is tagged and published only after the in-game re-test
([docs/TESTING-PLAN.md](docs/TESTING-PLAN.md), "Re-test 3") has passed.

### Added
- **Layer 1, the passthrough.** Real Minecraft Java 26.3 (Fabric mod) is drawn into Cyberpunk 2077 2.31 every frame through a
  ReShade add-on, with Cyberpunk's camera, depth and a Night City collision map (barrier blocks) around V. F9 toggles it;
  V is hidden and invincible, Steve's health is the only health.
- **Layer 2, building and explosions.** Placed blocks are collision boxes in Night City; TNT, creepers and fireworks become
  Cyberpunk explosions; weapons, arrows and fire hurt NPCs and vehicles through invisible proxies.
- **Layer 3, mobs against Night City.** Hostile mobs attack pedestrians; NPCs and NCPD shoot at puppets standing in for the mobs.
- **Layer 4, movement.** Elytra glides and wind-charge or mace launches move Steve with Minecraft physics while the DLL moves V
  every rendered frame; ender pearls teleport V.
- **Layer 5, interactive items.** Leads, fishing rods, wind charges, shields and potions that act on V.
- **Layer 6, Cyberpunk in Minecraft form.** The hack key, RAM, ten quickhack chips, a 20-perk skill tree and the starter kit.
- **Cyberware.** 17 implants in ripperdoc slots with a Cyberware screen (U): Mantis Blades, Gorilla Arms, Monowire, Projectile
  Launch System, Reinforced Tendons, Fortified Ankles, Lynx Paws, Sandevistan, Berserk, Cyberdeck, Kerenzikov, Optical Camo,
  Subdermal Armor, Kiroshi Optics, Biomonitor, Second Heart and the RAM Upgrade.
- **Re-test 3, interface.** Minecraft draws every piece of Cyberpunk's interface: radar, eddies, wanted level, quest and
  waypoint, interaction prompts, dialogue choices, subtitles, toasts, phone calls, journal, phone, atlas, inventory, skills and
  the Night City pause screen. Cyberpunk's own layers stay invisible while Cyberblock is on. Menus that cannot be replaced
  (shops, ripperdoc, stash, crafting, breach protocol, settings, saves, death, loading) are restyled in Minecraft colours with
  a Minecraft frame. A third-person view on F5.
- **Re-test 3, quickhacks.** The chip in your hand fires; every chip has its own Minecraft effect (lightning, flames, ink, real
  cobwebs, poison cloud, wither trail, outlines through walls, swirls, a creeper face, per-device effects) played when the
  upload completes, and a distinct Cyberpunk effect, with visible extras on crowd pedestrians. New wire messages `hackspread`
  and the `upload` and `kind` of `hackresult`.
- **Re-test 3, vehicles.** Steve rides along: his hand in a first-person vehicle camera, the whole seated Steve in a
  third-person one (the hotbar, survival bars and crosshair are hidden in a vehicle).
- **Re-test 3, cyberware.** Separate keys: Y for the Operating System implant, L for the Arms implant, O for Optical Camo
  (six rebindable keys, clash-free); two Operating System places (a Cyberdeck and Sandevistan or Berserk together); active
  abilities for Mantis Blades (lunge), Gorilla Arms (slam) and Monowire (lash); original 3D models for the arm implants in
  first and third person; a Sandevistan screen effect in ReShade with a strength slider; Steve's cyber look and an optical
  camo shimmer; advancements.
- **Release 1.0.0.** A release zip with an installer (`install.bat`, `uninstall.bat`, `install.ps1`), a launcher install
  with a "Play Cyberblock" button, GitHub Actions CI, this changelog, licence and third-party notices.
- **Tools.** `tools.fakehost` modes `--ui`, `--hacks` and `--seat`; `tools.package` builds the release zip.

### Changed
- Cyberpunk's HUD no longer comes back for prompts, dialogues or vehicles: Minecraft draws them.
- Melee, sprint, gauntlet and Gorilla Arms hits push NPCs like Minecraft does (stagger, knockdown and a short ragdoll only on
  strong hits) instead of flinging everyone.
- The glide steers with the camera like the vanilla elytra (the DLL owns the glide's yaw).
- Implants no longer share one activation key or a tap-and-hold selection.

### Fixed
- Re-test 3: every hack looked the same because the hack wheel preferred its remembered pick over the chip in the hand.
- Re-test 2: the elytra teleported and updated once a second (V is now moved every frame); hacks said "target lost" because of
  a wrong entity id; blocks floated a block above the street (a per-world ground alignment, `/cyberblock level`); a wind charge
  could not launch Steve; perks can be free (`/cyberblock perks free on`).
- Re-test 1: the elytra gate treated open-world V as in a scene; the NPC list was empty; key clashes between Cyberpunk and
  Minecraft; a "standing on air" log spam; typing in Minecraft screens; car hacks and scans.

## Planned
- A ripperdoc flow with eddy costs for implants (needs verified money and transaction plumbing).
- A cyberware capacity budget.
- Gamepad steering of the elytra glide.
- Screenshots in the README and the releases page.
