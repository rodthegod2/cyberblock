// Cyberpunk's HUD data for Minecraft (plan 2026-10-03-retest3-polish sections 1.1 and 1.11, task U3; F2, F13): money,
// wanted level, district, clock and vehicle speed (`stats`), the tracked job with its objectives and waypoint (`quest`),
// the minimap pins (`pins`), the interaction prompt (`prompt`: "F Talk", "F Pick up", doors, ...), the dialogue choice
// lists (`choices`), dialogue subtitles (`sub`) and phone calls (`call`). Minecraft draws all of it; Cyberpunk's own
// HUD is invisible while On (Ui.reds) but its blackboards and input keep working, so F, E/Q/1-4 and the wheel still
// pick choices in Cyberpunk.
//
// Delayed blackboard listeners (they also fire while the game is paused) send changes at once; a 2 Hz poll from
// CyberblockSystem's tick re-sends everything (the DLL drops unchanged messages and rate-limits stats, pins and a moving
// waypoint), so a Minecraft that just turned On or reconnected is up to date within half a second.
//
// 2.31 script API (the 2.31 scripts the compile checker uses):
//   UIInteractionsDef InteractionChoiceHub (InteractionChoiceHubData: active, title, choices), LootData (LootData:
//   isActive, title, choices), DialogChoiceHubs (DialogChoiceHubs.choiceHubs: ListChoiceHubData id/title/choices/
//   timeProvider), ActiveChoiceHubID, SelectedIndex (the selection inside the active hub, as interactionUIBase.swift and
//   dialogUI.swift read them); InteractionChoiceData inputAction/isHoldAction/localizedName/type/captionParts,
//   ListChoiceData localizedName/type/timeProvider/captionParts; ChoiceTypeWrapper.IsType with
//   gameinteractionsChoiceType; GetCaptionTagsFromArray (captionParts.swift); IVisualizerTimeProvider
//   GetCurrentProgress/GetDuration (dialogUI.swift's timer bar); UIGameDataDef ShowDialogLine (array<scnDialogLineData>),
//   HideDialogLine (array<CRUID>), HideDialogLineByData (baseSubtitlesControllers.swift); UI_ComDeviceDef
//   PhoneCallInformation (callPhase, isPlayerCalling, isAudioCall, callMode, isRejectable, contactName) and the
//   contact lookup of incomingCallController.swift (JournalManager.GetContacts, JournalContact.GetId/GetLocalizedName);
//   UI_WantedBarDef.CurrentWantedLevel; UI_SystemDef.TrackedMappin (an IMappin, quest_tracker.swift); UI_MapDef
//   .currentLocation (a district LocalizedName, minimap.swift); JournalManager GetTrackedEntry/GetParentEntry/
//   GetChildren/GetQuestType/GetObjectiveCurrentCounter/GetObjectiveTotalCounter/GetIsObjectiveOptional/GetEntryHash/
//   RegisterScriptCallback/UnregisterScriptCallback with gameJournalListenerType (quest_tracker.swift);
//   MappinSystem.GetMappins(gamemappinsMappinTargetType.Minimap)/GetQuestMappinPositionsByObjective, IMappin
//   IsVisible/IsActive/IsQuestMappin/IsPlayerTracked/GetWorldPosition/GetDistanceToPlayer/GetVariant;
//   TransactionSystem.GetItemQuantity(player, MarketSystem.Money()) (vendor.swift); TimeSystem.GetGameTime,
//   GameTime.Hours/Minutes; PlayerPuppet.GetMountedVehicle, VehicleObject.GetCurrentSpeed and the car HUD's km/h
//   conversion (car_hud.swift: |v| x the vehicle_ui speed_to_multiplier curve x 1.61).
//   Choice keys from r6/config/inputUserMappings.xml: Choice1 F, Choice2 R, Choice3 1, Choice4 2.

