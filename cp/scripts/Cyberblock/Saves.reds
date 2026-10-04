// Night City's save and load lists as a Minecraft screen (megaplan 9-U1). Minecraft's "Night City saves" screen
// (SavesScreen.java) asks for the list and loads or makes a save through the existing `uiact` actions and `reply`
// message (protocol.md section 1.1 shapes, no new message):
//   - `uiact journalreq arg:"saves"` -> `reply` kind journal (the same request/reply pairing as the Holo-Table's
//     `atlasreq arg:"fasttravel"`). Group 0 is the status: hash 0, title "ok" or "unavailable: <why>", done = not ok,
//     no lines. Then one group per save, in the order Cyberpunk lists them, at most 127: hash = the save's index in
//     that list, title = its location (else its name), done = Cyberpunk marks it invalid; lines: hash 1 = the save's
//     name (the key `loadlast` sends back; Minecraft offers no load for a name over 64 bytes, the uiact arg
//     limit), hash 2 = "<type>|<level>|<seconds played>|<timestamp>|<life path>" (only
//     when its metadata came), hash 3 = the tracked job (only when it has one).
//   - `uiact loadlast arg:<the save's name> id:<its index>` loads that save: the list is asked for again first and
//     the save is found by its exact name (the index only while its name still matches), so a save made in
//     the meantime cannot shift the load onto another one. It then waits for that save's metadata (at most 45 pumps)
//     and loads only when Cyberpunk says it is valid (SaveMetadataInfo.isValid), the check Cyberpunk's own load menu
//     makes: a corrupt save, one from a newer version or one needing a missing DLC is never handed to LoadSavedGame.
//     `loadlast` without an arg is the old "Load Last Save".
//   - `uiact save arg:"new"` makes a new manual save; `save` without an arg is the old quick save.
//
// The list comes from the calls Cyberpunk's own load menu makes (LoadGameMenuGameController): RequestSavesForLoad,
// answered through the system requests handler's callbacks OnSavesForLoadReady(saves: array<String>) and one
// OnSaveMetadataReady(info: ref<SaveMetadataInfo>) per save. Those calls and both callback signatures are proven by
// compile_check; that the handler also answers a script object that is not a menu, outside the load menu, is only
// checkable in game. So the path is a runtime feature check: when no list comes within 90 UI pumps (3 s at the DLL's
// 30 Hz) the reply says "unavailable", Minecraft's screen points to "Night City Menu..." (Cyberpunk's own lists, the
// pass-through `saves`), and the log says which path is active, once per change:
//   'saves: Minecraft save list active (RequestSavesForLoad answered, <n> saves)'
//   'saves: pass-through (RequestSavesForLoad gave no answer in 3 s; Night City Menu... has the lists)'
// Metadata that has not all come 45 pumps after the list is sent as far as it came ('saves: metadata for <k> of <n>').
// Other log lines: 'saves: load "<name>" (index <i>)', 'saves: load "<name>" dropped (<why>)' (why: "no save list",
// "not in the list any more", "not valid", "not valid: no metadata in 1.5 s"), 'saves: new manual save requested'.
//
// 2.31 script API (NativeDB, redscript), each call proven by compile_check: inkISystemRequestsHandler
// RegisterToCallback/UnregisterFromCallback, RequestSavesForLoad, LoadSavedGame(saveId: Int32), ManualSave(saveName:
// String), PauseMenu's QuickSave path in Menus.reds; LoadGameMenuGameController's OnSavesForLoadReady and
// OnSaveMetadataReady signatures; SaveMetadataInfo saveIndex (Int32), locationName and trackedQuest (String), playTime
// and level (Double), timestamp (Uint64), saveType (inkSaveType), lifePath (inkLifePath), isValid (Bool);
// EnumValueToString, GetLocalizedText, GameInstance.IsSavingLocked; Codeware 1.20 ScriptableService and
// GameInstance.GetSystemRequestsHandler. ManualSave's argument: "ManualSave", the prefix the 2.31 scripts read in save
// names ("ManualSave-"); the new entry showing in the list is the in-game check, and "Quick save" stays as the proven
// way to save.

public class CyberblockSaveService extends ScriptableService {
  // A list was asked for and has not come (or its metadata is still coming).
  public let asking: Bool;
  public let gotList: Bool;
  // UI pumps since the request, and since the list came.
  public let waitPumps: Int32;
  public let metaPumps: Int32;
  // The request id of Minecraft's list request (-1 none) and a load waiting for the fresh list and then for that save's
  // metadata ("" none; loadFound is its index in the fresh list, -1 until the list came).
  public let listId: Int32;
  public let loadKey: String;
  public let loadIndex: Int32;
  public let loadFound: Int32;
  // The list and each save's metadata (null until it came), by index.
  public let names: array<String>;
  public let infos: array<ref<SaveMetadataInfo>>;
  public let metaCount: Int32;
  // The path verdict logged last.
  public let path: String;

