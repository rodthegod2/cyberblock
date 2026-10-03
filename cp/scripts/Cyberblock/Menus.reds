// Cyberpunk's menus replaced by Minecraft screens (plan 2026-10-03-retest3-polish sections 1.1 and 1.11, task U5; F12),
// and the Night City screen actions Minecraft sends back (drained by CyberblockUi.Pump, Ui.reds).
//
// While Cyberblock is On, Minecraft is linked, V is not held by a scene or workspot and no escape-hatch menu is open,
// the in-game menu keys open Minecraft screens instead of Cyberpunk's menus (with the same trigger as vanilla: KBM on
// release, gamepad on hold-complete for the shortcut menus):
//   OpenPauseMenu (Esc), OpenHubMenu (H/Tab) -> pause;  OpenMapMenu (M) -> map;  OpenJournalMenu (J) -> journal;
//   OpenInventoryMenu (I), OpenCraftingMenu -> inventory;  OpenPerksMenu (P) -> skills.
// Cyberblock_OpenScreen answers true when Minecraft will open the screen; then the action is consumed. Anything else
// (not linked, Minecraft already in a screen, the q301 stadium cutscene, a disconnected pad) runs vanilla, so the
// player is never stranded. The vehicle state counts as On: Esc in a car opens the Minecraft pause screen too.
//
// Escape hatch: Minecraft's pause screen button "Night City Menu..." sends `uiact cpmenu` (arg pause, or map, journal,
// inventory, skills, crafting): the vanilla menu opens once, and while it (and anything opened from it) stays open the
// keys are not intercepted.
//
// 2.31 script API: gameuiInGameMenuGameController.OnAction/OnInitialize, its m_player, SpawnMenuInstanceEvent(
// n"OnOpenPauseMenu"), SpawnMenuInstanceDataEvent(n"OnOpenHubMenu_InitData" | n"OnOpenRadialHubMenu_InitData",
// HubMenuInitData{m_menuName}) with the radial_hub_menu_enabled fact (inGameMenuGameController.swift OnAction and
// OpenShortcutMenu); ListenerAction.GetName/GetType, gameinputActionType, ListenerActionConsumer.Consume,
// PlayerPuppet.PlayerLastUsedKBM; QuestsSystem.GetFact(n"q301_02a_stadium_stopUIInCutscene"); inkISystemRequestsHandler
// PauseGame/UnpauseGame/IsGamePaused/QuickSave/HasLastCheckpoint/LoadLastCheckpoint/GotoMainMenu and
// GameInstance.IsSavingLocked (pauseMenu.swift, inGameMenuGameController.swift); JournalManager GetQuests/GetChildren/
// GetEntry/GetEntryHash/GetEntryState/GetTrackedEntry/TrackEntry/GetContacts/GetFlattenedMessagesAndChoices/
// GetMessagesAndChoices/SetEntryVisited/ChangeEntryStateByHash (journalManager.swift; the SMS reply logic of
// messengerDialogView.swift ActivateReply), JournalPhoneMessage GetText/GetSender, JournalPhoneChoiceEntry GetText;
// MappinSystem.GetMappins(gamemappinsMappinTargetType.Map)/GetMappin(NewMappinID), IMappin GetDisplayName/
// GetNewMappinID/GetVariant, QuestMappin and PointOfInterestMappin GetJournalPathHash, MappinUtils.GetMappinsGroup and
// MappinsGroup_Record.GroupName (orphans.swift, mappinUtils.swift).
//
// `uiact track`: arg "quest" (or anything but "pin") tracks the journal entry `hash` (a job: its first active
// objective). arg "pin" tracks the pin at index `id` of the last `reply atlas` as the world map does for quest pins
// (worldMap.swift CanQuestTrackMappin/TrackQuestMappin: a pin with a journal entry in the Quest or SecondaryQuest
// mappin group, not a race pin, has that entry tracked). Other pins are tracked on the world map by its native
// TrackMappin, which needs the map's own pin controller and cannot be called from here: the player is told to use the
// Night City map, and the log says `ui: track pin <i> unresolved (...)`.

public abstract class CyberblockMenus {
  // Fast travel from the Minecraft atlas stays off until it is verified in game (plan U5); the atlas then points the
  // player to the Night City map.
  public static func FastTravelEnabled() -> Bool {
    return false;
  }