public class CyberblockHud {
  private let game: GameInstance;
  private let registered: Bool;
  // On and linked, as of the last tick: the listeners send only then.
  private let on: Bool;
  private let pollTicks: Int32;
  private let interactionsBB: wref<IBlackboard>;
  private let gameDataBB: wref<IBlackboard>;
  private let comBB: wref<IBlackboard>;
  private let wantedBB: wref<IBlackboard>;
  private let systemBB: wref<IBlackboard>;
  private let mapBB: wref<IBlackboard>;
  private let cbHub: ref<CallbackHandle>;
  private let cbLoot: ref<CallbackHandle>;
  private let cbDialog: ref<CallbackHandle>;
  private let cbActiveHub: ref<CallbackHandle>;
  private let cbSelected: ref<CallbackHandle>;
  private let cbShowLine: ref<CallbackHandle>;
  private let cbHideLine: ref<CallbackHandle>;
  private let cbHideLineByData: ref<CallbackHandle>;
  private let cbCall: ref<CallbackHandle>;
  private let cbWanted: ref<CallbackHandle>;
  private let cbTracked: ref<CallbackHandle>;
  private let cbLocation: ref<CallbackHandle>;
  // Dialogue lines shown on the Minecraft side: their ids and the keys Minecraft knows them by (parallel arrays).
  private let lineIds: array<CRUID>;
  private let lineKeys: array<String>;
  private let lineCounter: Int32;
  // A call rings, or an actionable notification shows (ticks left): T stays Cyberpunk's (Cyberblock_SetPhonePrompt).
  private let callRinging: Bool;
  private let actionTicks: Int32;
  private let phonePrompt: Bool;

  public func Register(game: GameInstance) -> Void {
    if this.registered {
      return;
    }
    this.game = game;
    let bbs = GameInstance.GetBlackboardSystem(game);
    let ui = GetAllBlackboardDefs().UIInteractions;
    this.interactionsBB = bbs.Get(ui);
    if IsDefined(this.interactionsBB) {
      this.cbHub = this.interactionsBB.RegisterDelayedListenerVariant(ui.InteractionChoiceHub, this, n"OnPromptChanged");
      this.cbLoot = this.interactionsBB.RegisterDelayedListenerVariant(ui.LootData, this, n"OnPromptChanged");
      this.cbDialog = this.interactionsBB.RegisterDelayedListenerVariant(ui.DialogChoiceHubs, this, n"OnChoicesChanged");
      this.cbActiveHub = this.interactionsBB.RegisterDelayedListenerInt(ui.ActiveChoiceHubID, this, n"OnChoiceIndexChanged");
      this.cbSelected = this.interactionsBB.RegisterDelayedListenerInt(ui.SelectedIndex, this, n"OnChoiceIndexChanged");
    }
    let data = GetAllBlackboardDefs().UIGameData;
    this.gameDataBB = bbs.Get(data);
    if IsDefined(this.gameDataBB) {
      this.cbShowLine = this.gameDataBB.RegisterDelayedListenerVariant(data.ShowDialogLine, this, n"OnShowDialogLine");
      this.cbHideLine = this.gameDataBB.RegisterDelayedListenerVariant(data.HideDialogLine, this, n"OnHideDialogLine");
      this.cbHideLineByData = this.gameDataBB.RegisterDelayedListenerVariant(data.HideDialogLineByData, this, n"OnHideDialogLineByData");
    }
    let com = GetAllBlackboardDefs().UI_ComDevice;
    this.comBB = bbs.Get(com);
    if IsDefined(this.comBB) {
      this.cbCall = this.comBB.RegisterDelayedListenerVariant(com.PhoneCallInformation, this, n"OnPhoneCall");
    }
    let wanted = GetAllBlackboardDefs().UI_WantedBar;
    this.wantedBB = bbs.Get(wanted);
    if IsDefined(this.wantedBB) {
      this.cbWanted = this.wantedBB.RegisterDelayedListenerInt(wanted.CurrentWantedLevel, this, n"OnStatsChanged");
    }
    let sys = GetAllBlackboardDefs().UI_System;
    this.systemBB = bbs.Get(sys);
    if IsDefined(this.systemBB) {
      this.cbTracked = this.systemBB.RegisterDelayedListenerVariant(sys.TrackedMappin, this, n"OnTrackedMappin");
    }
    let map = GetAllBlackboardDefs().UI_Map;
    this.mapBB = bbs.Get(map);
    if IsDefined(this.mapBB) {
      this.cbLocation = this.mapBB.RegisterDelayedListenerString(map.currentLocation, this, n"OnLocation");
    }
    let journal = GameInstance.GetJournalManager(game);
    if IsDefined(journal) {
      journal.RegisterScriptCallback(this, n"OnJournalChanged", gameJournalListenerType.Tracked);
      journal.RegisterScriptCallback(this, n"OnJournalChanged", gameJournalListenerType.Untracked);
      journal.RegisterScriptCallback(this, n"OnJournalChanged", gameJournalListenerType.State);
      journal.RegisterScriptCallback(this, n"OnJournalChanged", gameJournalListenerType.Counter);
      journal.RegisterScriptCallback(this, n"OnJournalChanged", gameJournalListenerType.ObjectiveOptional);
    }
    this.registered = true;
  }

