// Pass-through menus restyled in Minecraft colours (plan 2026-10-03-retest3-polish section 1.11, task U6; F12).
//
// The Cyberpunk menus Minecraft cannot replace (vendors, ripperdoc, wardrobe, crafting bench, breach protocol, arcade,
// tutorial and shard popups, settings and save/load lists through the escape hatch, the death screen, loading screens,
// the braindance editor) stay Cyberpunk's, but while Cyberblock is On:
//   - Minecraft is told which one is up (Cyberblock_PassMenu with a section 1.11 name; n"" when it closes) and draws its
//     frame and title bar around it; the DLL stops compositing the Minecraft world over it (cb_menu);
//   - its widget tree is tinted in Minecraft colours: text white #FFFFFF, disabled grey #A0A0A0, hovered/selected
//     yellow #FFFF55; large background rectangles and frame images light grey #C6C6C6 at 0.92, thin lines dark grey
//     #555555, small panels slot grey #8B8B8B. Icons and item art are left alone. Fonts stay Cyberpunk's (Minecraft's
//     font cannot be copied, and cooking an original pixel font needs WolvenKit, which is not in the toolchain).
// The walk visits at most 3000 widgets and tints at most 600 per pass, five passes a second, rotating where it starts
// so that big menus are covered over a few passes. Menu widgets go away with their menu; the persistent layers (game
// notifications during a menu, a blocking popup or a foreign pause, the HUD during a braindance) are tinted at their
// controllers' root widgets only, and those tints are remembered and put back when the exception ends.
//
// Which menu: the classes of the game controllers on inkMenuLayer (Codeware inkLayerWrapper.GetGameControllers) while
// UI_System.IsInMenu, checked with IScriptable.IsA against the 2.31 script classes FullscreenVendorGameController,
// VendorHubMenuGameController, RipperDocGameController, WardrobeUIGameController, CraftingMainGameController,
// HackingMinigameGameController, MinigameController(Advanced), TutorialPopupGameController, TutorialMainController,
// CodexPopupGameController, PhoneMessagePopupGameController, SettingsMainGameController,
// BrightnessSettingsGameController, LoadGameMenuGameController, SaveGameMenuGameController, DeathMenuGameController;
// a blocking popup on the game-notifications layer (CyberblockUiService.BlockingPopup, Ui.reds) is `tutorial` or
// `other`, a game paused by something else than Cyberblock (popups waiting for input) `tutorial`, a braindance
// `braindance`, any game controller with a visible root on inkLoadingLayer `loading` (logged once with its class, as
// the in-game check of that guess), any other menu `other`. While a menu is open its own popups (quantity pickers,
// confirmations) show on the game-notifications layer and are root-tinted.
//
// 2.31 script API: inkWidget GetState/GetSize/GetName/GetTintColor/SetTintColor/IsVisible, inkCompoundWidget
// GetNumChildren/GetWidgetByIndex (abstractWidgets.swift), inkGameController.GetRootWidget (widgetController.swift),
// HDRColor (orphans.swift); widget state names from the 2.31 scripts (Default, Hover, Selected, Active, Disabled,
// Unavailable, Locked).

public abstract class CyberblockRestyle {
  // From CyberblockUi.Pump (up to 30 Hz, also while the game is paused). on: Cyberblock is On.
  public static func Update(game: GameInstance, on: Bool) -> Void {
    let s = CyberblockUiService.Get();
    if !IsDefined(s) {
      return;
    }
    let walk: array<wref<inkWidget>>;
    let tint: array<wref<inkWidget>>;
    let name = on ? CyberblockRestyle.Detect(game, s, walk, tint) : n"";
    if NotEquals(name, s.pass) {
      if Equals(name, n"") {
        Cyberblock_Log(s"ui: pass-through ended (\(NameToString(s.pass)))");
      } else {
        Cyberblock_Log(s"ui: pass-through \(NameToString(name))");
      }
      CyberblockRestyle.RestoreRoots(s);
      s.pass = name;
      Cyberblock_PassMenu(name);
    }
    if Equals(name, n"") {
      return;
    }
    s.restylePass += 1;
    if s.restylePass % 6 != 0 {
      return;
    }
    if ArraySize(walk) > 0 {
      CyberblockRestyle.Walk(s, walk);
    }
    for root in tint {
      CyberblockRestyle.TintRoot(s, root);
    }
  }

