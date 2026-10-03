// Upload-time extras that make each quickhack visible on any NPC (re-test 3 plan 2026-10-03-retest3-polish section 1.6,
// task S2; F1 "every quickhack feels the same"). The vanilla quickhack (Hacks.reds) still does the real work; most
// targets are crowd pedestrians, on whom vanilla's squad- and AI-driven effects show nothing, so when the upload ends
// (DelaySystem.DelayCallback after the action's upload time, the moment Minecraft plays its impact) this adds:
//   overheat         crowd: CyberblockEffects.Ignite 5 s (BaseStatusEffect.Burning);
//   reboot_optics    crowd: BaseStatusEffect.Blind, 5 s left;
//   cripple_movement crowd: BaseStatusEffect.Stun, 4 s left (the root);
//   memory_wipe      crowd: BaseStatusEffect.Stun, 2 s left ("dazed");
//   cyberpsychosis   crowd: frenzy for 6 s: every 1.5 s the hacked NPC strikes the nearest NPC within 4 m
//                    (StrikeBy frac 0.15, attacker = the hacked puppet), each victim reported `frenzy`;
//   short_circuit    crowd: the status_electrocuted effect event (vanilla's Overload is visible on its own);
//   synapse_burnout  always: a heavy StrikeBy from V (frac 0.35, 3.5 m/s: below the Stagger tier, no ragdoll);
//   ping             always, first target only: hostile NPCs within 30 m of the target (at most 16, nearest first)
//                    are revealed for 10 s, each reported `ping`;
//   contagion        its spread is uploaded with the hack itself (Hacks.reds); device hacks: none.
// Every extra is logged as 'hackfx: <hack> <kind> <what>'. Extras spare whoever the pushes spare
// (CyberblockEffects.IsProtected: V, god modes, V's friends, workspots) and need the target still active
// (ScriptedPuppet.IsActive); a frenzy victim must pass the same tests.
//
// 2.31 sources (decompiled 2.31 scripts; TweakDB records checked present in tweakdb.bin and tweakdb_ep1.bin;
// compile-checked):
//   DelayCallback (delaySystem.swift: native class, script override of Call) queued with DelaySystem.DelayCallback(cb,
//     seconds), as DelayedEhxautionSoundClue (playerListeners.swift:29, 103) and PreventionSystem's callbacks do.
//   StatusEffectSystem.ApplyStatusEffect(target id, record, TDBID.None(), instigator id) +
//     SetStatusEffectRemainingDuration (as CyberblockEffects.Ignite); records BaseStatusEffect.Blind,
//     BaseStatusEffect.Stun.
//   GameObject.SendForceRevealObjectEvent(self, reveal, reason, instigator EntityID, lifetime) (gameObject.swift:1720).
//   GameObjectEffectHelper.StartEffectEvent(object, effect name) (native); effect name status_electrocuted from audit B.
//   Hostile to V: IsHostileTowardsPlayer, as Npcs.reds; NPCs from the registry CyberblockTetherService.Known.

public abstract class CyberblockHackFx {
  // Queues the extras of one hacked person for the end of its upload (at once when the upload is instant). primary:
  // the request's own target (only it pings; chained targets get the other extras).
  public static func Queue(game: GameInstance, id: Int32, hack: CName, target: ref<ScriptedPuppet>, kind: CName, upload: Float, primary: Bool) -> Void {
    if !IsDefined(target) || !CyberblockHackFx.HasExtra(hack, kind) {
      return;
    }
    let callback = new CyberblockHackFxCallback();
    callback.game = game;
    callback.id = id;
    callback.hack = hack;
    callback.kind = kind;
    callback.target = CyberblockIds.Hash(target);
    callback.primary = primary;
    if upload <= 0.0 {
      callback.Call();
    } else {
      GameInstance.GetDelaySystem(game).DelayCallback(callback, upload);
    }
  }

  private static func HasExtra(hack: CName, kind: CName) -> Bool {
    if Equals(hack, n"synapse_burnout") || Equals(hack, n"ping") {
      return true;
    }
    return Equals(kind, n"crowd") && (Equals(hack, n"short_circuit") || Equals(hack, n"overheat") || Equals(hack, n"reboot_optics")
      || Equals(hack, n"cripple_movement") || Equals(hack, n"memory_wipe") || Equals(hack, n"cyberpsychosis"));
  }