  public func Unregister() -> Void {
    if !this.registered {
      return;
    }
    let ui = GetAllBlackboardDefs().UIInteractions;
    if IsDefined(this.interactionsBB) {
      this.interactionsBB.UnregisterDelayedListener(ui.InteractionChoiceHub, this.cbHub);
      this.interactionsBB.UnregisterDelayedListener(ui.LootData, this.cbLoot);
      this.interactionsBB.UnregisterDelayedListener(ui.DialogChoiceHubs, this.cbDialog);
      this.interactionsBB.UnregisterDelayedListener(ui.ActiveChoiceHubID, this.cbActiveHub);
      this.interactionsBB.UnregisterDelayedListener(ui.SelectedIndex, this.cbSelected);
    }
    let data = GetAllBlackboardDefs().UIGameData;
    if IsDefined(this.gameDataBB) {
      this.gameDataBB.UnregisterDelayedListener(data.ShowDialogLine, this.cbShowLine);
      this.gameDataBB.UnregisterDelayedListener(data.HideDialogLine, this.cbHideLine);
      this.gameDataBB.UnregisterDelayedListener(data.HideDialogLineByData, this.cbHideLineByData);
    }
    if IsDefined(this.comBB) {
      this.comBB.UnregisterDelayedListener(GetAllBlackboardDefs().UI_ComDevice.PhoneCallInformation, this.cbCall);
    }
    if IsDefined(this.wantedBB) {
      this.wantedBB.UnregisterDelayedListener(GetAllBlackboardDefs().UI_WantedBar.CurrentWantedLevel, this.cbWanted);
    }
    if IsDefined(this.systemBB) {
      this.systemBB.UnregisterDelayedListener(GetAllBlackboardDefs().UI_System.TrackedMappin, this.cbTracked);
    }
    if IsDefined(this.mapBB) {
      this.mapBB.UnregisterDelayedListener(GetAllBlackboardDefs().UI_Map.currentLocation, this.cbLocation);
    }
    let journal = GameInstance.GetJournalManager(this.game);
    if IsDefined(journal) {
      journal.UnregisterScriptCallback(this, n"OnJournalChanged");
    }
    ArrayClear(this.lineIds);
    ArrayClear(this.lineKeys);
    this.SetPhonePrompt(false);
    this.registered = false;
  }

  // 10 Hz from CyberblockSystem.Tick. on: Cyberblock is On (Active or Vehicle) and not saving.
  public func Tick(player: ref<PlayerPuppet>, on: Bool) -> Void {
    this.on = on && Cyberblock_IsLinked() && IsDefined(player);
    if this.actionTicks > 0 {
      this.actionTicks -= 1;
    }
    this.SetPhonePrompt(this.on && (this.callRinging || this.actionTicks > 0));
    if !this.on {
      this.pollTicks = 0;
      return;
    }
    this.pollTicks -= 1;
    if this.pollTicks > 0 {
      return;
    }
    this.pollTicks = 5;
    this.SendStats();
    this.SendQuest();
    this.SendPins(player);
    this.SendPrompt();
    this.SendChoices();
  }

