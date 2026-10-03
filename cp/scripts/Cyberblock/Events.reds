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
//   perk -> CyberblockPerks.Set                perkfx -> CyberblockPerks.Fx
//
// While not live (Off, Lost, saving, no player) Night City is left alone:
//   - block changes still go into the collision registry (none of them is ever dropped), so the boxes match the
//     Minecraft world once live again (e.g. blocks a TNT chain removed while a lag spike made us Lost). Only the
//     registry changes: boxes are spawned by CyberblockCollision.Stream while live, and every box was already despawned
//     on leaving live (ClearWorld), so nothing appears in Night City;
//   - perk ranks are recorded (Minecraft sends them after each hello, whatever the state);
//   - hack and scan requests are answered at once with ok:false, "Cyberblock is not active" (Minecraft waits for an
//     answer to every request);
//   - everything else refers to a moment that has passed and is dropped (counted in the log).
//
// 2.31 APIs: none beyond the natives (Natives.reds) and redscript array intrinsics.

public class CyberblockEvents {
  // live: state is Active or Vehicle, not saving, with a player.
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
        } else if !live {
          if !CyberblockEvents.Refuse(kind) {
            dropped += 1;
          }
        } else {
          CyberblockEvents.Apply(game, kind);
        }
      }
    }
    if dropped > 0 {
      Cyberblock_Log(s"events: \(dropped) events dropped while not active");
    }
  }

  // The events that act on Night City, only while live.
  private static func Apply(game: GameInstance, kind: CName) -> Void {
    if Equals(kind, n"explosion") {
      CyberblockEffects.Explode(game, Cyberblock_EventPos(), Cyberblock_EventAmount(), Cyberblock_EventFlag());
    } else if Equals(kind, n"hit") {
      CyberblockEffects.HitFrom(game, Cyberblock_EventTarget(), Cyberblock_EventAmount(), Cyberblock_EventVector(), Cyberblock_EventId());
    } else if Equals(kind, n"ignite") {
      CyberblockEffects.Ignite(game, Cyberblock_EventTarget(), Cyberblock_EventAmount());
    } else if Equals(kind, n"teleport") {
      CyberblockEffects.Teleport(game, Cyberblock_EventPos(), Cyberblock_EventAmount());
    } else if Equals(kind, n"lead") {
      CyberblockTether.Lead(game, Cyberblock_EventTarget(), Cyberblock_EventPos(), Cyberblock_EventFlag(), Cyberblock_EventAmount());
    } else if Equals(kind, n"lead_release") {
      CyberblockTether.Release(game, Cyberblock_EventTarget());
    } else if Equals(kind, n"pull") {
      CyberblockTether.Pull(game, Cyberblock_EventTarget(), Cyberblock_EventPos(), Cyberblock_EventAmount());
    } else if Equals(kind, n"knock") {
      CyberblockTether.Knockback(game, Cyberblock_EventPos(), Cyberblock_EventAmount(), Cyberblock_EventExtra());
    } else if Equals(kind, n"effect") {
      CyberblockBuffs.Effect(game, Cyberblock_EventName(), Cyberblock_EventAmount(), RoundF(Cyberblock_EventExtra()));
    } else if Equals(kind, n"hack") {
      CyberblockHacks.Hack(game, Cyberblock_EventId(), Cyberblock_EventTarget(), Cyberblock_EventName(), Cyberblock_EventFlag());
    } else if Equals(kind, n"scan") {
      CyberblockScan.Scan(game, Cyberblock_EventId(), Cyberblock_EventTarget());
    } else if Equals(kind, n"perkfx") {
      CyberblockPerks.Fx(game, Cyberblock_EventName(), Cyberblock_EventAmount());
    } else {
      Cyberblock_Log(s"events: unknown kind \(NameToString(kind)) ignored");
    }
  }

  // Answers a hack or scan request that arrives while not live; false for every other kind.
  private static func Refuse(kind: CName) -> Bool {
    let reason = "Cyberblock is not active";
    if Equals(kind, n"hack") {
      Cyberblock_HackResult(Cyberblock_EventId(), false, reason);
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