  // The upload ended: the section 1.6 extra of hack on target.
  public static func Run(game: GameInstance, id: Int32, hack: CName, kind: CName, target: Uint64, primary: Bool) -> Void {
    let player = GetPlayer(game);
    let puppet = CyberblockEffects.Find(game, target) as ScriptedPuppet;
    let name = s"hackfx: \(NameToString(hack)) \(NameToString(kind))";
    if !IsDefined(player) || !IsDefined(puppet) || !ScriptedPuppet.IsActive(puppet) {
      Cyberblock_Log(s"\(name) target \(target) gone");
      return;
    }
    if Equals(hack, n"ping") {
      if primary {
        CyberblockHackFx.Ping(game, id, puppet, player);
      }
      return;
    }
    let spared = CyberblockEffects.IsProtected(game, puppet, player);
    if NotEquals(spared, n"") {
      Cyberblock_Log(s"\(name) spared \(target) (\(NameToString(spared)))");
      return;
    }
    if Equals(hack, n"synapse_burnout") {
      let push = CyberblockTetherService.Toward(player.GetWorldPosition(), puppet.GetWorldPosition()) * 3.5;
      CyberblockEffects.StrikeBy(game, puppet, 0.35, new Vector4(push.X, push.Y, 0.0, 0.0), player);
      Cyberblock_Log(s"\(name) heavy hit on \(target)");
    } else if Equals(hack, n"overheat") {
      CyberblockEffects.Ignite(game, target, 5.0);
      Cyberblock_Log(s"\(name) ignite \(target) 5 s");
    } else if Equals(hack, n"reboot_optics") {
      CyberblockHackFx.Status(game, puppet, player, t"BaseStatusEffect.Blind", 5.0);
      Cyberblock_Log(s"\(name) blind \(target) 5 s");
    } else if Equals(hack, n"cripple_movement") {
      CyberblockHackFx.Status(game, puppet, player, t"BaseStatusEffect.Stun", 4.0);
      Cyberblock_Log(s"\(name) stun \(target) 4 s");
    } else if Equals(hack, n"memory_wipe") {
      CyberblockHackFx.Status(game, puppet, player, t"BaseStatusEffect.Stun", 2.0);
      Cyberblock_Log(s"\(name) dazed \(target) 2 s");
    } else if Equals(hack, n"short_circuit") {
      GameObjectEffectHelper.StartEffectEvent(puppet, n"status_electrocuted");
      Cyberblock_Log(s"\(name) electrocuted vfx on \(target)");
    } else if Equals(hack, n"cyberpsychosis") {
      Cyberblock_Log(s"\(name) frenzy \(target) 6 s");
      let frenzy = new CyberblockFrenzyCallback();
      frenzy.game = game;
      frenzy.id = id;
      frenzy.source = target;
      frenzy.left = 4; // strikes at 0, 1.5, 3 and 4.5 s
      frenzy.Call();
    }
  }

  // One frenzy strike: the frenzied NPC hits the nearest other NPC within 4 m. False when the frenzy is over (the
  // frenzied NPC is gone or no longer active).
  public static func FrenzyStrike(game: GameInstance, id: Int32, source: Uint64) -> Bool {
    let player = GetPlayer(game);
    let attacker = CyberblockEffects.Find(game, source) as ScriptedPuppet;
    if !IsDefined(player) || !IsDefined(attacker) || !ScriptedPuppet.IsActive(attacker) {
      Cyberblock_Log(s"hackfx: cyberpsychosis frenzy of \(source) ended");
      return false;
    }
    let center = attacker.GetWorldPosition();
    let best: ref<ScriptedPuppet>;
    let bestDistance2 = 4.0 * 4.0;
    let tether = CyberblockTetherService.Get();
    if IsDefined(tether) {
      for object in tether.Known() {
        let npc = object as ScriptedPuppet;
        if IsDefined(npc) && npc != attacker && !npc.IsPlayer() && npc.IsAttached() {
          let distance2 = Vector4.DistanceSquared(npc.GetWorldPosition(), center);
          if distance2 <= bestDistance2 && ScriptedPuppet.IsActive(npc) && !CyberblockPuppets.IsPuppet(game, npc.GetEntityID())
            && Equals(CyberblockEffects.IsProtected(game, npc, player), n"") {
            best = npc;
            bestDistance2 = distance2;
          }
        }
      }
    }
    if IsDefined(best) {
      let push = CyberblockTetherService.Toward(center, best.GetWorldPosition()) * 3.0;
      CyberblockEffects.StrikeBy(game, best, 0.15, new Vector4(push.X, push.Y, 0.0, 0.0), attacker);
      let victim = CyberblockIds.Hash(best);
      Cyberblock_HackSpread(id, victim, n"frenzy");
      Cyberblock_Log(s"hackfx: cyberpsychosis crowd \(source) strikes \(victim)");
    }
    return true;
  }

