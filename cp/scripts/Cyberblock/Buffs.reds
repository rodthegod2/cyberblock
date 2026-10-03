// Steve's Minecraft status effects shown on V (plan 2026-10-03 sections 1.2 effect, 1.4 and 1.5, task S3.4). Minecraft
// sends an effect whenever one is added, changed or removed (seconds 0 = removed, 86400 = infinite); this file keeps
// them with their expiry and, while Cyberblock is Active on foot, applies the mapped ones to V:
//   invisibility       -> optical camo: BaseStatusEffect.OpticalCamoPlayerBuffRare + PlayerPuppet.SetInvisible(true)
//   speed              -> MaxSpeed x(1 + 0.2 (amp+1)), at most x3
//   slowness           -> MaxSpeed x(1 - 0.15 (amp+1)), at least x0.3
//   jump_boost         -> JumpHeight x(1 + 0.5 (amp+1)), at most x4
//   nausea             -> BaseStatusEffect.Drunk (the fullscreen drunk wobble)
//   blindness/darkness -> AIQuickHackStatusEffect.HackBlind (the blind screen an enemy netrunner's Reboot Optics gives V)
// Any other name is ignored and logged once. No damage-over-time effect is ever mapped: a DoT on V would reach Steve a
// second time through Health.reds. The caps on speed and jump keep /effect amplifiers up to 255 from flinging V.
//
// Lifetime: Tick(active false) takes everything off V but keeps the entries (their expiry keeps running), so a potion
// comes back after a vehicle ride; Clear (saves, loads, leaving Active/Vehicle) does the same and also forgets infinite
// entries, whose removal could otherwise be missed while Cyberblock is off (S1 drops effect events then). Stat modifiers
// are removed exactly (the same objects, from the entity they were added to); status effects are removed only when we
// applied them (one V already had, e.g. from a drink, is left alone).
//
// 2.31 sources (decompiled 2.31 scripts; TweakDB from r6/cache/tweakdb.bin and tweakdb_ep1.bin; compile-checked):
//   MaxSpeed: the stat the player's locomotion speed modifiers use (LocomotionTransitions AddMaxSpeedModifier,
//     EnableReloadStatModifier); player speed buffs scale it (BaseStatusEffect.DetectorRushPlayerBuff*:
//     AdditiveMultiplier MaxSpeed; AlcoholDebuff and PlayerElectrocuted: Multiplier MaxSpeed).
//   JumpHeight: set per locomotion state by the PlayerLocomotion modifier groups (JumpJumpHeightModifier +1,
//     DoubleJumpJumpHeightModifier +2.6, ...) and scaled for V by BaseStatusEffect.PlayerBleeding (Multiplier 0.5).
//   BaseStatusEffect.Drunk: PlayerPuppet.ProcessTieredDrunkEffect starts status_drunk_level_1..3 by stack count when it
//     is applied or removed. Its packages hold only weapon-sway stat groups (Prereqs.AnyWeaponHeldPrereq) and the
//     DrunkSFX effector: no attack, so no damage, although it inherits the Debuff/DoT/Poison tags from Poisoned.
//   AIQuickHackStatusEffect.HackBlind: VFX and SFX status_blinded, effectors StopAndPlayVFX hacking_glitch_low,
//     weapon-sway stat group, WeaponMalfunctionHudEffector; no attack. Immunity stat BlindImmunity.
//   BaseStatusEffect.OpticalCamoPlayerBuffRare and SetInvisible: audit C.3 (snippets Camo and Invisible).
//   StatusEffectSystem.ApplyStatusEffect(EntityID, TweakDBID, instigator TweakDBID) with our own instigator record id,
//     ObjectHasStatusEffect, SetStatusEffectRemainingDuration, RemoveStatusEffect; RPGManager.CreateStatModifier +
//     StatsSystem.AddModifier / RemoveModifier. Expiry on engine time (GameInstance.GetEngineTime).