  // The Minecraft screen for a menu input action, n"" for none.
  public static func ScreenFor(action: CName) -> CName {
    if Equals(action, n"OpenPauseMenu") || Equals(action, n"OpenHubMenu") {
      return n"pause";
    }
    if Equals(action, n"OpenMapMenu") {
      return n"map";
    }
    if Equals(action, n"OpenJournalMenu") {
      return n"journal";
    }
    if Equals(action, n"OpenInventoryMenu") || Equals(action, n"OpenCraftingMenu") {
      return n"inventory";
    }
    if Equals(action, n"OpenPerksMenu") {
      return n"skills";
    }
    return n"";
  }

  // True when the action was handed to Minecraft (the caller consumes it and skips vanilla).
  public static func Intercept(controller: ref<gameuiInGameMenuGameController>, action: ListenerAction) -> Bool {
    let player = controller.GetPlayerControlledObject() as PlayerPuppet;
    if !IsDefined(player) {
      return false;
    }
    let game = player.GetGame();
    let s = CyberblockUiService.Get();
    let system = CyberblockSystem.Get(game);
    if !IsDefined(s) || s.bypass || !IsDefined(system) || !system.UiOn() || !Cyberblock_IsLinked() {
      return false;
    }
    if CyberblockEffects.PlayerLocked(game, player) || GameInstance.GetQuestsSystem(game).GetFact(n"q301_02a_stadium_stopUIInCutscene") > 0 {
      return false;
    }
    let name = ListenerAction.GetName(action);
    let screen = CyberblockMenus.ScreenFor(name);
    if Equals(screen, n"") {
      return false;
    }
    let type = ListenerAction.GetType(action);
    let released = Equals(type, gameinputActionType.BUTTON_RELEASED);
    let trigger = false;
    if Equals(screen, n"pause") {
      trigger = released;
    } else {
      let kbm = player.PlayerLastUsedKBM();
      trigger = (kbm && released) || (!kbm && Equals(type, gameinputActionType.BUTTON_HOLD_COMPLETE));
    }
    if !trigger {
      return false;
    }
    if Cyberblock_OpenScreen(screen) {
      Cyberblock_Log(s"ui: menu \(NameToString(name)) -> minecraft \(NameToString(screen))");
      return true;
    }
    return false;
  }

