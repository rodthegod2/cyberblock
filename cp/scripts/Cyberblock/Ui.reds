// Cyberpunk's 2D UI while Cyberblock is On (plan 2026-10-03-retest3-polish sections 1.4 and 1.11, task U1): the HUD,
// game-notification and waiting-sign ink layers stay alive (controllers, listeners, interaction and choice input keep
// running) but are drawn at opacity 0; Minecraft draws every piece of UI from the data Hud.reds and Notifications.reds
// stream. Nothing shows the Cyberpunk HUD because of prompts or choices any more.
//
// Exceptions (logged as `ui: shown for <reason>`): the game-notifications layer shows while the game is paused by
// something that is not Cyberblock (tutorial and shard popups wait for input there), the HUD layer during a braindance
// (its editor timeline); both are restyled by CyberblockRestyle and framed by Minecraft.
//
// 2.31 script API: inkWidget.GetOpacity/SetOpacity/IsVisible/SetVisible (abstractWidgets.swift), Braindance.IsActive
// (BraindanceBlackboardDef, blackboardDefinitions.swift), inkISystemRequestsHandler.IsGamePaused (orphans.swift).
// Codeware 1.20: GameInstance.GetInkSystem(), inkSystem.GetLayers/GetLayer, inkLayerWrapper.GetLayerName/
// GetVirtualWindow, GameInstance.GetSystemRequestsHandler(), ScriptableService and
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
}

// Called by Cyberblock.dll (cb::ui::pump, game thread, up to 30 Hz, also while the game is paused, while Cyberblock is
// On or actions wait): drains the UI actions and keeps the ink layers hidden even while the DelaySystem tick stands
// still (pauses, menus). Menus.reds handles each action.
public abstract class CyberblockUi {
  public static func Pump() -> Void {
    let game = GetGameInstance();
    let system = CyberblockSystem.Get(game);
    let on = IsDefined(system) && system.UiOn();
    let action = Cyberblock_NextUiAction();
    let guard = 0;
    while NotEquals(action, n"") && guard < 32 {
      CyberblockMenus.Act(game, action, on);
      action = Cyberblock_NextUiAction();
      guard += 1;
    }
    CyberblockMenus.UpdateBypass(game);
    if IsDefined(GetPlayer(game)) {
      CyberblockInk.Apply(game, on);
      CyberblockNotifications.Poll(game, on);
      CyberblockRestyle.Update(game, on);
    }
  }
}

public abstract class CyberblockInk {
  // Every tick of CyberblockSystem and from CyberblockUi.Pump. on: Cyberblock is On (Active or Vehicle, not saving).
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
    let reason = braindance ? "braindance" : (foreignPause ? "game paused (popup)" : "");
    let first = !s.hidden;
    CyberblockInk.Set(n"inkHUDLayer", !braindance, first);
    CyberblockInk.Set(n"inkGameNotificationsLayer", !foreignPause, first);
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

  // All three layers back to opacity 1 (Off, Lost, session end, detach, before saves).
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