public class CyberblockBuffsService extends ScriptableService {
  // Minecraft's effects: name, engine-time end, amplifier.
  public let names: array<CName>;
  public let untils: array<Float>;
  public let amps: array<Int32>;
  // An entry changed since the last sync: durations of applied status effects are refreshed.
  public let changed: Bool;
  public let active: Bool;
  // What is on V now, and on which entity.
  public let owner: EntityID;
  public let speed: ref<gameStatModifierData>;
  public let speedValue: Float;
  public let slowness: ref<gameStatModifierData>;
  public let slownessValue: Float;
  public let jump: ref<gameStatModifierData>;
  public let jumpValue: Float;
  public let camo: Bool;
  public let invisible: Bool;
  public let drunk: Bool;
  public let blind: Bool;
  // Earliest engine time to re-apply a status effect the game took off V.
  public let nextReapply: Float;
  public let logged: array<CName>;

  public static func Get() -> ref<CyberblockBuffsService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockBuffsService") as CyberblockBuffsService;
  }
}

public abstract class CyberblockBuffs {
  public static func Effect(game: GameInstance, name: CName, seconds: Float, amplifier: Int32) -> Void {
    let buffs = CyberblockBuffsService.Get();
    if !IsDefined(buffs) {
      return;
    }
    if !CyberblockBuffs.Mapped(name) {
      if !ArrayContains(buffs.logged, name) {
        ArrayPush(buffs.logged, name);
        Cyberblock_Log(s"buffs: effect \(NameToString(name)) has no Cyberpunk mapping, ignored");
      }
      return;
    }
    let i = ArrayFindFirst(buffs.names, name);
    if seconds <= 0.0 {
      if i >= 0 {
        ArrayErase(buffs.names, i);
        ArrayErase(buffs.untils, i);
        ArrayErase(buffs.amps, i);
      }
    } else {
      let until = CyberblockBuffs.Now(game) + MinF(seconds, 86400.0);
      let amp = Clamp(amplifier, 0, 255);
      if i < 0 {
        ArrayPush(buffs.names, name);
        ArrayPush(buffs.untils, until);
        ArrayPush(buffs.amps, amp);
      } else {
        buffs.untils[i] = until;
        buffs.amps[i] = amp;
      }
    }
    buffs.changed = true;
    Cyberblock_Log(s"buffs: \(NameToString(name)) \(seconds) s, amplifier \(amplifier)");
    let player = GetPlayer(game);
    if buffs.active && IsDefined(player) {
      CyberblockBuffs.Sync(game, buffs, player);
    }
  }

  // Every tick. active: Cyberblock is Active on foot and no save is being written.
  public static func Tick(game: GameInstance, player: ref<PlayerPuppet>, active: Bool) -> Void {
    let buffs = CyberblockBuffsService.Get();
    if !IsDefined(buffs) {
      return;
    }
    buffs.active = active && IsDefined(player);
    if buffs.active {
      CyberblockBuffs.Sync(game, buffs, player);
    } else {
      CyberblockBuffs.TakeOff(game, buffs);
    }
  }

  public static func Clear(game: GameInstance) -> Void {
    let buffs = CyberblockBuffsService.Get();
    if !IsDefined(buffs) {
      return;
    }
    buffs.active = false;
    CyberblockBuffs.TakeOff(game, buffs);
    let now = CyberblockBuffs.Now(game);
    let i = ArraySize(buffs.names) - 1;
    while i >= 0 {
      if buffs.untils[i] - now >= 86000.0 {
        ArrayErase(buffs.names, i);
        ArrayErase(buffs.untils, i);
        ArrayErase(buffs.amps, i);
      }
      i -= 1;
    }
  }

  private static func Mapped(name: CName) -> Bool {
    return Equals(name, n"invisibility") || Equals(name, n"speed") || Equals(name, n"slowness") || Equals(name, n"jump_boost")
      || Equals(name, n"nausea") || Equals(name, n"blindness") || Equals(name, n"darkness");
  }