  // The escape hatch's bypass lasts while the vanilla menu it opened is up (UI_System.IsInMenu), or 5 s if no menu came.
  // From CyberblockUi.Pump.
  public static func UpdateBypass(game: GameInstance) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) || !s.bypass {
      return;
    }
    if CyberblockDisable.InMenu(game) {
      s.bypassSeenMenu = true;
      return;
    }
    s.bypassWait += 1;
    if s.bypassSeenMenu || s.bypassWait > 150 {
      s.bypass = false;
      s.bypassSeenMenu = false;
      s.bypassWait = 0;
      Cyberblock_Log("ui: escape hatch closed");
    }
  }

  // One action from Minecraft (CyberblockUi.Pump). on: Cyberblock is On; only `resume` arrives otherwise.
  public static func Act(game: GameInstance, action: CName, on: Bool) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) {
      return;
    }
    let requests = s.Requests();
    switch action {
      case n"pause":
        if on && !s.pausedByUs && IsDefined(requests) && !requests.IsGamePaused() {
          requests.PauseGame();
          s.pausedByUs = true;
          Cyberblock_Log("ui: paused by Minecraft");
        }
        break;
      case n"resume":
        if s.pausedByUs {
          s.pausedByUs = false;
          if IsDefined(requests) {
            requests.UnpauseGame();
          }
          Cyberblock_Log("ui: resumed");
        }
        break;
      case n"save":
        CyberblockMenus.Save(game, requests);
        break;
      case n"loadlast":
        if IsDefined(requests) && requests.HasLastCheckpoint() {
          CyberblockMenus.Resume(s, requests);
          requests.LoadLastCheckpoint(true);
        } else {
          Cyberblock_Notify(n"warning", "No save to load", "", 3.0);
        }
        break;
      case n"mainmenu":
        if IsDefined(requests) {
          CyberblockMenus.Resume(s, requests);
          requests.GotoMainMenu();
        }
        break;
      case n"cpmenu":
        CyberblockMenus.OpenVanilla(s, requests, Cyberblock_UiActionArg());
        break;
      case n"track":
        if Equals(Cyberblock_UiActionArg(), "pin") {
          CyberblockMenus.TrackPin(game, s, Cyberblock_UiActionId());
        } else {
          CyberblockMenus.Track(game, Cyberblock_UiActionHash());
        }
        break;
      case n"smsreply":
        CyberblockMenus.SmsReply(game, Cyberblock_UiActionHash());
        break;
      case n"fasttravel":
        if !CyberblockMenus.FastTravelEnabled() {
          Cyberblock_Notify(n"info", "Fast travel", "Use the Night City map (Night City Menu...)", 4.0);
        }
        break;
      case n"journalreq":
        CyberblockMenus.ReplyJournal(game, Cyberblock_UiActionId());
        break;
      case n"phonereq":
        CyberblockMenus.ReplyPhone(game, Cyberblock_UiActionId());
        break;
      case n"atlasreq":
        CyberblockMenus.ReplyAtlas(game, Cyberblock_UiActionId());
        break;
      default:
        Cyberblock_Log(s"ui: unknown action \(NameToString(action))");
    }
  }

  private static func Resume(s: ref<CyberblockUiService>, requests: wref<inkISystemRequestsHandler>) -> Void {
    if s.pausedByUs {
      s.pausedByUs = false;
      requests.UnpauseGame();
      Cyberblock_Log("ui: resumed");
    }
  }

  // A quick save, as the vanilla quick-save key does (inGameMenuGameController.swift HandleQuickSave).
  private static func Save(game: GameInstance, requests: wref<inkISystemRequestsHandler>) -> Void {
    let locks: array<gameSaveLock>;
    if !IsDefined(requests) || GameInstance.IsSavingLocked(game, locks) {
      Cyberblock_Notify(n"warning", "Saving is not possible right now", "", 3.0);
      return;
    }
    requests.QuickSave();
  }

  // The escape hatch: the vanilla menu, once.
  private static func OpenVanilla(s: ref<CyberblockUiService>, requests: wref<inkISystemRequestsHandler>, menu: String) -> Void {
    let controller = s.menuController;
    if !IsDefined(controller) {
      Cyberblock_Log("ui: escape hatch: no in-game menu controller");
      return;
    }
    CyberblockMenus.Resume(s, requests);
    s.bypass = true;
    s.bypassSeenMenu = false;
    s.bypassWait = 0;
    Cyberblock_Log(s"ui: escape hatch \(menu)");
    controller.CyberblockOpenVanilla(menu);
  }

  // Tracks a job (its first active objective) or an objective.
  private static func Track(game: GameInstance, hash: Uint32) -> Void {
    let journal = GameInstance.GetJournalManager(game);
    let entry = journal.GetEntry(hash);
    if !IsDefined(entry) {
      Cyberblock_Log(s"ui: track \(hash) unresolved (no journal entry)");
      return;
    }
    let objective = entry as JournalQuestObjective;
    if !IsDefined(objective) && IsDefined(entry as JournalQuest) {
      let filter: JournalRequestStateFilter;
      filter.active = true;
      let phases: array<wref<JournalEntry>>;
      journal.GetChildren(entry, filter, phases);
      for phase in phases {
        let objectives: array<wref<JournalEntry>>;
        journal.GetChildren(phase, filter, objectives);
        for o in objectives {
          if !IsDefined(objective) {
            objective = o as JournalQuestObjective;
          }
        }
      }
    }
    if IsDefined(objective) {
      journal.TrackEntry(objective);
      Cyberblock_Log(s"ui: tracked \(hash)");
    } else {
      Cyberblock_Log(s"ui: track \(hash) unresolved (no active objective)");
    }
  }

  // A pin of the last atlas reply, by its index there (see the header).
  private static func TrackPin(game: GameInstance, s: ref<CyberblockUiService>, index: Int32) -> Void {
    if index < 0 || index >= ArraySize(s.atlasPins) {
      Cyberblock_Log(s"ui: track pin \(index) unresolved (not in the last atlas reply)");
      return;
    }
    let mappin = GameInstance.GetMappinSystem(game).GetMappin(s.atlasPins[index]);
    if !IsDefined(mappin) {
      Cyberblock_Log(s"ui: track pin \(index) unresolved (pin gone)");
      return;
    }
    let hash = 0u;
    let quest = mappin as QuestMappin;
    let poi = mappin as PointOfInterestMappin;
    if IsDefined(quest) {
      hash = quest.GetJournalPathHash();
    } else {
      if IsDefined(poi) {
        hash = poi.GetJournalPathHash();
      }
    }
    let journal = GameInstance.GetJournalManager(game);
    let entry = hash != 0u ? journal.GetEntry(hash) : null;
    let variant = mappin.GetVariant();
    let group = MappinUtils.GetMappinsGroup(variant);
    let questGroup = IsDefined(group) && (Equals(group.GroupName(), n"Quest") || Equals(group.GroupName(), n"SecondaryQuest"));
    if IsDefined(entry) && questGroup && NotEquals(variant, gamedataMappinVariant.Zzz18_RacingVariant) {
      journal.TrackEntry(entry);
      Cyberblock_Log(s"ui: tracked pin \(index)");
      return;
    }
    Cyberblock_Log(s"ui: track pin \(index) unresolved (not a quest pin)");
    Cyberblock_Notify(n"info", "Track", "Set a waypoint on the Night City map (Night City Menu...)", 4.0);
  }

  // Picks an SMS reply as messengerDialogView.swift ActivateReply does: the other current replies become inactive, the
  // chosen one succeeds.
  private static func SmsReply(game: GameInstance, hash: Uint32) -> Void {
    let journal = GameInstance.GetJournalManager(game);
    let choice = journal.GetEntry(hash) as JournalPhoneChoiceEntry;
    if !IsDefined(choice) || NotEquals(journal.GetEntryState(choice), gameJournalEntryState.Active) {
      return;
    }
    let conversation = journal.GetParentEntry(choice);
    let messages: array<wref<JournalEntry>>;
    let replies: array<wref<JournalEntry>>;
    journal.GetMessagesAndChoices(conversation, messages, replies);
    for reply in replies {
      if NotEquals(reply.GetId(), choice.GetId()) {
        journal.SetEntryVisited(reply, true);
        journal.ChangeEntryStateByHash(Cast<Uint32>(journal.GetEntryHash(reply)), gameJournalEntryState.Inactive, JournalNotifyOption.Notify);
      }
    }
    journal.SetEntryVisited(choice, true);
    journal.ChangeEntryStateByHash(hash, gameJournalEntryState.Succeeded, JournalNotifyOption.Notify);
    Cyberblock_Log(s"ui: sms reply \(hash)");
  }

  // `reply journal`: the active jobs first, then the finished ones; each with its objectives (0 active, 1 done, 2 failed).
  private static func ReplyJournal(game: GameInstance, id: Int32) -> Void {
    let journal = GameInstance.GetJournalManager(game);
    Cyberblock_ReplyBegin(n"journal", id);
    let tracked = journal.GetTrackedEntry();
    let trackedQuest: wref<JournalEntry>;
    if IsDefined(tracked) {
      trackedQuest = journal.GetParentEntry(journal.GetParentEntry(tracked));
    }
    let pass = 0;
    while pass < 2 {
      let context: JournalRequestContext;
      context.stateFilter.active = pass == 0;
      context.stateFilter.succeeded = pass == 1;
      let quests: array<wref<JournalEntry>>;
      journal.GetQuests(context, quests);
      for q in quests {
        let quest = q as JournalQuest;
        if IsDefined(quest) {
          Cyberblock_ReplyGroup(Cast<Uint32>(journal.GetEntryHash(quest)), GetLocalizedText(quest.GetTitle(journal)),
            CyberblockMenus.QuestKind(journal.GetQuestType(quest)), quest == trackedQuest, pass == 1);
          let filter: JournalRequestStateFilter;
          filter.active = true;
          filter.succeeded = true;
          filter.failed = true;
          let phases: array<wref<JournalEntry>>;
          journal.GetChildren(quest, filter, phases);
          for phase in phases {
            let objectives: array<wref<JournalEntry>>;
            journal.GetChildren(phase, filter, objectives);
            for o in objectives {
              let objective = o as JournalQuestObjective;
              if IsDefined(objective) {
                let state = journal.GetEntryState(objective);
                let line = Equals(state, gameJournalEntryState.Succeeded) ? 1 : (Equals(state, gameJournalEntryState.Failed) ? 2 : 0);
                Cyberblock_ReplyLine(Cast<Uint32>(journal.GetEntryHash(objective)), GetLocalizedText(objective.GetDescription()), line);
              }
            }
          }
        }
      }
      pass += 1;
    }
    Cyberblock_ReplyEnd();
  }

  // `reply phone`: each contact with messages, the last 16 of them (0 from the contact, 1 from V) and the replies V can
  // pick now (2; their hash is what smsreply sends).
  private static func ReplyPhone(game: GameInstance, id: Int32) -> Void {
    let journal = GameInstance.GetJournalManager(game);
    Cyberblock_ReplyBegin(n"phone", id);
    let context: JournalRequestContext;
    context.stateFilter.active = true;
    let contacts: array<wref<JournalEntry>>;
    journal.GetContacts(context, contacts);
    for c in contacts {
      let contact = c as JournalContact;
      if IsDefined(contact) {
        let messages: array<wref<JournalEntry>>;
        let replies: array<wref<JournalEntry>>;
        journal.GetFlattenedMessagesAndChoices(contact, messages, replies);
        if ArraySize(messages) > 0 || ArraySize(replies) > 0 {
          Cyberblock_ReplyGroup(Cast<Uint32>(journal.GetEntryHash(contact)), contact.GetLocalizedName(journal), n"sms", false, false);
          let first = Max(0, ArraySize(messages) - 16);
          let i = first;
          while i < ArraySize(messages) {
            let message = messages[i] as JournalPhoneMessage;
            let sent = messages[i] as JournalPhoneChoiceEntry;
            if IsDefined(message) {
              Cyberblock_ReplyLine(Cast<Uint32>(journal.GetEntryHash(message)), GetLocalizedText(message.GetText()), Equals(message.GetSender(), gameMessageSender.Player) ? 1 : 0);
            } else {
              if IsDefined(sent) {
                Cyberblock_ReplyLine(Cast<Uint32>(journal.GetEntryHash(sent)), GetLocalizedText(sent.GetText()), 1);
              }
            }
            i += 1;
          }
          for r in replies {
            let reply = r as JournalPhoneChoiceEntry;
            if IsDefined(reply) && Equals(journal.GetEntryState(reply), gameJournalEntryState.Active) {
              Cyberblock_ReplyLine(Cast<Uint32>(journal.GetEntryHash(reply)), GetLocalizedText(reply.GetText()), 2);
            }
          }
        }
      }
    }
    Cyberblock_ReplyEnd();
  }

  // `reply atlas`: every visible Map-target mappin (fast-travel points included; index -1 while fast travel is off), at
  // most 256 (the DLL's cap). Their IDs are kept in reply order for `track pin`.
  private static func ReplyAtlas(game: GameInstance, id: Int32) -> Void {
    let s = CyberblockUiService.Get();
    if IsDefined(s) {
      ArrayClear(s.atlasPins);
    }
    Cyberblock_ReplyBegin(n"atlas", id);
    let mappins: array<ref<IMappin>>;
    GameInstance.GetMappinSystem(game).GetMappins(gamemappinsMappinTargetType.Map, mappins);
    let sent = 0;
    for m in mappins {
      if IsDefined(m) && m.IsVisible() && sent < 256 {
        Cyberblock_ReplyPin(m.GetWorldPosition(), CyberblockHud.PinKind(m), GetLocalizedText(m.GetDisplayName()), -1);
        if IsDefined(s) {
          ArrayPush(s.atlasPins, m.GetNewMappinID());
        }
        sent += 1;
      }
    }
    Cyberblock_ReplyEnd();
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
}

