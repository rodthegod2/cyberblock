// Cyberpunk's notifications as Minecraft toasts (plan 2026-10-03-retest3-polish sections 1.1 and 1.11, task U4; F12):
// while Cyberblock is On every notification that Cyberpunk shows goes to Minecraft as `notify` (kind, title, text,
// seconds); the vanilla widget still plays, invisible (the game-notifications layer is at opacity 0, Ui.reds). Saves
// get "Saving Night City..." / "Saved" toasts (Cyberblock.reds).
//
// How: the plan's wrap of SetNotificationData is not possible. Every override of it overrides the native
// gameuiGenericNotificationReceiverGameController.SetNotificationData, so the compiled overrides keep the native's
// unmangled name and the 2.31 redscript compiler answers @wrapMethod with UNRESOLVED_METHOD (checked with
// compile_check.py for GenericNotificationController and all 17 subclasses; the native AddNewNotificationData cannot be
// wrapped either). Instead each notification controller registers itself from OnInitialize (a callback, which wraps
// fine) and CyberblockUi.Pump reads its view data on the next pass (the native sets it right after spawning the
// widget), through accessors added to the controllers. Each controller is reported once.
//
// 2.31 script API: GenericNotificationController (OnInitialize, private m_data set by its SetNotificationData, which
// every subclass but ZoneAlertNotification, VehicleAlertNotification, AwacsAlertNotification, AuthorisationNotification
// and ProgressionNotification calls; ZoneAlertNotification keeps m_zone_data and ProgressionNotification
// progression_data), CraftingNotification (overrides OnInitialize without super), the classes of
// cyberpunk/UI/fullscreen/notification/*.swift and widgets/notifications/progression_notification.swift. View data:
// GenericNotificationViewData title/text/action, CurrencyUpdateNotificationViewData diff/total,
// PhoneMessageNotificationViewData contactHash (+ QuestUpdateNotificationViewData SMSText/SMSLocKey/rewardXP, as
// MessengerNotification reads them), ItemAddedNotificationViewData itemID, TarotCardAddedNotificationViewData cardName,
// ShardCollectedNotificationViewData shardTitle, LevelUpNotificationViewData levelupdata.lvl, ProgressionViewData delta,
// ZoneAlertNotificationViewData securityZoneData (ESecurityAreaType). Item names as quest_tracker.swift builds them
// (GetLocalizedText(NameToString(Item_Record.DisplayName()))).

