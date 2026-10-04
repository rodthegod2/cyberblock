// Cyberpunk's 2D UI while Cyberblock is On (plan 2026-10-03-retest3-polish sections 1.4 and 1.11, task U1): the HUD,
// game-notification and waiting-sign ink layers stay alive (controllers, listeners, interaction and choice input keep
// running) but are drawn at opacity 0; Minecraft draws every piece of UI from the data Hud.reds and Notifications.reds
// stream. Nothing shows the Cyberpunk HUD because of prompts or choices any more.
//
// Exceptions (logged as `ui: shown for <reason>`), each restyled by CyberblockRestyle and framed by Minecraft:
//   - the game-notifications layer while a blocking popup is up there (tutorial popups that wait for input or freeze
//     time, codex, shard, SMS, PonR rewards, vehicle radio/manager/colour, frame switcher, twintone popups), while the
//     game is paused by something that is not Cyberblock, and while a Cyberpunk menu is open (the quantity pickers and
//     confirmations of vendors, ripperdoc, crafting and the inventory are spawned there; `menu popups`);
//   - the HUD layer during a braindance (its editor timeline).
// The layers stay hidden through saves (ink opacity is not part of a save).
//
// 2.31 script API: inkWidget.GetOpacity/SetOpacity/IsVisible/SetVisible (abstractWidgets.swift), Braindance.IsActive
// (BraindanceBlackboardDef, blackboardDefinitions.swift), inkISystemRequestsHandler.IsGamePaused (orphans.swift),
// TimeSystem.IsTimeDilationActive(reason) (orphans.swift) with tutorialGameController.swift's n"UI_TutorialPopup"; the
// popups PopupsManager.ShowGameNotification spawns (popupManager.swift) and their controllers:
// TutorialPopupGameController (m_inputBlocked/m_gamePaused), CodexPopupGameController, ShardNotificationController,
// PhoneMessagePopupGameController, BaseModalListPopupGameController (PonrRewards, VehicleRadioPopup,
// VehiclesManagerPopup), vehicleColorSelectorGameController, FrameSwitcherMenuGameController,
// TwintoneOverrideGameController, GenericMessageNotification.
// Codeware 1.20: GameInstance.GetInkSystem(), inkSystem.GetLayers/GetLayer, inkLayerWrapper.GetLayerName/
// GetVirtualWindow/GetGameControllers, GameInstance.GetSystemRequestsHandler(), ScriptableService and
// GameInstance.GetScriptableServiceContainer().GetService (Codeware.Global.reds). Layer names are Codeware's ink layer
// class names (inkHUDLayer, inkGameNotificationsLayer, inkWaitingSignLayer); the first Apply logs every layer name the
// running game reports, as the in-game check.
// Never touched: inkWorldLayer, inkAdvertisementsLayer, inkStreetSignsLayer, inkSystemNotificationsLayer,
// inkPhotoModeLayer (and inkMenuLayer/inkLoadingLayer, which CyberblockRestyle only tints).

// The UI state that must outlive a single call: a ScriptableService, which Codeware creates once for the game and keeps
// across sessions.
public class CyberblockUiService extends ScriptableService {
  // The three layers are at opacity 0 because of us.
  public let hidden: Bool;
  // The layer names were logged once.
  public let layersLogged: Bool;
  // The exception that shows a layer right now ("" none).
  public let shownFor: String;
  // Cyberblock paused the game for Minecraft's Night City pause screen.
  public let pausedByUs: Bool;
  // A `pause` was refused because the DLL pump was not running (Menus.reds; logged once).
  public let pauseRefusedLogged: Bool;
  // The in-game menu controller (Menus.reds) and the one-shot bypass for the escape hatch.
  public let menuController: wref<gameuiInGameMenuGameController>;
  public let bypass: Bool;
  public let bypassSeenMenu: Bool;
  public let bypassWait: Int32;
  // The pass-through name Minecraft was told last (Restyle.reds).
  public let pass: CName;
  // Restyle.reds state: pump passes, where the next widget walk starts, the persistent-layer roots it tinted and their
  // own colours, and whether the loading screen controller was logged.
  public let restylePass: Int32;
  public let walkOffset: Int32;
  public let tintedRoots: array<wref<inkWidget>>;
  public let rootColors: array<HDRColor>;
  public let loadingLogged: Bool;
  // Notification controllers waiting for their data (Notifications.reds) and the pump passes each waited.
  public let pendingNotifications: array<wref<GenericNotificationController>>;
  public let pendingAges: array<Int32>;
  // The mappins of the last `reply atlas`, in reply order: `uiact track pin <index>` tracks one (Menus.reds).
  public let atlasPins: array<NewMappinID>;
  private let requests: wref<inkISystemRequestsHandler>;