  // Before Cyberblock turns Off or is Lost (while Minecraft still listens): no prompt, choices or subtitles stay up.
  public func ClearMinecraft() -> Void {
    let noTexts: array<String>;
    let noKeys: array<CName>;
    let noBools: array<Bool>;
    let noInts: array<Int32>;
    Cyberblock_HudPrompt("", noTexts, noKeys, noBools, noBools);
    Cyberblock_HudChoices(noInts, noTexts, noInts, noTexts, noInts, -1, -1, -1.0);
    for key in this.lineKeys {
      Cyberblock_Subtitle(false, key, "", "", 0.0);
    }
    ArrayClear(this.lineIds);
    ArrayClear(this.lineKeys);
    this.callRinging = false;
    this.SetPhonePrompt(false);
  }

  // An actionable notification (a message that T opens) showed: T stays Cyberpunk's for 5 s (Notifications.reds).
  public func NoteActionNotification() -> Void {
    this.actionTicks = 50;
    this.SetPhonePrompt(this.on);
  }

  private func SetPhonePrompt(open: Bool) -> Void {
    if NotEquals(open, this.phonePrompt) {
      this.phonePrompt = open;
      Cyberblock_SetPhonePrompt(open);
    }
  }

  // ---- listeners ------------------------------------------------------------------------------------------------------

  protected cb func OnPromptChanged(value: Variant) -> Bool {
    if this.on {
      this.SendPrompt();
    }
  }

  protected cb func OnChoicesChanged(value: Variant) -> Bool {
    if this.on {
      this.SendChoices();
    }
  }

  protected cb func OnChoiceIndexChanged(value: Int32) -> Bool {
    if this.on {
      this.SendChoices();
    }
  }

  protected cb func OnStatsChanged(value: Int32) -> Bool {
    if this.on {
      this.SendStats();
    }
  }

  protected cb func OnLocation(value: String) -> Bool {
    if this.on {
      this.SendStats();
    }
  }

  protected cb func OnTrackedMappin(value: Variant) -> Bool {
    if this.on {
      this.SendQuest();
    }
  }

  protected cb func OnJournalChanged(hash: Uint32, className: CName, notifyOption: JournalNotifyOption, changeType: JournalChangeType) -> Bool {
    if this.on {
      this.SendQuest();
    }
  }

  protected cb func OnShowDialogLine(value: Variant) -> Bool {
    let lines = FromVariant<array<scnDialogLineData>>(value);
    for line in lines {
      if CyberblockHud.Subtitled(line.type) && StrLen(line.text) > 0 && !this.HasLine(line.id) {
        this.lineCounter += 1;
        let key = s"l\(this.lineCounter)";
        ArrayPush(this.lineIds, line.id);
        ArrayPush(this.lineKeys, key);
        if ArraySize(this.lineIds) > 16 {
          ArrayErase(this.lineIds, 0);
          ArrayErase(this.lineKeys, 0);
        }
        if this.on {
          Cyberblock_Subtitle(true, key, GetLocalizedText(line.speakerName), line.text, line.duration);
        }
      }
    }
  }

  protected cb func OnHideDialogLine(value: Variant) -> Bool {
    let ids = FromVariant<array<CRUID>>(value);
    for id in ids {
      this.HideLine(id);
    }
  }

  protected cb func OnHideDialogLineByData(value: Variant) -> Bool {
    let lines = FromVariant<array<scnDialogLineData>>(value);
    for line in lines {
      this.HideLine(line.id);
    }
  }

  protected cb func OnPhoneCall(value: Variant) -> Bool {
    let info = FromVariant<PhoneCallInformation>(value);
    let phase = n"";
    switch info.callPhase {
      case questPhoneCallPhase.IncomingCall:
        phase = info.isPlayerCalling ? n"start" : n"incoming";
        break;
      case questPhoneCallPhase.StartCall:
        phase = n"active";
        break;
      case questPhoneCallPhase.EndCall:
        phase = n"end";
        break;
      default:
        phase = n"";
    }
    this.callRinging = Equals(phase, n"incoming");
    this.SetPhonePrompt(this.on && (this.callRinging || this.actionTicks > 0));
    if this.on && NotEquals(phase, n"") {
      let audio = info.isAudioCall || Equals(info.callMode, questPhoneCallMode.Audio);
      Cyberblock_PhoneCall(phase, this.ContactName(info.contactName), audio, info.isRejectable);
    }
  }