  // Makes V match the live entries: expired ones go, the rest are applied or refreshed.
  private static func Sync(game: GameInstance, buffs: ref<CyberblockBuffsService>, player: ref<PlayerPuppet>) -> Void {
    let now = CyberblockBuffs.Now(game);
    let i = ArraySize(buffs.names) - 1;
    while i >= 0 {
      if buffs.untils[i] <= now {
        ArrayErase(buffs.names, i);
        ArrayErase(buffs.untils, i);
        ArrayErase(buffs.amps, i);
        buffs.changed = true;
      }
      i -= 1;
    }
    let id = player.GetEntityID();
    if buffs.owner != id {
      CyberblockBuffs.TakeOff(game, buffs); // V was replaced (load): the old modifiers went with the old V
      buffs.owner = id;
    }
    let stats = GameInstance.GetStatsSystem(game);
    let statsId = Cast<StatsObjectID>(id);

    let want = 0.0;
    let amp = CyberblockBuffs.Amp(buffs, n"speed");
    if amp >= 0 {
      want = MinF(1.0 + 0.2 * Cast<Float>(amp + 1), 3.0);
    }
    if want != buffs.speedValue {
      buffs.speed = CyberblockBuffs.Multiplier(stats, statsId, buffs.speed, gamedataStatType.MaxSpeed, want);
      buffs.speedValue = want;
    }
    want = 0.0;
    amp = CyberblockBuffs.Amp(buffs, n"slowness");
    if amp >= 0 {
      want = MaxF(1.0 - 0.15 * Cast<Float>(amp + 1), 0.3);
    }
    if want != buffs.slownessValue {
      buffs.slowness = CyberblockBuffs.Multiplier(stats, statsId, buffs.slowness, gamedataStatType.MaxSpeed, want);
      buffs.slownessValue = want;
    }
    want = 0.0;
    amp = CyberblockBuffs.Amp(buffs, n"jump_boost");
    if amp >= 0 {
      want = MinF(1.0 + 0.5 * Cast<Float>(amp + 1), 4.0);
    }
    if want != buffs.jumpValue {
      buffs.jump = CyberblockBuffs.Multiplier(stats, statsId, buffs.jump, gamedataStatType.JumpHeight, want);
      buffs.jumpValue = want;
    }

    let reapply = now >= buffs.nextReapply;
    let refresh = buffs.changed;
    let camoLeft = CyberblockBuffs.Remaining(buffs, n"invisibility", n"invisibility", now);
    buffs.camo = CyberblockBuffs.Status(player, t"BaseStatusEffect.OpticalCamoPlayerBuffRare", camoLeft, buffs.camo, refresh, reapply);
    let invisible = camoLeft > 0.0;
    if NotEquals(invisible, buffs.invisible) {
      player.SetInvisible(invisible);
      buffs.invisible = invisible;
    }
    buffs.drunk = CyberblockBuffs.Status(player, t"BaseStatusEffect.Drunk", CyberblockBuffs.Remaining(buffs, n"nausea", n"nausea", now), buffs.drunk, refresh, reapply);
    buffs.blind = CyberblockBuffs.Status(player, t"AIQuickHackStatusEffect.HackBlind", CyberblockBuffs.Remaining(buffs, n"blindness", n"darkness", now), buffs.blind, refresh, reapply);
    if reapply {
      buffs.nextReapply = now + 1.0;
    }
    buffs.changed = false;
  }

