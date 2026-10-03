# Cyberblock — in-game test session (2026-10-03)

Everything below is built, unit-tested (Python 182, JUnit 228, C++ 189), compile-checked against the real 2.31 game
scripts, reviewed and installed — but none of layers 2-6 has run inside Cyberpunk yet. Work top to bottom; each step
says what should happen. Tell Claude "step N: what you saw" and it reads `red4ext\logs\Cyberblock.log`, Minecraft's
`mc\run\logs\latest.log` and captures the game window to diagnose.

## 0. Before launching
- Close Roblox Studio (and check Task Manager for leftover `RobloxStudioBeta` processes) — memory headroom.
- Cyberpunk: Frame Generation **Off**, Resolution Scaling Off for the first run.
- In `D:\Cyberblock`: `uv run python -m tools.play` (starts Minecraft, waits for it, then starts Cyberpunk).
- Load a save outdoors in a busy street (NPCs, cars, NCPD nearby). Press **F9**.

## 1. Layer 1 acceptance (5 min)
1. Walk up stairs and under an overpass: Steve's hand/hotbar stay; placed blocks rest on Night City floors.
2. Look at a wall that has a Minecraft block behind it: **no block outline shows through the wall** (aim-limit fix).
3. Get in a car and out again: Cyberpunk HUD returns while driving; Steve/hotbar return on foot.
4. Close the Minecraft window mid-walk: within ~2 s V is restored with a notification. Restart Minecraft: it resumes.
5. Save while active, then load: the save loads normally.

## 2. Cyberpunk mechanics disabled while active (2 min)
6. Tab does not scan, X does not heal, no double jump / dodge / air dash, Alt opens no radial wheel, weapons cannot be
   drawn, phone (T) goes to Minecraft chat instead.
7. Esc opens Cyberpunk's pause menu; inside it, keys do not move/act Steve; after closing, Minecraft input works again.

## 3. Health link — "Steve's health is the only health" (5 min)
8. Let NCPD/gangers shoot V: Steve's hearts drop, V never dies.
9. Jump from ~10 m: fall damage on Steve's hearts. Let a car hit V: damage. Stand near a grenade/fire: damage.
10. Die in survival: Minecraft's death screen appears; click Respawn with the mouse; play continues.
11. `/gamemode creative` (Alt+Tab, type in Minecraft, Alt+Tab back): nothing hurts Steve or V.

## 4. Layer 2 — blocks, explosions, weapons, fire (10 min)
12. `/cyberblock kit` (or in survival the kit arrives on first activation).
13. Build a 3-block-high wall across a sidewalk and a road: pedestrians and cars stop at it; V can stand on it.
14. Place TNT under a parked car and light it: real Cyberpunk explosion, car destroyed, NCPD heat.
15. Sword a pedestrian, shoot one with the bow: they take damage/ragdoll, heat appears.
16. Flint & steel (or a fire charge) on an NPC: they burn.

## 5. Layer 3 — mobs vs Night City (5 min)
17. Spawn eggs (zombie, skeleton, creeper) near pedestrians/gangers: mobs attack NPCs; gangers/NCPD shoot back and can
    kill mobs; a creeper's explosion damages Night City.

## 6. Layer 4 — elytra & ender pearls (5 min)
18. From a rooftop with an elytra equipped, jump and press Space while falling: glide; fireworks boost; landing returns
    normal movement. Open the pause menu mid-air for 2 s, resume: nothing breaks.
19. Throw an ender pearl: V teleports where it lands; no fall damage spike.

## 7. Layer 5 — interactive items (5 min)
20. Lead on a pedestrian and on a car, walk away: they are dragged. Use the lead again to release.
21. Fishing rod: hook an NPC/car and reel in. Wind charge: knockback blast. Shield raised toward a shooter: blocked.
22. Potions: speed (V faster), jump boost (higher jump), invisibility (hostiles lose V).

## 8. Layer 6 — Cyberpunk in Minecraft form (10 min)
23. RAM bar on the Minecraft HUD; it regenerates.
24. Hold **G** on a ganger: glowing outline + tooltip (name, faction, threat). On a vending machine: device info.
25. Use each quickhack item on gangers: Redstone Charge (short circuit), Blaze Chip (overheat), Ink Sac Daemon
    (blind), Cobweb Daemon (cripple), Spider Eye Virus (contagion), Wither Skull Daemon (synapse burnout), Compass Ping,
    Bottle of Forgetting (memory wipe), Creeper Brain (cyberpsychosis). Redstone Lever on a vending machine, a camera,
    a traffic light.