@wrapMethod(gameuiInGameMenuGameController)
protected cb func OnInitialize() -> Bool {
  let result = wrappedMethod();
  let s = CyberblockUiService.Get();
  if IsDefined(s) {
    s.menuController = this;
  }
  return result;
}

@wrapMethod(gameuiInGameMenuGameController)
protected cb func OnAction(action: ListenerAction, consumer: ListenerActionConsumer) -> Bool {
  if CyberblockMenus.Intercept(this, action) {
    ListenerActionConsumer.Consume(consumer);
    return false;
  }
  return wrappedMethod(action, consumer);
}

// The escape hatch's vanilla menu: the pause menu, or a hub menu by its shortcut name.
@addMethod(gameuiInGameMenuGameController)
public func CyberblockOpenVanilla(menu: String) -> Void {
  if Equals(menu, "") || Equals(menu, "pause") {
    this.SpawnMenuInstanceEvent(n"OnOpenPauseMenu");
    return;
  }
  let data = new HubMenuInitData();
  switch menu {
    case "map":
      data.m_menuName = n"world_map";
      break;
    case "journal":
      data.m_menuName = n"quest_log";
      break;
    case "skills":
      data.m_menuName = n"new_perks";
      break;
    case "crafting":
      data.m_menuName = n"crafting_main";
      break;
    default:
      data.m_menuName = n"inventory_screen";
  }
  let event = GetFact(this.m_player.GetGame(), n"radial_hub_menu_enabled") > 0 ? n"OnOpenRadialHubMenu_InitData" : n"OnOpenHubMenu_InitData";
  this.SpawnMenuInstanceDataEvent(event, data);
}