  // The section 1.11 pass-through name of what Cyberpunk shows now (n"" for nothing), the root widgets whose trees are
  // walked (menus, loading screens) and the persistent-layer roots that are only tinted.
  private static func Detect(game: GameInstance, s: ref<CyberblockUiService>, out walk: array<wref<inkWidget>>, out tint: array<wref<inkWidget>>) -> CName {
    let ink = GameInstance.GetInkSystem();
    let loading = ink.GetLayer(n"inkLoadingLayer");
    if IsDefined(loading) {
      for controller in loading.GetGameControllers() {
        if IsDefined(controller) && IsDefined(controller.GetRootWidget()) && controller.GetRootWidget().IsVisible() {
          if !s.loadingLogged {
            s.loadingLogged = true;
            Cyberblock_Log(s"ui: loading screen controller \(NameToString(controller.GetClassName()))");
          }
          ArrayPush(walk, controller.GetRootWidget());
          return n"loading";
        }
      }
    }
    if CyberblockDisable.InMenu(game) {
      let found = n"";
      let menu = ink.GetLayer(n"inkMenuLayer");
      if IsDefined(menu) {
        for controller in menu.GetGameControllers() {
          if IsDefined(controller) {
            let kind = CyberblockRestyle.MenuKind(controller);
            if Equals(found, n"") || (NotEquals(kind, n"other") && Equals(found, n"other")) {
              found = kind;
            }
            if IsDefined(controller.GetRootWidget()) {
              ArrayPush(walk, controller.GetRootWidget());
            }
          }
        }
      }
      // The menu's own popups (quantity pickers, confirmations) are on the game-notifications layer, shown by Ui.reds.
      CyberblockRestyle.Roots(n"inkGameNotificationsLayer", tint);
      return Equals(found, n"") ? n"other" : found;
    }
    let cls: CName;
    let popup = s.BlockingPopup(game, cls);
    if NotEquals(popup, n"") {
      CyberblockRestyle.Roots(n"inkGameNotificationsLayer", tint);
      return popup;
    }
    if s.ForeignPause() {
      CyberblockRestyle.Roots(n"inkGameNotificationsLayer", tint);
      return n"tutorial";
    }
    if CyberblockInk.InBraindance(game) {
      CyberblockRestyle.Roots(n"inkHUDLayer", tint);
      return n"braindance";
    }
    return n"";
  }

  private static func MenuKind(c: wref<inkIGameController>) -> CName {
    if c.IsA(n"RipperDocGameController") {
      return n"ripperdoc";
    }
    if c.IsA(n"FullscreenVendorGameController") || c.IsA(n"VendorHubMenuGameController") {
      return n"vendor";
    }
    if c.IsA(n"WardrobeUIGameController") {
      return n"wardrobe";
    }
    if c.IsA(n"CraftingMainGameController") {
      return n"crafting";
    }
    if c.IsA(n"HackingMinigameGameController") {
      return n"breach";
    }
    if c.IsA(n"MinigameController") || c.IsA(n"MinigameControllerAdvanced") {
      return n"arcade";
    }
    if c.IsA(n"TutorialPopupGameController") || c.IsA(n"TutorialMainController") || c.IsA(n"CodexPopupGameController") || c.IsA(n"PhoneMessagePopupGameController") {
      return n"tutorial";
    }
    if c.IsA(n"SettingsMainGameController") || c.IsA(n"BrightnessSettingsGameController") {
      return n"settings";
    }
    if c.IsA(n"LoadGameMenuGameController") || c.IsA(n"SaveGameMenuGameController") {
      return n"saves";
    }
    if c.IsA(n"DeathMenuGameController") {
      return n"death";
    }
    return n"other";
  }

  private static func Roots(layerName: CName, out roots: array<wref<inkWidget>>) -> Void {
    let layer = GameInstance.GetInkSystem().GetLayer(layerName);
    if !IsDefined(layer) {
      return;
    }
    for controller in layer.GetGameControllers() {
      if IsDefined(controller) && IsDefined(controller.GetRootWidget()) {
        ArrayPush(roots, controller.GetRootWidget());
      }
    }
  }