  // ---- senders --------------------------------------------------------------------------------------------------------

  private func SendStats() -> Void {
    let player = GetPlayer(this.game);
    if !IsDefined(player) {
      return;
    }
    let money = GameInstance.GetTransactionSystem(this.game).GetItemQuantity(player, MarketSystem.Money());
    let wanted = IsDefined(this.wantedBB) ? this.wantedBB.GetInt(GetAllBlackboardDefs().UI_WantedBar.CurrentWantedLevel) : 0;
    let district = IsDefined(this.mapBB) ? GetLocalizedText(this.mapBB.GetString(GetAllBlackboardDefs().UI_Map.currentLocation)) : "";
    let time = GameInstance.GetTimeSystem(this.game).GetGameTime();
    let speed = -1.0;
    let vehicle = player.GetMountedVehicle();
    if IsDefined(vehicle) {
      let raw = AbsF(vehicle.GetCurrentSpeed());
      let multiplier = GameInstance.GetStatsDataSystem(this.game).GetValueFromCurve(n"vehicle_ui", raw, n"speed_to_multiplier");
      speed = raw * multiplier * 1.61;
    }
    Cyberblock_HudStats(money, wanted, district, GameTime.Hours(time), GameTime.Minutes(time), speed);
  }

  // The tracked objective as quest_tracker.swift shows it: the job's title and type, the active objectives of its
  // active phases with counters and the optional flag; the waypoint is the tracked mappin, else the objective's first
  // quest mappin position.
  private func SendQuest() -> Void {
    let journal = GameInstance.GetJournalManager(this.game);
    let texts: array<String>;
    let cur: array<Int32>;
    let tot: array<Int32>;
    let optional: array<Bool>;
    let title = "";
    let kind = n"other";
    let tracked = -1;
    let hasWp = false;
    let wp: Vector4;
    let objective = IsDefined(journal) ? journal.GetTrackedEntry() as JournalQuestObjective : null;
    if IsDefined(objective) {
      let phase = journal.GetParentEntry(objective) as JournalQuestPhase;
      let quest = IsDefined(phase) ? journal.GetParentEntry(phase) as JournalQuest : null;
      if IsDefined(quest) {
        title = GetLocalizedText(quest.GetTitle(journal));
        kind = CyberblockHud.QuestKind(journal.GetQuestType(quest));
        let filter: JournalRequestStateFilter;
        filter.active = true;
        let phases: array<wref<JournalEntry>>;
        journal.GetChildren(quest, filter, phases);
        for p in phases {
          let entries: array<wref<JournalEntry>>;
          journal.GetChildren(p, filter, entries);
          for e in entries {
            let o = e as JournalQuestObjective;
            if IsDefined(o) && ArraySize(texts) < 12 {
              if o == objective {
                tracked = ArraySize(texts);
              }
              ArrayPush(texts, GetLocalizedText(o.GetDescription()));
              ArrayPush(cur, journal.GetObjectiveCurrentCounter(o));
              ArrayPush(tot, journal.GetObjectiveTotalCounter(o));
              ArrayPush(optional, journal.GetIsObjectiveOptional(o));
            }
          }
        }
      }
    }
    let mappin = IsDefined(this.systemBB) ? FromVariant<ref<IScriptable>>(this.systemBB.GetVariant(GetAllBlackboardDefs().UI_System.TrackedMappin)) as IMappin : null;
    if IsDefined(mappin) {
      hasWp = true;
      wp = mappin.GetWorldPosition();
    } else {
      if IsDefined(objective) {
        let positions: array<Vector3>;
        if GameInstance.GetMappinSystem(this.game).GetQuestMappinPositionsByObjective(Cast<Uint32>(journal.GetEntryHash(objective)), positions) && ArraySize(positions) > 0 {
          hasWp = true;
          wp = Vector4.Vector3To4(positions[0]);
        }
      }
    }
    Cyberblock_HudQuest(title, kind, texts, cur, tot, optional, tracked, hasWp, wp);
  }