  public static func Get() -> ref<CyberblockSaveService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockSaveService") as CyberblockSaveService;
  }

  // Asks Cyberpunk for its save list, unless a request is already out.
  public func Ask() -> Void {
    if this.asking && !this.gotList {
      return;
    }
    let requests = GameInstance.GetSystemRequestsHandler();
    if !IsDefined(requests) {
      this.Fail("no system requests handler");
      return;
    }
    this.asking = true;
    this.gotList = false;
    this.waitPumps = 0;
    this.metaPumps = 0;
    this.metaCount = 0;
    this.loadFound = -1;
    ArrayClear(this.names);
    ArrayClear(this.infos);
    // registered fresh each time: the handler may be a new one after a load
    requests.UnregisterFromCallback(n"OnSavesForLoadReady", this, n"OnSavesForLoadReady");
    requests.UnregisterFromCallback(n"OnSaveMetadataReady", this, n"OnSaveMetadataReady");
    requests.RegisterToCallback(n"OnSavesForLoadReady", this, n"OnSavesForLoadReady");
    requests.RegisterToCallback(n"OnSaveMetadataReady", this, n"OnSaveMetadataReady");
    requests.RequestSavesForLoad();
  }

  protected cb func OnSavesForLoadReady(saves: array<String>) -> Bool {
    // a list Cyberpunk's own load menu asked for is not ours
    if !this.asking || this.gotList {
      return false;
    }
    this.gotList = true;
    this.names = saves;
    ArrayResize(this.infos, ArraySize(saves));
    this.Verdict(s"Minecraft save list active (RequestSavesForLoad answered, \(ArraySize(saves)) saves)");
    if StrLen(this.loadKey) > 0 {
      this.FindLoad();
    }
    if this.listId >= 0 && ArraySize(saves) == 0 {
      this.Reply("ok");
    }
    this.Settle();
    return true;
  }

  protected cb func OnSaveMetadataReady(info: ref<SaveMetadataInfo>) -> Bool {
    if !this.asking || !this.gotList || !IsDefined(info) {
      return false;
    }
    let i = info.saveIndex;
    if i >= 0 && i < ArraySize(this.infos) && !IsDefined(this.infos[i]) {
      this.infos[i] = info;
      this.metaCount += 1;
      if StrLen(this.loadKey) > 0 && i == this.loadFound {
        this.LoadNow(info);
      }
      if this.metaCount >= ArraySize(this.names) && this.listId >= 0 {
        this.Reply("ok");
      }
      this.Settle();
    }
    return true;
  }

  // Nothing waits any more (no list request, no load): stop listening for this list.
  private func Settle() -> Void {
    if this.listId < 0 && StrLen(this.loadKey) == 0 {
      this.asking = false;
    }
  }

  // Each UI pump (CyberblockRestyle.Update): the timeouts of the feature check.
  public func Update() -> Void {
    if !this.asking {
      return;
    }
    if !this.gotList {
      this.waitPumps += 1;
      if this.waitPumps >= 90 {
        this.Fail("RequestSavesForLoad gave no answer in 3 s");
      }
      return;
    }
    this.metaPumps += 1;
    if this.metaPumps >= 45 {
      if this.listId >= 0 {
        Cyberblock_Log(s"saves: metadata for \(this.metaCount) of \(ArraySize(this.names))");
        this.Reply("ok");
      }
      if StrLen(this.loadKey) > 0 {
        // its validity is unknown: never hand it to LoadSavedGame
        this.DropLoad("not valid: no metadata in 1.5 s", "Night City could not check that save: load it through Night City Menu...");
      }
      this.asking = false;
    }
  }

  // No list: Minecraft's screen says why and points to the pass-through; a waiting load is dropped.
  private func Fail(why: String) -> Void {
    this.Verdict(s"pass-through (\(why); Night City Menu... has the lists)");
    if StrLen(this.loadKey) > 0 {
      this.DropLoad("no save list", "Night City did not list its saves: use Night City Menu...");
    }
    if this.listId >= 0 {
      this.Reply(s"unavailable: \(why)");
    }
    this.asking = false;
  }

  private func DropLoad(why: String, notice: String) -> Void {
    Cyberblock_Log(s"saves: load \"\(this.loadKey)\" dropped (\(why))");
    Cyberblock_Notify(n"warning", "Load", notice, 4.0);
    this.loadKey = "";
    this.loadFound = -1;
  }

  private func Verdict(path: String) -> Void {
    if NotEquals(path, this.path) {
      this.path = path;
      Cyberblock_Log(s"saves: \(path)");
    }
  }

  // The waiting load, on the fresh list: by its exact name, the index only while its name still matches. It then
  // waits for that save's metadata (OnSaveMetadataReady, or dropped by Update's timeout).
  private func FindLoad() -> Void {
    let key = this.loadKey;
    let found = -1;
    if this.loadIndex >= 0 && this.loadIndex < ArraySize(this.names) && Equals(this.names[this.loadIndex], key) {
      found = this.loadIndex;
    }
    let i = 0;
    while found < 0 && i < ArraySize(this.names) {
      if Equals(this.names[i], key) {
        found = i;
      }
      i += 1;
    }
    if found < 0 {
      this.DropLoad("not in the list any more", "That save is not in the list any more");
      return;
    }
    this.loadFound = found;
    if IsDefined(this.infos[found]) {
      this.LoadNow(this.infos[found]);
    }
  }

  // The waiting load, with its metadata: only a save Cyberpunk calls valid is loaded.
  private func LoadNow(info: ref<SaveMetadataInfo>) -> Void {
    let found = this.loadFound;
    if !info.isValid {
      this.DropLoad("not valid", "Night City cannot load that save");
      return;
    }
    this.loadKey = "";
    this.loadFound = -1;
    let requests = GameInstance.GetSystemRequestsHandler();
    let ui = CyberblockUiService.Get();
    if IsDefined(ui) {
      CyberblockMenus.Resume(ui, requests);
    }
    Cyberblock_Log(s"saves: load \"\(this.names[found])\" (index \(found))");
    if IsDefined(requests) {
      requests.LoadSavedGame(found);
    }
  }

  // The `reply` (see the header): the status group, then one group per save.
  private func Reply(status: String) -> Void {
    let id = this.listId;
    this.listId = -1;
    this.asking = StrLen(this.loadKey) > 0;
    Cyberblock_ReplyBegin(n"journal", id);
    Cyberblock_ReplyGroup(0u, status, n"other", false, NotEquals(status, "ok"));
    let i = 0;
    while i < ArraySize(this.names) && i < 127 {
      let info = i < ArraySize(this.infos) ? this.infos[i] : null;
      let title = this.names[i];
      if IsDefined(info) && StrLen(CyberblockSaves.Text(info.locationName)) > 0 {
        title = CyberblockSaves.Text(info.locationName);
      }
      Cyberblock_ReplyGroup(Cast<Uint32>(i), title, n"other", false, IsDefined(info) && !info.isValid);
      Cyberblock_ReplyLine(1u, this.names[i], 0);
      if IsDefined(info) {
        let type = EnumValueToString("inkSaveType", Cast<Int64>(EnumInt(info.saveType)));
        let life = EnumValueToString("inkLifePath", Cast<Int64>(EnumInt(info.lifePath)));
        let level = Cast<Int32>(info.level);
        let played = Cast<Int64>(info.playTime);
        Cyberblock_ReplyLine(2u, s"\(type)|\(level)|\(played)|\(info.timestamp)|\(life)", 0);
        let quest = CyberblockSaves.Text(info.trackedQuest);
        if StrLen(quest) > 0 {
          Cyberblock_ReplyLine(3u, quest, 0);
        }
      }
      i += 1;
    }
    Cyberblock_ReplyEnd();
  }
}