  // A persistent layer's root: tinted light grey once, its own colour remembered for RestoreRoots.
  private static func TintRoot(s: ref<CyberblockUiService>, root: wref<inkWidget>) -> Void {
    if !IsDefined(root) || ArrayContains(s.tintedRoots, root) || ArraySize(s.tintedRoots) >= 64 {
      return;
    }
    ArrayPush(s.tintedRoots, root);
    ArrayPush(s.rootColors, root.GetTintColor());
    root.SetTintColor(new HDRColor(0.78, 0.78, 0.78, 1.0));
  }

  public static func RestoreRoots(s: ref<CyberblockUiService>) -> Void {
    let i = 0;
    while i < ArraySize(s.tintedRoots) {
      if IsDefined(s.tintedRoots[i]) {
        s.tintedRoots[i].SetTintColor(s.rootColors[i]);
      }
      i += 1;
    }
    ArrayClear(s.tintedRoots);
    ArrayClear(s.rootColors);
  }

  // Breadth-first over the menu's widgets: at most 3000 visited and 600 tinted, starting the tinting at a rotating
  // offset so that the next passes reach the rest of a big menu.
  private static func Walk(s: ref<CyberblockUiService>, roots: array<wref<inkWidget>>) -> Void {
    let queue: array<wref<inkWidget>> = roots;
    let head = 0;
    let visited = 0;
    let tinted = 0;
    let skip = s.walkOffset;
    while head < ArraySize(queue) && visited < 3000 && tinted < 600 {
      let widget = queue[head];
      head += 1;
      if IsDefined(widget) && widget.IsVisible() {
        visited += 1;
        if skip > 0 {
          skip -= 1;
        } else {
          if CyberblockRestyle.Tint(widget) {
            tinted += 1;
          }
        }
        let compound = widget as inkCompoundWidget;
        if IsDefined(compound) {
          let n = compound.GetNumChildren();
          let i = 0;
          while i < n {
            ArrayPush(queue, compound.GetWidgetByIndex(i));
            i += 1;
          }
        }
      }
    }
    // Next pass starts where this one stopped; past the end it wraps to the top.
    s.walkOffset = (head < ArraySize(queue) && tinted >= 600) ? visited : 0;
  }

  // Minecraft colours for one widget; true when it was tinted.
  private static func Tint(w: wref<inkWidget>) -> Bool {
    if w.IsA(n"inkText") {
      let state = w.GetState();
      if Equals(state, n"Disabled") || Equals(state, n"Unavailable") || Equals(state, n"Locked") || Equals(state, n"DisabledActive") {
        w.SetTintColor(new HDRColor(0.627, 0.627, 0.627, 1.0));
      } else {
        if Equals(state, n"Hover") || Equals(state, n"Selected") || Equals(state, n"Active") || Equals(state, n"Press") {
          w.SetTintColor(new HDRColor(1.0, 1.0, 0.333, 1.0));
        } else {
          w.SetTintColor(new HDRColor(1.0, 1.0, 1.0, 1.0));
        }
      }
      return true;
    }
    let decorImage = w.IsA(n"inkImage") && CyberblockRestyle.DecorName(w.GetName());
    if !w.IsA(n"inkRectangle") && !decorImage {
      return false;
    }
    let size = w.GetSize();
    let thin = MinF(size.X, size.Y);
    if thin > 0.0 && thin <= 4.0 {
      w.SetTintColor(new HDRColor(0.333, 0.333, 0.333, 1.0));
    } else {
      if size.X * size.Y >= 40000.0 {
        w.SetTintColor(new HDRColor(0.776, 0.776, 0.776, 0.92));
      } else {
        w.SetTintColor(new HDRColor(0.545, 0.545, 0.545, 1.0));
      }
    }
    return true;
  }

  // Frame and background images (by their widget names); everything else (icons, item art) is left alone.
  private static func DecorName(name: CName) -> Bool {
    let text = StrLower(NameToString(name));
    return StrContains(text, "bg") || StrContains(text, "background") || StrContains(text, "frame")
      || StrContains(text, "border") || StrContains(text, "fill") || StrContains(text, "panel") || StrContains(text, "line");
  }
}