  // Visible, active minimap pins within 150 m (at most 32).
  private func SendPins(player: ref<PlayerPuppet>) -> Void {
    let mappins: array<ref<IMappin>>;
    GameInstance.GetMappinSystem(this.game).GetMappins(gamemappinsMappinTargetType.Minimap, mappins);
    let pos: array<Vector4>;
    let kinds: array<CName>;
    let tracked: array<Bool>;
    for m in mappins {
      if ArraySize(pos) >= 32 {
        break;
      }
      if IsDefined(m) && m.IsVisible() && m.IsActive() && m.GetDistanceToPlayer() <= 150.0 {
        ArrayPush(pos, m.GetWorldPosition());
        ArrayPush(kinds, CyberblockHud.PinKind(m));
        ArrayPush(tracked, m.IsPlayerTracked());
      }
    }
    Cyberblock_HudPins(pos, kinds, tracked);
  }

  // The loot prompt when one is up, else the interaction hub ("F Talk", "F Open", doors, ...); empty clears it.
  private func SendPrompt() -> Void {
    let title = "";
    let texts: array<String>;
    let keys: array<CName>;
    let holds: array<Bool>;
    let inactive: array<Bool>;
    if IsDefined(this.interactionsBB) {
      let ui = GetAllBlackboardDefs().UIInteractions;
      let loot = FromVariant<LootData>(this.interactionsBB.GetVariant(ui.LootData));
      let hub = FromVariant<InteractionChoiceHubData>(this.interactionsBB.GetVariant(ui.InteractionChoiceHub));
      let choices: array<InteractionChoiceData>;
      if loot.isActive && ArraySize(loot.choices) > 0 {
        title = GetLocalizedText(loot.title);
        choices = loot.choices;
      } else {
        if hub.active && ArraySize(hub.choices) > 0 {
          title = GetLocalizedText(hub.title);
          choices = hub.choices;
        }
      }
      for c in choices {
        let tags = GetCaptionTagsFromArray(c.captionParts.parts);
        let text = GetLocalizedText(c.localizedName);
        ArrayPush(texts, StrLen(tags) > 0 ? tags + " " + text : text);
        ArrayPush(keys, CyberblockHud.KeyOf(c.inputAction));
        ArrayPush(holds, c.isHoldAction);
        ArrayPush(inactive, ChoiceTypeWrapper.IsType(c.type, gameinteractionsChoiceType.Inactive) || ChoiceTypeWrapper.IsType(c.type, gameinteractionsChoiceType.CheckFailed));
      }
    }
    Cyberblock_HudPrompt(title, texts, keys, holds, inactive);
  }

  // Every dialogue choice hub with its choices, flags, the active hub, the selection inside it and a timed hub's
  // seconds left.
  private func SendChoices() -> Void {
    let hubIds: array<Int32>;
    let hubTitles: array<String>;
    let hubOf: array<Int32>;
    let texts: array<String>;
    let flags: array<Int32>;
    let active = -1;
    let selected = -1;
    let left = -1.0;
    if IsDefined(this.interactionsBB) {
      let ui = GetAllBlackboardDefs().UIInteractions;
      let hubs = FromVariant<DialogChoiceHubs>(this.interactionsBB.GetVariant(ui.DialogChoiceHubs));
      active = this.interactionsBB.GetInt(ui.ActiveChoiceHubID);
      selected = this.interactionsBB.GetInt(ui.SelectedIndex);
      for hub in hubs.choiceHubs {
        let h = ArraySize(hubIds);
        ArrayPush(hubIds, hub.id);
        ArrayPush(hubTitles, GetLocalizedText(hub.title));
        if IsDefined(hub.timeProvider) {
          left = CyberblockHud.SecondsLeft(hub.timeProvider);
        }
        for c in hub.choices {
          let timed = IsDefined(c.timeProvider) || ChoiceTypeWrapper.IsType(c.type, gameinteractionsChoiceType.TimedDialog);
          if IsDefined(c.timeProvider) {
            left = CyberblockHud.SecondsLeft(c.timeProvider);
          }
          let tags = GetCaptionTagsFromArray(c.captionParts.parts);
          let text = GetLocalizedText(c.localizedName);
          ArrayPush(hubOf, h);
          ArrayPush(texts, StrLen(tags) > 0 ? "[" + tags + "] " + text : text);
          ArrayPush(flags, CyberblockHud.Flags(c.type, timed));
        }
      }
    }
    Cyberblock_HudChoices(hubIds, hubTitles, hubOf, texts, flags, active, selected, left);
  }