  // Ping: reveals the target and the hostile NPCs within 30 m of it (at most 16, nearest first) for 10 s; each of those
  // is reported to Minecraft as `ping`.
  private static func Ping(game: GameInstance, id: Int32, target: ref<ScriptedPuppet>, player: ref<PlayerPuppet>) -> Void {
    let instigator = player.GetEntityID();
    GameObject.SendForceRevealObjectEvent(target, true, n"CyberblockPing", instigator, 10.0);
    let picked: array<ref<ScriptedPuppet>>;
    let pickedDistance2: array<Float>;
    let center = target.GetWorldPosition();
    let tether = CyberblockTetherService.Get();
    if IsDefined(tether) {
      for object in tether.Known() {
        let npc = object as ScriptedPuppet;
        if IsDefined(npc) && npc != target && !npc.IsPlayer() && npc.IsAttached() && IsHostileTowardsPlayer(npc) {
          let distance2 = Vector4.DistanceSquared(npc.GetWorldPosition(), center);
          if distance2 <= 30.0 * 30.0 && ScriptedPuppet.IsAlive(npc) && !CyberblockPuppets.IsPuppet(game, npc.GetEntityID()) {
            let at = ArraySize(picked);
            while at > 0 && pickedDistance2[at - 1] > distance2 {
              at -= 1;
            }
            if at < 16 {
              ArrayInsert(picked, at, npc);
              ArrayInsert(pickedDistance2, at, distance2);
              if ArraySize(picked) > 16 {
                ArrayResize(picked, 16);
                ArrayResize(pickedDistance2, 16);
              }
            }
          }
        }
      }
    }
    for npc in picked {
      GameObject.SendForceRevealObjectEvent(npc, true, n"CyberblockPing", instigator, 10.0);
      Cyberblock_HackSpread(id, CyberblockIds.Hash(npc), n"ping");
    }
    Cyberblock_Log(s"hackfx: ping \(CyberblockIds.Hash(target)) revealed \(ArraySize(picked)) hostile NPCs");
  }

  // A vanilla status effect with V as instigator, cut to seconds.
  private static func Status(game: GameInstance, target: ref<ScriptedPuppet>, player: ref<PlayerPuppet>, record: TweakDBID, seconds: Float) -> Void {
    let effects = GameInstance.GetStatusEffectSystem(game);
    let id = target.GetEntityID();
    effects.ApplyStatusEffect(id, record, TDBID.None(), player.GetEntityID());
    effects.SetStatusEffectRemainingDuration(id, record, seconds);
  }
}

// The end of one hacked person's upload.
public class CyberblockHackFxCallback extends DelayCallback {
  public let game: GameInstance;
  public let id: Int32;
  public let hack: CName;
  public let kind: CName;
  public let target: Uint64;
  public let primary: Bool;

  public func Call() -> Void {
    CyberblockHackFx.Run(this.game, this.id, this.hack, this.kind, this.target, this.primary);
  }
}

// One frenzy strike, then the next one 1.5 s later while strikes are left.
public class CyberblockFrenzyCallback extends DelayCallback {
  public let game: GameInstance;
  public let id: Int32;
  public let source: Uint64;
  public let left: Int32;

  public func Call() -> Void {
    if this.left <= 0 || !CyberblockHackFx.FrenzyStrike(this.game, this.id, this.source) {
      return;
    }
    this.left -= 1;
    if this.left > 0 {
      GameInstance.GetDelaySystem(this.game).DelayCallback(this, 1.5);
    } else {
      Cyberblock_Log(s"hackfx: cyberpsychosis frenzy of \(this.source) over");
    }
  }
}