public abstract class CyberblockNotifications {
  // From every notification controller's OnInitialize.
  public static func Track(controller: ref<GenericNotificationController>) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) {
      return;
    }
    if ArraySize(s.pendingNotifications) >= 16 {
      ArrayErase(s.pendingNotifications, 0);
      ArrayErase(s.pendingAges, 0);
    }
    ArrayPush(s.pendingNotifications, controller);
    ArrayPush(s.pendingAges, 0);
  }

  // From CyberblockUi.Pump: reports every tracked controller whose data is set (or that keeps none), drops the rest
  // after about half a second. on: Cyberblock is On (only then is anything sent).
  public static func Poll(game: GameInstance, on: Bool) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) {
      return;
    }
    let i = 0;
    while i < ArraySize(s.pendingNotifications) {
      let controller = s.pendingNotifications[i];
      let data = IsDefined(controller) ? CyberblockNotifications.DataOf(controller) : null;
      let keepsNone = IsDefined(controller) && CyberblockNotifications.KeepsNoData(controller);
      if !IsDefined(controller) || IsDefined(data) || keepsNone || s.pendingAges[i] >= 15 {
        if on && IsDefined(controller) && (IsDefined(data) || keepsNone) {
          CyberblockNotifications.Report(game, controller, data);
        }
        ArrayErase(s.pendingNotifications, i);
        ArrayErase(s.pendingAges, i);
      } else {
        s.pendingAges[i] += 1;
        i += 1;
      }
    }
  }

  // The view data a controller was given (null until SetNotificationData ran).
  private static func DataOf(controller: ref<GenericNotificationController>) -> ref<GenericNotificationViewData> {
    let zone = controller as ZoneAlertNotification;
    if IsDefined(zone) {
      return zone.CyberblockZoneData();
    }
    let progression = controller as ProgressionNotification;
    if IsDefined(progression) {
      return progression.CyberblockProgressionData();
    }
    return controller.CyberblockData();
  }

  // Controllers whose SetNotificationData stores nothing: reported from their class alone.
  private static func KeepsNoData(controller: ref<GenericNotificationController>) -> Bool {
    return controller.IsA(n"AwacsAlertNotification") || controller.IsA(n"VehicleAlertNotification")
      || controller.IsA(n"AuthorisationNotification");
  }

  public static func Report(game: GameInstance, controller: ref<GenericNotificationController>, data: ref<GenericNotificationViewData>) -> Void {
    let system = CyberblockSystem.Get(game);
    if !IsDefined(system) {
      return;
    }
    let kind = n"info";
    let title = IsDefined(data) ? GetLocalizedText(data.title) : "";
    let text = IsDefined(data) ? GetLocalizedText(data.text) : "";
    let secs = 4.0;
    let sms = data as PhoneMessageNotificationViewData;
    let quest = data as QuestUpdateNotificationViewData;
    let money = data as CurrencyUpdateNotificationViewData;
    let item = data as ItemAddedNotificationViewData;
    let tarot = data as TarotCardAddedNotificationViewData;
    let shard = data as ShardCollectedNotificationViewData;
    let level = data as LevelUpNotificationViewData;
    let xp = data as ProgressionViewData;
    let zone = data as ZoneAlertNotificationViewData;
    if IsDefined(sms) {
      kind = n"sms";
      secs = 6.0;
      let journal = GameInstance.GetJournalManager(game);
      let contact = journal.GetEntry(Cast<Uint32>(sms.contactHash)) as JournalContact;
      if IsDefined(contact) {
        title = contact.GetLocalizedName(journal);
      }
      text = NotEquals(sms.SMSLocKey, "") ? GetLocalizedText(sms.SMSLocKey) : sms.SMSText;
    } else {
      if IsDefined(quest) {
        kind = controller.IsA(n"NewLocationNotification") ? n"zone" : n"quest";
        if controller.IsA(n"NCPDJobDoneNotification") && quest.rewardXP > 0 {
          text = s"+\(quest.rewardXP) XP";
        }
      } else {
        if IsDefined(money) {
          kind = n"money";
          text = (money.diff >= 0 ? "+" : "") + IntToString(money.diff) + " €$ (" + ToString(money.total) + ")";
        } else {
          if IsDefined(item) {
            kind = n"item";
            if StrLen(title) == 0 {
              let record = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(item.itemID));
              if IsDefined(record) {
                title = GetLocalizedText(NameToString(record.DisplayName()));
              }
            }
          } else {
            if IsDefined(tarot) {
              kind = n"item";
              title = GetLocalizedText(tarot.cardName);
            } else {
              if IsDefined(shard) {
                kind = n"shard";
                title = GetLocalizedText(shard.shardTitle);
              } else {
                if IsDefined(level) {
                  kind = n"level";
                  text = s"Level \(level.levelupdata.lvl)";
                } else {
                  if IsDefined(xp) {
                    kind = n"xp";
                    text = s"+\(xp.delta)";
                    secs = 2.0;
                  } else {
                    if IsDefined(zone) {
                      kind = n"zone";
                      text = CyberblockNotifications.ZoneText(zone.securityZoneData);
                    } else {
                      kind = CyberblockNotifications.KindOf(controller, data);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    if IsDefined(data) && IsDefined(data.action) {
      // A notification with an action (an SMS T opens): T stays Cyberpunk's for a while.
      system.Hud().NoteActionNotification();
    }
    Cyberblock_Log(s"ui: notify \(NameToString(kind))");
    Cyberblock_Notify(kind, title, text, secs);
  }

  private static func KindOf(controller: ref<GenericNotificationController>, data: ref<GenericNotificationViewData>) -> CName {
    if controller.IsA(n"AwacsAlertNotification") {
      return n"wanted";
    }
    if controller.IsA(n"VehicleAlertNotification") || IsDefined(data as UIInGameNotificationViewData) {
      return n"warning";
    }
    if controller.IsA(n"BountyCollectedNotification") {
      return n"money";
    }
    if controller.IsA(n"CraftingNotification") {
      return n"item";
    }
    return n"info";
  }

  private static func ZoneText(type: ESecurityAreaType) -> String {
    switch type {
      case ESecurityAreaType.DANGEROUS:
        return "Dangerous zone";
      case ESecurityAreaType.RESTRICTED:
        return "Restricted area";
      case ESecurityAreaType.SAFE:
        return "Safe zone";
      default:
        return "Public area";
    }
  }
}

@wrapMethod(GenericNotificationController)
protected cb func OnInitialize() -> Bool {
  let result = wrappedMethod();
  CyberblockNotifications.Track(this);
  return result;
}

// CraftingNotification's OnInitialize does not call super.
@wrapMethod(CraftingNotification)
protected cb func OnInitialize() -> Bool {
  let result = wrappedMethod();
  CyberblockNotifications.Track(this);
  return result;
}

@addMethod(GenericNotificationController)
public func CyberblockData() -> ref<GenericNotificationViewData> {
  return this.m_data;
}

@addMethod(ZoneAlertNotification)
public func CyberblockZoneData() -> ref<ZoneAlertNotificationViewData> {
  return this.m_zone_data;
}

@addMethod(ProgressionNotification)
public func CyberblockProgressionData() -> ref<ProgressionViewData> {
  return this.progression_data;
}