  // ---- helpers --------------------------------------------------------------------------------------------------------

  private func HasLine(id: CRUID) -> Bool {
    for known in this.lineIds {
      if Equals(known, id) {
        return true;
      }
    }
    return false;
  }

  private func HideLine(id: CRUID) -> Void {
    let i = 0;
    while i < ArraySize(this.lineIds) {
      if Equals(this.lineIds[i], id) {
        if this.on {
          Cyberblock_Subtitle(false, this.lineKeys[i], "", "", 0.0);
        }
        ArrayErase(this.lineIds, i);
        ArrayErase(this.lineKeys, i);
        return;
      }
      i += 1;
    }
  }

  // The contact's display name, looked up as incomingCallController.swift does.
  private func ContactName(id: CName) -> String {
    let journal = GameInstance.GetJournalManager(this.game);
    if !IsDefined(journal) {
      return "";
    }
    let context: JournalRequestContext;
    context.stateFilter.active = true;
    context.stateFilter.inactive = true;
    let contacts: array<wref<JournalEntry>>;
    journal.GetContacts(context, contacts);
    let none: ref<IJournalManager>;
    for entry in contacts {
      let contact = entry as JournalContact;
      if IsDefined(contact) && Equals(contact.GetId(), NameToString(id)) {
        return contact.GetLocalizedName(none);
      }
    }
    return "";
  }

  // Spoken lines worth a subtitle: what the HUD subtitles show (not radio, TV, overhead chatter or scene comments).
  private static func Subtitled(type: scnDialogLineType) -> Bool {
    return Equals(type, scnDialogLineType.Regular) || Equals(type, scnDialogLineType.Holocall)
      || Equals(type, scnDialogLineType.OwnerlessRegular) || Equals(type, scnDialogLineType.AlwaysCinematicNoSpeaker)
      || Equals(type, scnDialogLineType.Narrator);
  }

  private static func SecondsLeft(provider: wref<IVisualizerTimeProvider>) -> Float {
    let duration = provider.GetDuration();
    return MaxF(0.0, duration * (1.0 - ClampF(provider.GetCurrentProgress(), 0.0, 1.0)));
  }

  // Section 1.2 flag bits: 1 inactive, 2 pay, 4 quest, 8 check_ok, 16 check_fail, 32 timed.
  private static func Flags(type: ChoiceTypeWrapper, timed: Bool) -> Int32 {
    let flags = 0;
    if ChoiceTypeWrapper.IsType(type, gameinteractionsChoiceType.Inactive) {
      flags += 1;
    }
    if ChoiceTypeWrapper.IsType(type, gameinteractionsChoiceType.Pay) {
      flags += 2;
    }
    if ChoiceTypeWrapper.IsType(type, gameinteractionsChoiceType.QuestImportant) {
      flags += 4;
    }
    if ChoiceTypeWrapper.IsType(type, gameinteractionsChoiceType.CheckSuccess) {
      flags += 8;
    }
    if ChoiceTypeWrapper.IsType(type, gameinteractionsChoiceType.CheckFailed) {
      flags += 16;
    }
    if timed {
      flags += 32;
    }
    return flags;
  }

  // The key Cyberpunk binds to a choice action (inputUserMappings.xml).
  private static func KeyOf(action: CName) -> CName {
    if Equals(action, n"Choice1") {
      return n"F";
    }
    if Equals(action, n"Choice2") {
      return n"R";
    }
    if Equals(action, n"Choice3") {
      return n"1";
    }
    if Equals(action, n"Choice4") {
      return n"2";
    }
    return n"";
  }