  public static func Get() -> ref<CyberblockUiService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockUiService") as CyberblockUiService;
  }

  public func Requests() -> wref<inkISystemRequestsHandler> {
    if !IsDefined(this.requests) {
      this.requests = GameInstance.GetSystemRequestsHandler();
    }
    return this.requests;
  }

  // The game is paused by something that is not Cyberblock (a tutorial or shard popup, a Cyberpunk menu).
  public func ForeignPause() -> Bool {
    let requests = this.Requests();
    return IsDefined(requests) && requests.IsGamePaused() && !this.pausedByUs;
  }

  // A popup on the game-notifications layer that waits for input: its section 1.11 pass-through name (n"tutorial" or
  // n"other"), n"" for none; cls is its controller class (for the log).
  public func BlockingPopup(game: GameInstance, out cls: CName) -> CName {
    let layer = GameInstance.GetInkSystem().GetLayer(n"inkGameNotificationsLayer");
    if IsDefined(layer) {
      for c in layer.GetGameControllers() {
        if IsDefined(c) && IsDefined(c.GetRootWidget()) && c.GetRootWidget().IsVisible() {
          let kind = CyberblockUiService.PopupKind(c);
          if NotEquals(kind, n"") {
            cls = c.GetClassName();
            return kind;
          }
        }
      }
    }
    if GameInstance.GetTimeSystem(game).IsTimeDilationActive(n"UI_TutorialPopup") {
      cls = n"UI_TutorialPopup";
      return n"tutorial";
    }
    return n"";
  }

  private static func PopupKind(c: wref<inkIGameController>) -> CName {
    if c.IsA(n"TutorialPopupGameController") {
      let tutorial = c as TutorialPopupGameController;
      return IsDefined(tutorial) && tutorial.CyberblockWaits() ? n"tutorial" : n"";
    }
    if c.IsA(n"CodexPopupGameController") || c.IsA(n"ShardNotificationController") {
      return n"tutorial";
    }
    if c.IsA(n"PhoneMessagePopupGameController") || c.IsA(n"BaseModalListPopupGameController")
      || c.IsA(n"vehicleColorSelectorGameController") || c.IsA(n"FrameSwitcherMenuGameController")
      || c.IsA(n"TwintoneOverrideGameController") || c.IsA(n"GenericMessageNotification") {
      return n"other";
    }
    return n"";
  }
}

// A tutorial popup waits for input (closeAtInput) or freezes time (pauseGame); a plain hint does neither and stays
// hidden.
@addMethod(TutorialPopupGameController)
public func CyberblockWaits() -> Bool {
  return this.m_inputBlocked || this.m_gamePaused;
}

// Called by Cyberblock.dll (cb::ui::pump, game thread, up to 30 Hz, also while the game is paused, while Cyberblock is
// On or actions wait): drains the UI actions and keeps the ink layers hidden even while the DelaySystem tick stands
// still (pauses, menus). Menus.reds handles each action.
public abstract class CyberblockUi {
  public static func Pump() -> Void {
    let game = GetGameInstance();
    let system = CyberblockSystem.Get(game);
    let on = IsDefined(system) && system.UiOn();
    // The ink layers stay hidden (and notifications go to Minecraft) through saves: ink opacity is not saved.
    let inkOn = IsDefined(system) && system.InkOn();
    if IsDefined(system) {
      system.PumpSave();
    }
    let action = Cyberblock_NextUiAction();
    let guard = 0;
    while NotEquals(action, n"") && guard < 32 {
      CyberblockMenus.Act(game, action, on);
      action = Cyberblock_NextUiAction();
      guard += 1;
    }
    CyberblockMenus.UpdateBypass(game);
    if IsDefined(GetPlayer(game)) {
      CyberblockInk.Apply(game, inkOn);
      CyberblockNotifications.Poll(game, inkOn);
      CyberblockRestyle.Update(game, inkOn);
    }
  }
}