public abstract class CyberblockSaves {
  // `uiact journalreq arg:"saves"`.
  public static func List(id: Int32) -> Void {
    let s = CyberblockSaveService.Get();
    if !IsDefined(s) {
      return;
    }
    s.listId = id;
    s.Ask();
  }

  // `uiact loadlast arg:<name> id:<index>`.
  public static func Load(key: String, index: Int32) -> Void {
    let s = CyberblockSaveService.Get();
    if !IsDefined(s) {
      return;
    }
    s.loadKey = key;
    s.loadIndex = index;
    s.Ask();
  }

  // `uiact save arg:"new"`: a new manual save, refused like the quick save while saving is locked.
  public static func NewSave(game: GameInstance) -> Void {
    let requests = GameInstance.GetSystemRequestsHandler();
    let locks: array<gameSaveLock>;
    if !IsDefined(requests) || GameInstance.IsSavingLocked(game, locks) {
      Cyberblock_Notify(n"warning", "Saving is not possible right now", "", 3.0);
      return;
    }
    Cyberblock_Log("saves: new manual save requested");
    requests.ManualSave("ManualSave");
  }

  // From CyberblockRestyle.Update (each UI pump, also while paused).
  public static func Update() -> Void {
    let s = CyberblockSaveService.Get();
    if IsDefined(s) {
      s.Update();
    }
  }

  // A save's location or job: localized when it is a LocKey, as it is otherwise.
  public static func Text(text: String) -> String {
    return StrBeginsWith(text, "LocKey#") ? GetLocalizedText(text) : text;
  }
}
