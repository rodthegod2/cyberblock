// Minecraft -> Cyberpunk events, queued by Cyberblock.dll already in Cyberpunk space and handed to the S2/S3 classes
// (layer 2 plan section 1.4; plan 2026-10-03 sections 1.3 and 1.4). CyberblockSystem calls Tick every 100 ms, so up
// to 640 events a second are applied, plus up to 5120 block changes a second: those are only registry updates, and
// the DLL hands them out after the other kinds, so a backlog (a resync that arrived while the game was loading or
// paused, when this tick does not run) drains in seconds without delaying hits or hack answers.
//
// Event -> script API (plan 2026-10-03 section 1.3 event table):
//   block_add / block_remove -> CyberblockCollision.Add / Remove      block_reset -> CyberblockCollision.Reset
//   explosion -> CyberblockEffects.Explode     hit -> CyberblockEffects.HitFrom (mob id from Cyberblock_EventId)
//   ignite -> CyberblockEffects.Ignite         teleport -> CyberblockEffects.Teleport
//   lead / lead_release / pull / knock -> CyberblockTether.Lead / Release / Pull / Knockback
//   effect -> CyberblockBuffs.Effect           hack -> CyberblockHacks.Hack       scan -> CyberblockScan.Scan
//   perk -> CyberblockPerks.Set                perkfx -> CyberblockPerks.Fx (hands it to CyberblockCyberware.Fx)
//   cyber -> CyberblockCyberware.Set           cyberfx -> CyberblockCyberware.Fx  (plan 2026-10-03-retest2 1.3)
//   prefs -> CyberblockLocomotion.SetStepUp(flag = prefs.stepup), CyberblockView.SetVehicleDistance(amount = vdist)  (megaplan 2026-10-04 Q-D1/Q-S2; recorded whatever the state)
//   session -> CyberblockSession.OnEvent, whatever the state (megaplan 2026-10-04 task 1-H1, Session.reds)
//   vteleport -> CyberblockTeleport.Start, park -> CyberblockPark.OnEvent: whatever the state, each refuses itself while
//     not live, so every vteleport gets its Cyberblock_TeleportDone (megaplan 2026-10-04 task 3-S1, Teleport.reds, Park.reds)
//   devact -> CyberblockDeviceService.OnEvent, whatever the state: it answers every request with Cyberblock_DeviceResult,
//     refusing while not live (megaplan 2026-10-04 task 4-S1, Devices.reds)
//   rhack -> CyberblockRemoteHacks.OnEvent, daemon -> CyberblockDaemons.OnEvent, whatever the state: each answers every
//     request with Cyberblock_HackResult, refusing while not live (megaplan 2026-10-04 task 6-S1, RemoteHacks.reds, Daemons.reds)
//   hub -> CyberblockHub.OnEvent (EventFlag = in), repair -> CyberblockServices.OnEvent (EventId = request id):
//     whatever the state, each refusing itself while not live (megaplan 2026-10-04 task 7-S1; Hub.reds, Services.reds).
//     `repair` is not wired: no wire message or DLL event kind exists yet (the Phase 7 descope, Services.reds).
//     A vendor's buy and sell are not events: they are `uiact buy|sell` (Menus.reds -> Vendors.reds, section 1.8).
//   spawn -> CyberblockSpawns.OnEvent (answers every request with Cyberblock_SpawnResult, refusing while not live),
//     heat -> CyberblockHeat.OnHeat, attitude -> CyberblockHeat.OnAttitude (both remembered whatever the state and
//     applied while live), glitch -> CyberblockGlitch.OnEvent (only while live) (megaplan 2026-10-04 task 8-S1; Spawns.reds,
//     Heat.reds, Glitch.reds; the event fields are listed in Natives.reds).
//
// While a save is written, CyberblockSystem does not call Tick at all: the events wait in the DLL's queue and are applied
// after the save. While not live otherwise (Off, Lost, no player) Night City is left alone:
//   - block changes still go into the collision registry (none of them is ever dropped), so the boxes match the
//     Minecraft world once live again (e.g. blocks a TNT chain removed while a lag spike made us Lost). Only the
//     registry changes: boxes are spawned by CyberblockCollision.Stream while live, and every box was already despawned
//     on leaving live (ClearWorld), so nothing appears in Night City;
//   - perk ranks and installed cyberware are recorded (Minecraft sends them after each hello, whatever the state);
//   - hack and scan requests are answered at once with ok:false, "Cyberblock is not active" (Minecraft waits for an
//     answer to every request);
//   - everything else refers to a moment that has passed and is dropped (counted in the log).
//
// Hits, leads and pulls are logged with target and amount, and successful scans with Scan's note (class, id, how it
// was found), at most one line per kind per second (Note).
//
// 2.31 APIs: none beyond the natives (Natives.reds), redscript array intrinsics and the simulation clock
// (CyberblockEffectsService.Now, TimeSystem.GetSimTime).