26. **K** skill tree: `/xp add @s 30 levels`, buy Double Jump (V double-jumps), Sandevistan Sprint (double-tap Shift
    → slow-mo 5 s), Iron Skin (+4 hearts), Silent Steps, Chain Hack; check each does what its tooltip says.
27. Mantis blade / gorilla gauntlet items (stretch).

## 9. Safety pass (3 min)
28. F9 off/on 10 times; drive; save and load while active: V always comes back normal (HUD, weapons, no god mode,
    visible body, normal time speed).
29. Optional: `uv run python -m tools.install --remove` then reinstall — the game folder is left exactly as before.

---

## Appendix — every in-game check the build agents recorded

### Layer 2 — track P
- [ ] Track M's M6 oracle: with the dev Minecraft client running on a test port, run 'uv run python -m tools.fakehost --layer2 --url ws://127.0.0.1:<port> --shm <name> --cmd "summon tnt 0 64 0" --hold 15' and confirm that explosion arrives (power 4), that block events arrive when blocks are placed or destroyed, that hit arrives when a bow or sword hits a proxy, and that Steve loses 5 hearts (25% of max health) from the dmg. Only Minecraft can show this, since the fake host has no real Minecraft side.
- [ ] Integration: confirm the DLL's own copy of the layer-2 conversions (cp/plugin/src/cbmath.*) passes the new mc_pos and mc_block vectors in shared/vectors.json.

### Layer 2 — track M
- [ ] With the dev client on a test port (-Pcyberblock.port, -Pcyberblock.frame) and the real DLL and updated S1 script installed: /fill a region of about 2000 cells (e.g. fill 30 70 30 50 80 50 stone) and then clear it; confirm in Night City that Cyberblock_Log shows no queue drops (QueueStats dropped stays 0) and that every collision box appears and disappears, with no invisible walls left behind. Rerun mc/tools-dev/layer2_check.py against the dev client to confirm the 363-cell fill/clear checks still pass with the slower pacing (expected about 1.2 s per fill).

### Layer 2 — track D
- [ ] After loading, red4ext/logs/Cyberblock.log shows 'redscript natives registered' and no 'native ...: unknown parameter type' or 'unknown return type' lines. This is where array:Uint64, array:Float, array:Bool and Uint64 resolve in the game's RTTI at post-register time. Layer 1 only proved array:Vector4. If any of these lines appear, the redscript call to that native will misbehave.
- [ ] With the mashup Active and Minecraft attached: run /summon tnt in Minecraft. A CET console call to Cyberblock_NextEvent() should return the CName explosion, then Cyberblock_EventPos() should be the TNT position in Cyberpunk space and Cyberblock_EventAmount() should be 4.0. A second call to Cyberblock_NextEvent() should return an empty CName. Once S1's pump lands the scripts drain events themselves, so this check is easiest before then or with the pump disabled.
- [ ] The 5 s memory line in the log reads 'mc events: N queued, M dropped, K rejected'. In normal play dropped and rejected stay 0 and queued is near 0. A climbing dropped count means the redscript pump is not draining events (up to 64 per tick).
- [ ] Yaw convention for Cyberblock_SubmitNpcs: the DLL assumes Cyberpunk yaw in degrees as Entity.GetWorldYaw reports it (0 faces +Y, counter-clockwise from above). Stand an NPC facing north and check that its Minecraft proxy faces the same way (Cyberpunk yaw 0 becomes Minecraft yaw 180, facing -Z). S1's Npcs.reds must pass degrees, not radians.
- [ ] Cyberblock_Damage: shoot V and check the Minecraft log or fakehost shows a dmg with a sensible frac and a from position that is correctly converted (Cyberpunk shooter at (x, y, z) arrives as Minecraft (x, z, -y)).

