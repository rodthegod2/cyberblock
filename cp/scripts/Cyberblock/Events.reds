// Minecraft -> Cyberpunk events (block, explosion, hit, ignite), queued by Cyberblock.dll already in Cyberpunk space
// and handed to the S2 classes CyberblockEffects / CyberblockCollision (plan section 1.4). CyberblockSystem calls
// Tick every 100 ms, so up to 640 events a second are applied.
//
// While Off or Lost Night City is left alone: explosions, hits and ignitions that arrive then refer to a moment that
// has passed and are dropped, but block changes are kept (in order) and replayed on the next live tick, so the
// collision boxes still match the Minecraft world (e.g. blocks a TNT chain removed while a lag spike made us Lost).
//
// 2.31 APIs: none beyond the natives (Natives.reds) and redscript array intrinsics.

public class CyberblockEvents {
  // Block changes waiting for the next live tick: AABB min corner and add (true) / remove (false).
  private let pendingPos: array<Vector4>;
  private let pendingAdd: array<Bool>;

  // live: state is Active or Vehicle.
  public func Tick(game: GameInstance, live: Bool) -> Void {
    if live {
      this.Replay(game);
    }
    let dropped = 0;
    let n = 0;
    while n < 64 {
      let kind = Cyberblock_NextEvent();
      if Equals(kind, n"") {
        break;
      }
      n += 1;
      if Equals(kind, n"block_add") || Equals(kind, n"block_remove") {
        this.Block(game, live, Cyberblock_EventPos(), Equals(kind, n"block_add"));
      } else if !live {
        dropped += 1;
      } else if Equals(kind, n"explosion") {
        CyberblockEffects.Explode(game, Cyberblock_EventPos(), Cyberblock_EventAmount(), Cyberblock_EventFlag());
      } else if Equals(kind, n"hit") {
        CyberblockEffects.Hit(game, Cyberblock_EventTarget(), Cyberblock_EventAmount(), Cyberblock_EventVector());
      } else if Equals(kind, n"ignite") {
        CyberblockEffects.Ignite(game, Cyberblock_EventTarget(), Cyberblock_EventAmount());
      } else {
        Cyberblock_Log(s"events: unknown kind \(NameToString(kind)) ignored");
      }
    }
    if dropped > 0 {
      Cyberblock_Log(s"events: \(dropped) explosion/hit/ignite events dropped while not active");
    }
  }

  private func Block(game: GameInstance, live: Bool, aabbMin: Vector4, add: Bool) -> Void {
    if live {
      CyberblockEvents.Apply(game, aabbMin, add);
      return;
    }
    // Bounded like the DLL queue: past 4096 changes the oldest are forgotten.
    if ArraySize(this.pendingPos) >= 4096 {
      ArrayErase(this.pendingPos, 0);
      ArrayErase(this.pendingAdd, 0);
    }
    ArrayPush(this.pendingPos, aabbMin);
    ArrayPush(this.pendingAdd, add);
  }

  private func Replay(game: GameInstance) -> Void {
    let count = ArraySize(this.pendingPos);
    if count == 0 {
      return;
    }
    let i = 0;
    while i < count {
      CyberblockEvents.Apply(game, this.pendingPos[i], this.pendingAdd[i]);
      i += 1;
    }
    ArrayClear(this.pendingPos);
    ArrayClear(this.pendingAdd);
    Cyberblock_Log(s"events: \(count) block changes from while not active replayed");
  }

  private static func Apply(game: GameInstance, aabbMin: Vector4, add: Bool) -> Void {
    if add {
      CyberblockCollision.Add(game, aabbMin);
    } else {
      CyberblockCollision.Remove(game, aabbMin);
    }
  }
}