public class CyberblockEvents {
  // Rate limit of Note, per event kind (parallel arrays): when the last line was written, and how many were not since.
  private let noteKinds: array<CName>;
  private let noteAt: array<Float>;
  private let noteSkipped: array<Int32>;

  // live: state is Active or Vehicle, with a player (never called while a save is written).
  public func Tick(game: GameInstance, live: Bool) -> Void {
    let dropped = 0;
    let n = 0;      // events of the other kinds
    let blocks = 0; // block changes
    while n < 64 && blocks < 512 {
      let kind = Cyberblock_NextEvent();
      if Equals(kind, n"") {
        break;
      }
      if Equals(kind, n"block_add") {
        blocks += 1;
        CyberblockCollision.Add(game, Cyberblock_EventPos());
      } else if Equals(kind, n"block_remove") {
        blocks += 1;
        CyberblockCollision.Remove(game, Cyberblock_EventPos());
      } else if Equals(kind, n"block_reset") {
        // Minecraft re-sends all its blocks after this (blocks resync after each host hello).
        blocks += 1;
        CyberblockCollision.Reset(game);
      } else {
        n += 1;
        if Equals(kind, n"perk") {
          CyberblockPerks.Set(game, Cyberblock_EventName(), RoundF(Cyberblock_EventAmount()));
        } else if Equals(kind, n"cyber") {
          CyberblockCyberware.Set(game, Cyberblock_EventName(), RoundF(Cyberblock_EventAmount()) == 1);
        } else if Equals(kind, n"prefs") {
          CyberblockLocomotion.SetStepUp(Cyberblock_EventFlag());
          CyberblockView.SetVehicleDistance(Cyberblock_EventAmount()); // prefs.vdist (View.reds)
        } else if Equals(kind, n"session") {
          let system = CyberblockSystem.Get(game);
          if IsDefined(system) {
            system.Session().OnEvent(game);
          }
        } else if Equals(kind, n"vteleport") {
          let system = CyberblockSystem.Get(game);
          if IsDefined(system) {
            system.Teleport().Start(game, live);
          }
        } else if Equals(kind, n"park") {
          let system = CyberblockSystem.Get(game);
          if IsDefined(system) {
            system.Park().OnEvent(game, live);
          }
        } else if Equals(kind, n"devact") {
          CyberblockDeviceService.OnEvent(game); // refuses itself while not live (Devices.reds)
        } else if Equals(kind, n"rhack") {
          CyberblockRemoteHacks.OnEvent(game, live); // refuses itself while not live (RemoteHacks.reds)
        } else if Equals(kind, n"daemon") {
          CyberblockDaemons.OnEvent(game, live); // refuses itself while not live (Daemons.reds)
        } else if Equals(kind, n"hub") {
          CyberblockHub.OnEvent(game, live); // megaplan 7-S1: parks V while Steve is in the hub (Hub.reds)
        } else if Equals(kind, n"repair") {
          // megaplan 7-S1: refuses itself while not live. Not wired yet: the DLL has no repair kind (Services.reds)
          CyberblockServices.OnEvent(game, live);
        } else if Equals(kind, n"spawn") {
          CyberblockSpawns.OnEvent(game, live); // megaplan 8-S1: answers every request, refusing while not live (Spawns.reds)
        } else if Equals(kind, n"heat") {
          CyberblockHeat.OnHeat(game, live); // megaplan 8-S1: kept, applied while live (Heat.reds)
        } else if Equals(kind, n"attitude") {
          CyberblockHeat.OnAttitude(game, live); // megaplan 8-S1: kept, applied while live (Heat.reds)
        } else if Equals(kind, n"glitch") {
          CyberblockGlitch.OnEvent(game, live); // megaplan 8-S1: only while live (Glitch.reds)
        } else if !live {
          if !CyberblockEvents.Refuse(kind) {
            dropped += 1;
          }
        } else {
          this.Apply(game, kind);
        }
      }
    }
    if dropped > 0 {
      Cyberblock_Log(s"events: \(dropped) events dropped while not active");
    }
  }