### Layer 2 — track S1
- [ ] God mode still reaches the damage pipeline (Active, on foot): let an NPC shoot V. Cyberblock.log should show 'dmg bullet <dmg> of <max> from known (Attacks....)'. In CET, Game.GetStatPoolsSystem():GetStatPoolValue(Game.GetPlayer():GetEntityID(), gamedataStatPoolType.Health, true) should stay at 100. If no dmg line appears, the native layer skipped OnHit for the invulnerable player.
- [ ] Fall damage: jump from about 10-15 m while Active. Expect a 'dmg fall' line, and frac >= 1 for a death landing. If there is no line, the landing AoE attack does not hit its own instigator (self-damage flag missing from the effect set).
- [ ] Vehicle hit: let a car hit V while Active. Expect 'dmg vehicle ... from known'. Check that the from point (Minecraft knockback direction) is on the car's side, which confirms the hitDirection sign used in the fallback.
- [ ] Grenade or explosive barrel near V: expect 'dmg explosion'. Standing in fire: expect 'dmg fire' or 'dmg explosion'.
- [ ] NPC registry: on a busy street while Active, the 5 s line 'npcs: N sent of M NPCs/vehicles attached' should show M > 0 and N <= 48. Stand next to parked or moving traffic and confirm vehicle proxies appear in Minecraft; this proves the native type name n"vehicleBaseObject" resolves in EntityTarget.Type. Also confirm M does not keep growing over minutes (detach pruning works).
- [ ] Yaw: an NPC proxy's facing in Minecraft matches the NPC in Night City. Or compare CET npc:GetWorldYaw() with Rad2Deg(atan2(-fwd.x, fwd.y)).
- [ ] Codeware entity callbacks during a heavy spawn (fast travel into a crowded area): no crash or script errors. The callbacks only push or remove weak refs in a script array, which assumes entity attach runs on the game thread.
- [ ] After a save or load while Active: the log shows the collision clear lines and boxes come back on the next live tick (needs S2's Collision).

### Layer 2 — track S2
- [ ] Sword kill on a pedestrian (CET: CyberblockEffects.Hit(g, <ped EntityID hash>, 2.0, Vector4.new(6,0,0,0))): the pedestrian plays a normal death or ragdoll and is NOT dismembered, and the redscript/Cyberblock log has no errors. A non-lethal hit (frac 0.2) plays a melee hit reaction.
- [ ] Same Hit on a quest NPC or companion (Invulnerable/Immortal god mode or friendly, e.g. a fixer or Judy) and on an NPC sitting in a workspot (a bar stool, a bench): no ragdoll and no push. A plain hostile ganger in the open gets ragdolled.
- [ ] Hit with frac 0.2 and impulse (6,0,0) on a traffic car: the car takes damage and is pushed. The same call while V drives that car: the car is not pushed.
- [ ] Hit on a vehicle in decay state (burning or about to explode): no extra damage is applied.
- [ ] After a sword hit on a civilian, NCPD heat and the crime report still appear (needs SetAttackTime and attackType for PreventionSystem.CreateNewPreventionDamageRequest).
- [ ] Collision check 6 in docs/notes/collision-spike.md: add a box, CyberblockCollision.Reset(g), then Stream after moving 5 m. The box does not return, and the log has 'collision: reset, 1 boxes forgotten'.

### Layers 3-6 — track P
- [ ] README claims to confirm during the I.2 acceptance run (they come from the plan, not from anything verified here): Space while falling with an elytra opens it and a fall above 15 m/s for 0.5 s opens it on its own; double-tapping Left Shift within 300 ms with the Sandevistan perk fires it; F5 does not quick-save; the G/K rebinding limits.
- [ ] Run uv run python -m tools.fakehost --port 25710 --layer3 --layer4 --layer6 --hold 12 against a test client (track M or C oracle) and check that Minecraft's printed 'mobs:', 'flight:', 'proj:', 'scan:' and 'hack:' lines match shared/protocol.md and that the fall cam at x=-1, z=-1 starts a glide when the jump tap arrives. Nothing here could be checked without a client: no client was launched and no port was opened beyond the ephemeral test servers.

### Layers 3-6 — track D
- [ ] G-1 keyboard path: press F9 to activate, then read red4ext\logs\Cyberblock.log for the line 'raw keyboard input is registered (flags ...)' or 'raw keyboard input is not registered'. Then, while Active, press F5 and G and K: expect no quicksave notification, no walk toggle, no crafting menu. Press E, Q, T, 1, 2: nothing happens in Cyberpunk (1/2 do not pick interaction choices), and Minecraft receives inventory/drop/chat/slot.
- [ ] IAT hook: the log should show 'hooked GetRawInputData in the game's import table' at startup. While Active, left/right/middle click and scroll: Cyberpunk shows no weapon wheel or weapon switching, no Tag/scanner zoom, and V's camera still turns with the mouse. After F9 off, the mouse buttons work again. If the log says 'could not hook GetRawInputData', mouse buttons also reach Cyberpunk.
- [ ] Key-up rule: hold E, press F9 (Active), release E: Cyberpunk must have no stuck E (interaction does not repeat). Hold the left mouse button, press F9, release: no stuck fire or attack in Cyberpunk.
- [ ] Space and Left Shift while Active: V still jumps and sprints in Cyberpunk, and Minecraft receives key jump and key sprint (cp\scripts or fakehost output) with no auto-repeat.
- [ ] Binds: rebind the hack key in Minecraft's controls to Z: Z is swallowed and G reaches Cyberpunk again. Bind it to W or E: the log shows one 'binds: Minecraft's hack key cannot be used' warning and the previous key keeps working.
- [ ] Menus (needs S1 calling Cyberblock_SetMenu): open Cyberpunk's pause or inventory menu while Active: E/Q/T/1-9 and mouse clicks behave normally in the menu, and forwarding resumes when it closes.
- [ ] Look capture, from the CET console: Cyberblock_SetLookCapture(true), move the mouse, and fakehost/Minecraft log shows look {d:[dyaw,dpitch]} (right = positive yaw, down = positive pitch, 0.15 degrees per count, at most 120/s). Cyberblock_SetLookCapture(false) stops it.
- [ ] Stance: Cyberblock_SetStance(true) from CET, and fakehost shows cam.cr true on every cam message; false clears it.
- [ ] Game mode: Cyberblock_GameMode() == CName.new("creative") in a creative Minecraft world, n"" before the first status or after Minecraft closes.
- [ ] The hook with ReShade's own input hooks: open and close the ReShade overlay (Home) while Active and check the mouse still works and no crash on unload (F-key exit or alt-F4).
- [ ] Mob/flight/proj natives with a real Minecraft (track M): CET Cyberblock_MobCount() and Cyberblock_FlightSeq() change as mobs appear and when gliding starts and ends.

### Layers 3-6 — track M
- [ ] With real Cyberpunk: start a glide (elytra, jump while V falls), open the pause menu mid-air for more than 2 s, then resume. Expect: Steve is not left gliding; the elytra durability tooltip does not drop while paused or afterwards; V/Steve hang at the stop point and follow V again; a new jump-while-falling starts a fresh glide.

### Layers 3-6 — track C
- [ ] With real Cyberpunk: let Cyberpunk connect while Minecraft is at the title screen, then open a world where Steve owns double_jump and dodge. Expected, without buying anything: the Cyberpunk log or CET shows a 'perks' message with 20 entries carrying those ranks, V can double jump, and dodge works.
- [ ] In the same session, Save and Quit to the title in Minecraft. Expected: Cyberpunk receives a 'perks' message with all ranks at 0, double jump no longer works, and silent_steps Visibility is back to normal. Then open a different world with different ranks: Cyberpunk gets that world's ranks.
- [ ] After merging with M (whose HostLink calls Bridge.dispatch): start Cyberpunk first, so its link waits, then start Minecraft. Expected: 'binds' arrives right after hello. If it is ever missing, see the entrypoint-order note.

### Layers 3-6 — track S1
- [ ] G-1 keyboard path: track D logs whether GetRegisteredRawInputDevices lists usage page 1 / usage 6. With Cyberblock Active on foot, press F5 and G: there should be no 'Quicksave' notification and no walk toggle. The DLL swallow comes first, and the Disable.reds wraps on HandleQuickSave and ProcessToggleWalkInput are the second line.
- [ ] G-2 stat modifiers are not saved: equip Reinforced Tendons and go Active on foot. Double jump should fail, and CET Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.HasDoubleJump) should return 0. Save from the pause menu while Active (PlayerState removes the modifiers before the save), turn Cyberblock Off and reload: the same CET line should return 1.
- [ ] G-9 lethal paths under Cyberblock god mode. Fall test: Active, jump off a megabuilding roof (DeathLand). V should survive, and Steve should take fall damage with frac about 4 (Cyberblock.log line 'dmg fall'). Kill test: in a throwaway save, apply BaseStatusEffect.ForceKill to V while Active. Does V die?
- [ ] G-10 DoT wrap: while Active, let an NPC with an incendiary weapon (or another Burning applicator) set V on fire. Expect several 'dmg fire' lines in Cyberblock.log from one ignition, with V's Health unchanged.
- [ ] G-11 camo perks: with a camo slide or grapple perk owned and Cyberblock Active (CanUseOpticalCamo and CanUseTerrainCamo x0 applied), slide or grapple. V should not cloak.
- [ ] G-12 superhero landing: with a ground-slam source owned and Cyberblock Active (HasSuperheroFall x0 applied), crouch in mid-air. There should be no slam.
- [ ] G-13 radial wheel and vehicle: Active on foot, holding Alt opens no wheel and the world does not freeze. Get into a car (Active to Vehicle; PlayerState clears NoRadialMenus and its VehicleBlockRadioInput tag): the vehicle radio wheel should work.
- [ ] G-14 restrictions not left in saves: save while Active, then load. CET StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('GameplayRestriction.NoScanning')) should be false, as for NoHealing, NoRadialMenus, NoQuickMelee, NoZooming and BodyCarryingForceDrop. Optionally the audit's raw check: apply NoScanning with isSavable=false, save, reload, and see whether it is gone.
- [ ] DISABLE rows while Active on foot: Tab does not scan (no world slowdown); X does not heal; there is no double jump, charge jump, hover, dodge or air dash; dead or downed NPC bodies cannot be picked up; zoom does nothing. After Steve buys the dodge perk (K): dash and air dash work. After Steve buys double_jump: double jump works (S3 grants +1, S1 no longer zeroes it).
- [ ] Menu flag: Active, open the pause menu and press W/A/S/D and E. Steve must not move or act in Minecraft; after the menu closes, input reaches Minecraft again.
- [ ] Stance: Active, crouch V (C), then slide. Fake host or Minecraft should show cam.cr=true (Steve in the crouch pose); stand up and cr returns to false.
- [ ] Probes through our boxes: Active, build a 3-block Minecraft wall 2 m from V on a street. In Minecraft, no barrier should appear in the air cell in front of the wall, the wall block can be targeted and outlined (the aim is no longer stopped at the box face), and the street under a placed block stays ground.
- [ ] Flight probes: glide from a roof (layer 4). Minecraft's barrier ground should appear well ahead in the glide direction, up to 64 m, and the 2 m grid should fill in to 1 m once within 24 m.
- [ ] Creative: Minecraft in creative, F9 while driving. Crash into a wall at speed and let NPCs shoot the car: V cannot die. Cyberblock.log should show 'creative: V invulnerable in the vehicle', and 'creative: V vulnerable again' on getting out or switching to survival.
- [ ] Fall suppression: land a glide or an ender pearl teleport from a height. Within 2 s there should be no 'dmg fall' sent; the log should show 'not reported: flight or teleport landing'.
- [ ] block_reset: reconnect Minecraft while Off. Cyberblock.log should show 'events: block reset' and 'collision: reset'; after F9 the boxes come back from Minecraft's resync.
- [ ] Inactive requests: with Cyberblock Off, hold G in Minecraft and use a quickhack item. Minecraft should get scaninfo ok:false and hackresult ok:false with reason 'Cyberblock is not active'.