  private static func QuestKind(type: gameJournalQuestType) -> CName {
    switch type {
      case gameJournalQuestType.MainQuest:
        return n"main";
      case gameJournalQuestType.SideQuest:
      case gameJournalQuestType.MinorQuest:
      case gameJournalQuestType.CourierSideQuest:
      case gameJournalQuestType.ApartmentQuest:
      case gameJournalQuestType.VehicleQuest:
        return n"side";
      case gameJournalQuestType.StreetStory:
      case gameJournalQuestType.CyberPsycho:
      case gameJournalQuestType.Contract:
      case gameJournalQuestType.CourierQuest:
        return n"gig";
      default:
        return n"other";
    }
  }

  // The section 1.1 pin kind of a mappin, from its gamedataMappinVariant.
  public static func PinKind(m: ref<IMappin>) -> CName {
    if m.IsQuestMappin() {
      return n"quest";
    }
    return CyberblockHud.VariantKind(m.GetVariant());
  }

  public static func VariantKind(v: gamedataMappinVariant) -> CName {
    switch v {
      case gamedataMappinVariant.DefaultQuestVariant:
      case gamedataMappinVariant.QuestGiverVariant:
      case gamedataMappinVariant.ExclamationMarkVariant:
      case gamedataMappinVariant.RetrievingVariant:
        return n"quest";
      case gamedataMappinVariant.FastTravelVariant:
      case gamedataMappinVariant.Zzz17_NCARTVariant:
        return n"fasttravel";
      case gamedataMappinVariant.ServicePointRipperdocVariant:
        return n"ripperdoc";
      case gamedataMappinVariant.OpenVendorVariant:
      case gamedataMappinVariant.WanderingMerchantVariant:
      case gamedataMappinVariant.ServicePointBarVariant:
      case gamedataMappinVariant.ServicePointClothesVariant:
      case gamedataMappinVariant.ServicePointCyberwareVariant:
      case gamedataMappinVariant.ServicePointDropPointVariant:
      case gamedataMappinVariant.ServicePointFoodVariant:
      case gamedataMappinVariant.ServicePointGunsVariant:
      case gamedataMappinVariant.ServicePointJunkVariant:
      case gamedataMappinVariant.ServicePointMedsVariant:
      case gamedataMappinVariant.ServicePointTechVariant:
      case gamedataMappinVariant.Zzz14_ServicePointBlackMarketVariant:
        return n"vendor";
      case gamedataMappinVariant.FixerVariant:
      case gamedataMappinVariant.GangWatchVariant:
      case gamedataMappinVariant.OutpostVariant:
      case gamedataMappinVariant.BountyHuntVariant:
      case gamedataMappinVariant.HuntForPsychoVariant:
      case gamedataMappinVariant.ClientInDistressVariant:
      case gamedataMappinVariant.MinorActivityVariant:
      case gamedataMappinVariant.Zzz06_NCPDGigVariant:
        return n"gig";
      case gamedataMappinVariant.Zzz04_PreventionVehicleVariant:
      case gamedataMappinVariant.Zzz11_RoadBlockadeVariant:
        return n"police";
      case gamedataMappinVariant.ApartmentVariant:
      case gamedataMappinVariant.Zzz05_ApartmentToPurchaseVariant:
      case gamedataMappinVariant.Zzz07_PlayerStashVariant:
      case gamedataMappinVariant.Zzz08_WardrobeVariant:
        return n"apartment";
      case gamedataMappinVariant.VehicleVariant:
      case gamedataMappinVariant.Zzz01_CarForPurchaseVariant:
      case gamedataMappinVariant.Zzz02_MotorcycleForPurchaseVariant:
      case gamedataMappinVariant.Zzz03_MotorcycleVariant:
      case gamedataMappinVariant.Zzz19_DelamainTaxiVariant:
        return n"car";
      default:
        return n"other";
    }
  }
}