public abstract class CyberblockInk {
  // Every tick of CyberblockSystem and from CyberblockUi.Pump. on: Cyberblock is On (Active or Vehicle, saves included).
  public static func Apply(game: GameInstance, on: Bool) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) {
      return;
    }
    if !on {
      if s.hidden {
        CyberblockInk.RestoreFor(game, "off");
      }
      return;
    }
    if !s.layersLogged {
      s.layersLogged = true;
      let names = "";
      for layer in GameInstance.GetInkSystem().GetLayers() {
        names += (StrLen(names) > 0 ? ", " : "") + NameToString(layer.GetLayerName());
      }
      Cyberblock_Log(s"ui: ink layers \(names)");
    }
    let braindance = CyberblockInk.InBraindance(game);
    let foreignPause = s.ForeignPause();
    let cls: CName;
    let popup = s.BlockingPopup(game, cls);
    let menu = CyberblockDisable.InMenu(game);
    let reason = "";
    if braindance {
      reason = "braindance";
    } else {
      if NotEquals(popup, n"") {
        reason = s"blocking popup (\(NameToString(cls)))";
      } else {
        if foreignPause {
          reason = "game paused (popup)";
        } else {
          if menu {
            reason = "menu popups";
          }
        }
      }
    }
    let first = !s.hidden;
    CyberblockInk.Set(n"inkHUDLayer", !braindance, first);
    CyberblockInk.Set(n"inkGameNotificationsLayer", !foreignPause && Equals(popup, n"") && !menu, first);
    CyberblockInk.Set(n"inkWaitingSignLayer", true, first);
    s.hidden = true;
    if first {
      Cyberblock_Log("ui: hidden (on)");
    }
    if NotEquals(reason, s.shownFor) {
      if StrLen(reason) > 0 {
        Cyberblock_Log(s"ui: shown for \(reason)");
      } else {
        Cyberblock_Log("ui: hidden (on)");
      }
      s.shownFor = reason;
    }
  }

  // All three layers back to opacity 1 (Off, Lost, session ready and end, detach).
  public static func Restore(game: GameInstance) -> Void {
    CyberblockInk.RestoreFor(game, "restore");
  }

  public static func RestoreFor(game: GameInstance, why: String) -> Void {
    // A pass-through menu ends with Cyberblock On: Minecraft is told, the tinted layer roots get their colours back.
    CyberblockRestyle.Update(game, false);
    let s = CyberblockUiService.Get();
    if IsDefined(s) && !s.hidden {
      return;
    }
    CyberblockInk.Show(n"inkHUDLayer");
    CyberblockInk.Show(n"inkGameNotificationsLayer");
    CyberblockInk.Show(n"inkWaitingSignLayer");
    if IsDefined(s) {
      s.hidden = false;
      s.shownFor = "";
    }
    Cyberblock_Log(s"ui: restored (\(why))");
  }

  public static func InBraindance(game: GameInstance) -> Bool {
    let bb = GameInstance.GetBlackboardSystem(game).Get(GetAllBlackboardDefs().Braindance);
    return IsDefined(bb) && bb.GetBool(GetAllBlackboardDefs().Braindance.IsActive);
  }

  // hide: opacity 0, else 1 (an exception). first: the layer is hidden for the first time this On period, so a window
  // that an older build left invisible is made visible again (it is drawn at opacity 0 from now on).
  private static func Set(name: CName, hide: Bool, first: Bool) -> Void {
    let window = CyberblockInk.Window(name);
    if !IsDefined(window) {
      return;
    }
    let target = hide ? 0.0 : 1.0;
    if window.GetOpacity() != target {
      window.SetOpacity(target);
    }
    if first && !window.IsVisible() {
      window.SetVisible(true);
    }
  }

  private static func Show(name: CName) -> Void {
    let window = CyberblockInk.Window(name);
    if IsDefined(window) && window.GetOpacity() != 1.0 {
      window.SetOpacity(1.0);
    }
  }

  private static func Window(name: CName) -> wref<inkVirtualWindow> {
    let layer = GameInstance.GetInkSystem().GetLayer(name);
    if IsDefined(layer) {
      return layer.GetVirtualWindow();
    }
    return null;
  }
}