  // Takes everything we put on V off again; the entries stay.
  private static func TakeOff(game: GameInstance, buffs: ref<CyberblockBuffsService>) -> Void {
    let stats = GameInstance.GetStatsSystem(game);
    let statsId = Cast<StatsObjectID>(buffs.owner);
    buffs.speed = CyberblockBuffs.Multiplier(stats, statsId, buffs.speed, gamedataStatType.MaxSpeed, 0.0);
    buffs.speedValue = 0.0;
    buffs.slowness = CyberblockBuffs.Multiplier(stats, statsId, buffs.slowness, gamedataStatType.MaxSpeed, 0.0);
    buffs.slownessValue = 0.0;
    buffs.jump = CyberblockBuffs.Multiplier(stats, statsId, buffs.jump, gamedataStatType.JumpHeight, 0.0);
    buffs.jumpValue = 0.0;
    if !buffs.camo && !buffs.invisible && !buffs.drunk && !buffs.blind {
      return;
    }
    let player = GameInstance.FindEntityByID(game, buffs.owner) as PlayerPuppet;
    let effects = GameInstance.GetStatusEffectSystem(game);
    if buffs.camo {
      effects.RemoveStatusEffect(buffs.owner, t"BaseStatusEffect.OpticalCamoPlayerBuffRare");
      buffs.camo = false;
    }
    if buffs.drunk {
      effects.RemoveStatusEffect(buffs.owner, t"BaseStatusEffect.Drunk");
      buffs.drunk = false;
    }
    if buffs.blind {
      effects.RemoveStatusEffect(buffs.owner, t"AIQuickHackStatusEffect.HackBlind");
      buffs.blind = false;
    }
    if buffs.invisible {
      if IsDefined(player) {
        player.SetInvisible(false);
      }
      buffs.invisible = false;
    }
  }

  // Replaces a multiplier modifier: removes current (if any) and adds stat x value when value > 0. Returns the new one.
  private static func Multiplier(stats: ref<StatsSystem>, id: StatsObjectID, current: ref<gameStatModifierData>, stat: gamedataStatType, value: Float) -> ref<gameStatModifierData> {
    if IsDefined(current) {
      stats.RemoveModifier(id, current);
    }
    if value <= 0.0 {
      return null;
    }
    let mod = RPGManager.CreateStatModifier(stat, gameStatModifierType.Multiplier, value);
    stats.AddModifier(id, mod);
    return mod;
  }

  // Applies, refreshes or removes one status effect on V. applied: we put it there. Returns the new applied flag. One
  // V already had from the game is never touched; one the game took off ours is re-applied when reapply allows.
  private static func Status(player: ref<PlayerPuppet>, effect: TweakDBID, remaining: Float, applied: Bool, refresh: Bool, reapply: Bool) -> Bool {
    let effects = GameInstance.GetStatusEffectSystem(player.GetGame());
    let id = player.GetEntityID();
    let has = StatusEffectSystem.ObjectHasStatusEffect(player, effect);
    if remaining <= 0.0 {
      if applied && has {
        effects.RemoveStatusEffect(id, effect);
      }
      return false;
    }
    if has {
      if applied && refresh {
        effects.SetStatusEffectRemainingDuration(id, effect, remaining);
      }
      return applied;
    }
    if applied && !reapply {
      return true;
    }
    if effects.ApplyStatusEffect(id, effect, t"Cyberblock.Buffs") {
      effects.SetStatusEffectRemainingDuration(id, effect, remaining);
    }
    return true;
  }

  // The amplifier of a live entry, -1 if there is none.
  private static func Amp(buffs: ref<CyberblockBuffsService>, name: CName) -> Int32 {
    let i = ArrayFindFirst(buffs.names, name);
    return i >= 0 ? buffs.amps[i] : -1;
  }

  // Seconds left of the longer of two entries, 0 if neither is live.
  private static func Remaining(buffs: ref<CyberblockBuffsService>, a: CName, b: CName, now: Float) -> Float {
    let left = 0.0;
    let i = 0;
    while i < ArraySize(buffs.names) {
      if Equals(buffs.names[i], a) || Equals(buffs.names[i], b) {
        left = MaxF(left, buffs.untils[i] - now);
      }
      i += 1;
    }
    return left;
  }

  private static func Now(game: GameInstance) -> Float {
    return EngineTime.ToFloat(GameInstance.GetEngineTime(game));
  }
}