### Layers 3-6 — track S2
- [ ] Gate check, after the Npcs.reds patch is merged: Cyberblock Active, Minecraft running, spawn a zombie from a spawn egg next to Steve. Expected: Cyberblock.log has 'puppets: spawned for ... zombie', the 'npcs: N sent' count does not grow by one, and Minecraft shows no human NPC proxy at the zombie's position (F3+B shows only the zombie's hitbox there).
- [ ] Despawn window: kill the zombie in Minecraft. Expected: 'puppets: despawned mob <id>' in the log and no human proxy appearing at the spot, not even briefly. This confirms that the Codeware tag (IsTagged) outlives the entity's attachment, or detaches in the same frame. If a proxy flashes, the Despawn spawner event comes before Entity/Detach.

### Layers 3-6 — track S3
- [ ] G-3 Quickhack: in CET, npc = Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false), then StatusEffectHelper.ApplyStatusEffect(npc, TweakDBID.new('BaseStatusEffect.OverloadLevel1'), Game.GetPlayer():GetEntityID()). Expect electrocution VFX and damage. Then use a Redstone Charge from Minecraft on a ganger. Expect an upload delay, the same result plus NCPD heat, and Cyberblock.log 'hack short_circuit on <id>: ok'. Repeat on a crowd NPC and on a mech.
- [ ] G-4 Device hack: Redstone Lever on a vending machine -> the screen glitches and cans drop (log 'hack device on <id>: ok'). Redstone Lever on a camera -> its detection cone stops tracking V, and the Minecraft view must NOT switch to the CCTV.
- [ ] G-5 Traffic: Redstone Lever on a traffic light at an intersection. Do the intersection's lights change, and do cars stop and go?
- [ ] G-6 Slow-mo: buy Sandevistan and double-tap sprint. NPCs and cars run at x0.2 while V does not, for 5 s. Log: 'perks: sandevistan slow-mo for 5 s', then 'perks: slow-mo ended'. Note any screen effect. Pressing F9 during the slow-mo must restore normal time at once.
- [ ] G-7 Camo: in combat, drink an invisibility potion -> hostiles lose V. Compare with only Game.GetPlayer():SetInvisible(true) in CET. After 10 s, StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.OpticalCamoPlayerBuffRare')) should still be true (Buffs re-applies it once a second if camo decay removes it).
- [ ] G-8 Visibility: with Silent Steps bought, Game.GetStatsSystem():GetStatValue(Game.GetPlayer():GetEntityID(), gamedataStatType.Visibility) is half its perk-off value. Crouch-walk towards an idle guard and compare how long the detection meter takes to fill with and without the perk.
- [ ] Hack short_circuit (Redstone Charge) on a ganger: after the upload, electrocution VFX and damage; hackresult ok:true.
- [ ] Hack overheat (Blaze Chip): the ganger burns (overheat VFX) and its health ticks down; Steve takes no damage.
- [ ] Hack reboot_optics (Ink Sac Daemon): blinded VFX, and the ganger loses track of V for the effect's duration.
- [ ] Hack cripple_movement (Cobweb Daemon): the ganger stops moving.
- [ ] Hack contagion (Spider Eye Virus): poison VFX and damage over time on the target. Note whether it spreads (that depends on V's spread perks).
- [ ] Hack synapse_burnout (Wither Skull Daemon): brain-bolt VFX and heavy damage.
- [ ] Hack ping (Compass Ping): the log shows ok, and in CET StatusEffectSystem.ObjectHasStatusEffect(npc, TweakDBID.new('BaseStatusEffect.Ping')) is true. Nothing is visible because the HUD is hidden.
- [ ] Hack memory_wipe (Bottle of Forgetting) on a ganger fighting V: it drops combat and returns to idle.
- [ ] Hack cyberpsychosis (Creeper Brain) on a ganger in a group: it attacks its squad, then kills itself.
- [ ] Hack device (Redstone Lever): on a vending machine, a camera and a traffic light as in G-4/G-5; on a TV or radio it glitches or distracts; on a powered-off device, hackresult ok:false with reason 'not hackable'. The camera must never take over the view.
- [ ] Chain Hack perk: with the perk, a ganger within 8 m of the target gets the same hack (two VFX). A companion, or a quest NPC in a scene, gives 'not hackable'; a dead target gives 'target gone'.
- [ ] Buff invisibility: see G-7. When the potion ends, V is visible to NPCs again (SetInvisible(false)) and the camo status effect is gone.
- [ ] Buff speed: read GetStatValue(V, gamedataStatType.MaxSpeed) before and during Speed II -> x1.4, and V crosses a measured street visibly faster. The value is back to normal at potion end and on F9 off.
- [ ] Buff slowness: Slowness I -> MaxSpeed x0.85 and V is slower. Slowness X stays at x0.3.
- [ ] Buff jump_boost: with Jump Boost II, V clears an obstacle about twice the normal jump height. Normal again afterwards.
- [ ] Buff nausea: eating a pufferfish puts the drunk wobble on V (StatusEffectSystem.ObjectHasStatusEffect(Game.GetPlayer(), TweakDBID.new('BaseStatusEffect.Drunk')) is true). It is removed when Minecraft removes the effect. Check whether the wobble is visible behind the Minecraft frame.
- [ ] Buff blindness: splash blindness gives the blind glitch screen on V (AIQuickHackStatusEffect.HackBlind). It is gone when the Minecraft effect ends, even if that is later than its own 8 s.
- [ ] Buff darkness: same as blindness (/effect give @p darkness).
- [ ] Unmapped effects (poison, wither): the log shows 'buffs: effect poison has no Cyberpunk mapping, ignored' once. V gets no effect, and Steve takes only Minecraft's own poison damage.
- [ ] Perk double_jump: with the perk bought and Cyberblock Active, V double-jumps, and GetStatValue(V, HasDoubleJump) >= 1. After F9 off, V's own value is back.
- [ ] Scan: hack key held on a ganger -> scaninfo ok:true with name, faction and threat; on a vending machine -> device:true; on the sky -> ok:false.
- [ ] Kills: killing a hostile ganger with a sword logs 'killed human <id> (hostile)' once; destroying a car with TNT logs 'killed vehicle <id>'; a kill V makes with Cyberblock Off logs nothing.