  // The events that act on Night City, only while live. Hits, leads and pulls are noted in the log (target and
  // amount), so a proxy that Minecraft hits, leashes or hooks can be followed to its NPC or vehicle.
  private func Apply(game: GameInstance, kind: CName) -> Void {
    if Equals(kind, n"explosion") {
      CyberblockEffects.Explode(game, Cyberblock_EventPos(), Cyberblock_EventAmount(), Cyberblock_EventFlag());
    } else if Equals(kind, n"hit") {
      this.Note(game, kind, s"target \(Cyberblock_EventTarget()) amount \(Cyberblock_EventAmount()) by mob \(Cyberblock_EventId())");
      CyberblockEffects.HitFrom(game, Cyberblock_EventTarget(), Cyberblock_EventAmount(), Cyberblock_EventVector(), Cyberblock_EventId());
    } else if Equals(kind, n"ignite") {
      CyberblockEffects.Ignite(game, Cyberblock_EventTarget(), Cyberblock_EventAmount());
    } else if Equals(kind, n"teleport") {
      CyberblockEffects.Teleport(game, Cyberblock_EventPos(), Cyberblock_EventAmount());
    } else if Equals(kind, n"lead") {
      this.Note(game, kind, s"target \(Cyberblock_EventTarget()) amount \(Cyberblock_EventAmount())");
      CyberblockTether.Lead(game, Cyberblock_EventTarget(), Cyberblock_EventPos(), Cyberblock_EventFlag(), Cyberblock_EventAmount());
    } else if Equals(kind, n"lead_release") {
      CyberblockTether.Release(game, Cyberblock_EventTarget());
    } else if Equals(kind, n"pull") {
      this.Note(game, kind, s"target \(Cyberblock_EventTarget()) amount \(Cyberblock_EventAmount())");
      CyberblockTether.Pull(game, Cyberblock_EventTarget(), Cyberblock_EventPos(), Cyberblock_EventAmount());
    } else if Equals(kind, n"knock") {
      CyberblockTether.Knockback(game, Cyberblock_EventPos(), Cyberblock_EventAmount(), Cyberblock_EventExtra());
    } else if Equals(kind, n"effect") {
      CyberblockBuffs.Effect(game, Cyberblock_EventName(), Cyberblock_EventAmount(), RoundF(Cyberblock_EventExtra()));
    } else if Equals(kind, n"hack") {
      CyberblockHacks.Hack(game, Cyberblock_EventId(), Cyberblock_EventTarget(), Cyberblock_EventName(), Cyberblock_EventFlag());
    } else if Equals(kind, n"scan") {
      let note = CyberblockScan.Scan(game, Cyberblock_EventId(), Cyberblock_EventTarget());
      if StrLen(note) > 0 {
        this.Note(game, kind, note); // a failure was logged by Scan itself
      }
    } else if Equals(kind, n"perkfx") {
      CyberblockPerks.Fx(game, Cyberblock_EventName(), Cyberblock_EventAmount());
    } else if Equals(kind, n"cyberfx") {
      CyberblockCyberware.Fx(game, Cyberblock_EventName(), Cyberblock_EventAmount());
    } else {
      Cyberblock_Log(s"events: unknown kind \(NameToString(kind)) ignored");
    }
  }

  // 'events: <kind> <detail>', at most one line per kind per second; the line after a quiet spell counts the events
  // of that kind it left out, so a fight cannot flood the log.
  private func Note(game: GameInstance, kind: CName, detail: String) -> Void {
    let now = CyberblockEffectsService.Now(game);
    let i = ArrayFindFirst(this.noteKinds, kind);
    if i < 0 {
      ArrayPush(this.noteKinds, kind);
      ArrayPush(this.noteAt, 0.0);
      ArrayPush(this.noteSkipped, 0);
      i = ArraySize(this.noteKinds) - 1;
    } else {
      // Simulation time restarts with a session: an older stamp ahead of now does not hold the line back.
      let age = now - this.noteAt[i];
      if age >= 0.0 && age < 1.0 {
        this.noteSkipped[i] += 1;
        return;
      }
    }
    let more = this.noteSkipped[i] > 0 ? s" (\(this.noteSkipped[i]) more \(NameToString(kind)) events not logged before it)" : "";
    Cyberblock_Log(s"events: \(NameToString(kind)) \(detail)\(more)");
    this.noteAt[i] = now;
    this.noteSkipped[i] = 0;
  }

  // Answers a hack or scan request that arrives while not live; false for every other kind.
  private static func Refuse(kind: CName) -> Bool {
    let reason = "Cyberblock is not active";
    if Equals(kind, n"hack") {
      Cyberblock_HackResult(Cyberblock_EventId(), false, reason, 0.0, n"");
      return true;
    }
    if Equals(kind, n"scan") {
      let none: Vector4;
      Cyberblock_ScanResult(Cyberblock_EventId(), false, 0ul, "", "", n"none", 0.0, false, none, none);
      return true;
    }
    return false;
  }
}
