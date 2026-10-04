// A host's Night City vendors and ripperdocs as true Minecraft screens (megaplan 7-V1; sections 1.9 `reply kind:"vendor"`
// and 1.16: the host pays with its real Cyberpunk money).
//
// When the host opens a vendor or a ripperdoc while Cyberblock is On and Minecraft is linked, Cyberpunk's vendor hub
// starts as usual (VendorHubMenuGameController.OnSetUserData); right after it, the stock is read and sent to Minecraft as
// an unrequested `reply kind:"vendor"` (pin 0 = the vendor, kind ripperdoc|vendor and its shop name; one group per stock
// category, kind buy + done:false the vendor's stock, kind sell + done:true V's items it takes; lines: hash = the item's
// TweakDB hash, text = its name, state = its unit price). This is the one vendor contract of Phase 7 (megaplan 1.8/1.9
// amendment 2026-10-04): nothing asks for the reply (no `vendorreq`), and buy/sell are `uiact`s, never DLL events.
// Minecraft opens its vendor screen for it; once Minecraft reports a screen open
// (Cyberblock_ScreenOpen) Cyberpunk's own hub is closed, so input goes to Minecraft. If Minecraft opens nothing within
// 1.5 s (no screen, an older mod or DLL that dropped the reply) Cyberpunk's screen simply stays: the pass-through.
// `uiact buy|sell` (hash = item hash, id = the line's index on its side, arg = the quantity) trade through the vendor's
// own VendorDataManager (BuyItemFromVendor / SellItemToVendor: MarketSystem's BuyRequest / SellRequest, the vanilla
// path: Cyberpunk moves the money); 0.3 s later the result is told as a toast and the stock is sent again. A buy keeps
// vanilla's rules first: a vendor ware whose availability prereq (Street Cred, level) or a ripperdoc's "must be wearable"
// is not met is sent as "<name> (locked)" and refused ("Requirements not met"), and the quantity is capped as the hub's
// GetMaxQuantity does (1 grenade, injector or inhaler; ammo up to V's cap, "Ammo full" at it).
// `uiact cpmenu arg:"vendor"` (the screen's "Night City vendor..." button) reopens Cyberpunk's own hub for the same vendor
// once (UISystem.RequestVendorMenu with the hub's own panel data), for installing cyberware and buyback.
//
// Pass-through (Cyberpunk's screen, logged as `vendors: <name> pass-through (<why>)`): a stash, the prologue ripperdoc
// (Viktor before q001_ripperdoc_done) and the ripperdoc tutorials (mq048, the Phantom Liberty start), a quest lock
// (LockInHubMenu), a Minecraft screen already open, the vendor not streamed, and any vendor whose stock reads empty.
// V seated in the ripperdoc's chair or talking in the vendor's dialogue scene (a workspot or scene tier 2-5) is the
// hub's own state, not a block. Phase 0 (f) is not confirmed in game yet: the stock read is the runtime feature check,
// and every verdict change logs `vendors: path: <minecraft screens|pass-through> (...)`.
//
// 2.31 script API (NativeDB, redscript; the 2.31 scripts the compile checker uses): VendorHubMenuGameController
// OnSetUserData/m_VendorDataManager/m_isRipperdoc/m_isRipperdocViktorTutorial/m_player/CloseVendor
// (vendorHubMenuGameController.swift); VendorDataManager GetVendorInstance/GetVendorName/GetVendorInventoryItems/
// GetRipperDocItems/GetItemsPlayerCanSellFast/CanPlayerSellItem/GetBuyingPrice/GetSellingPrice/BuyItemFromVendor/
// SellItemToVendor/GetLocalPlayerCurrencyAmount (UI/data/vendor.swift); VendorGameItemData.gameItemData/itemStack,
// SItemStack.isAvailable (Vendor.PlayerCanBuy, vendor.swift), EquipmentSystem.GetInstance/GetPlayerData and
// EquipmentSystemPlayerData.IsEquippable (equipmentSystem.swift), RPGManager.GetItemRecord, gameItemData
// HasTag/GetStatValueByType, TransactionSystem.GetItemQuantity,
// MarketSystem.GetVendorMoney (marketSystem.swift); VendorPanelData.data, UISystem.RequestVendorMenu (uiSystem.swift);
// gameItemData GetID/GetQuantity, ItemID.GetTDBID/HasFlag, TDBID.ToNumber, RPGManager.GetItemCategory,
// UIItemsHelper.GetItemName, StatusEffectSystem.ObjectHasStatusEffectWithTag, QuestsSystem.GetFact (orphans.swift and the
// files named).

public class CyberblockVendorService extends ScriptableService {
  // 0 none, 1 the reply went out and Cyberpunk's hub waits for Minecraft's screen, 2 Minecraft's screen is the vendor.
  public let phase: Int32;
  // Pump passes waited in phase 1; pump passes Minecraft's screen was seen closed in phase 2.
  public let wait: Int32;
  public let hub: wref<VendorHubMenuGameController>;
  public let manager: ref<VendorDataManager>;
  public let panel: ref<VendorPanelData>;
  public let ripperdoc: Bool;
  public let name: String;
  // +1 for every vendor taken over: the reply id.
  public let session: Int32;
  // Both sides in reply order (by category): the items, their line texts, unit prices and categories.
  public let buyItems: array<ItemID>;
  public let buyTexts: array<String>;
  public let buyPrices: array<Int32>;
  public let buyCats: array<Int32>;
  // false: the vendor's availability prereq (Street Cred, level) or a ripperdoc's "must be wearable" is not met yet.
  public let buyOk: array<Bool>;
  public let sellItems: array<ItemID>;
  public let sellTexts: array<String>;
  public let sellPrices: array<Int32>;
  public let sellCats: array<Int32>;
  // The next vendor hub that opens is Cyberpunk's (the escape hatch).
  public let bypassOnce: Bool;
  // The trade waiting for its result: pump passes left, what, how many, buy or sell, and the money before it.
  public let settleIn: Int32;
  public let tradeName: String;
  public let tradeCount: Int32;
  public let tradeBuy: Bool;
  public let moneyBefore: Int32;
  // The last `vendors: path:` verdict logged.
  public let path: String;
  // The last `fast travel:` verdict and fast-travel reply logged (Menus.reds, the Holo-Table's fast travel of package 7V).
  public let fastTravelLog: String;
  public let fastTravelReplyLog: String;
  // A fast travel picked while V is parked in the hub (Menus.reds): it runs once the park is released (hub exit).
  public let travelPending: Bool;
  public let travelIndex: Int32;
  public let travelName: String;
  // Pump passes since the park was released.
  public let travelWait: Int32;

  public static func Get() -> ref<CyberblockVendorService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockVendorService") as CyberblockVendorService;
  }
}

public abstract class CyberblockVendors {
  // Stock categories in screen order (titles are Cyberblock's own words).
  public static func CategoryCount() -> Int32 {
    return 7;
  }

  public static func CategoryTitle(cat: Int32) -> String {
    switch cat {
      case 0:
        return "Weapons";
      case 1:
        return "Cyberware";
      case 2:
        return "Mods and parts";
      case 3:
        return "Grenades and gadgets";
      case 4:
        return "Consumables";
      case 5:
        return "Clothing";
      default:
        return "Other";
    }
  }

  public static func Category(id: ItemID) -> Int32 {
    switch RPGManager.GetItemCategory(id) {
      case gamedataItemCategory.Weapon:
        return 0;
      case gamedataItemCategory.Cyberware:
        return 1;
      case gamedataItemCategory.Part:
      case gamedataItemCategory.WeaponMod:
        return 2;
      case gamedataItemCategory.Gadget:
        return 3;
      case gamedataItemCategory.Consumable:
        return 4;
      case gamedataItemCategory.Clothing:
        return 5;
      default:
        return 6;
    }
  }

  // The item's hash on the wire: the low 32 bits of its TweakDB id (the record name's hash).
  public static func Hash(id: ItemID) -> Uint32 {
    return Cast<Uint32>(TDBID.ToNumber(ItemID.GetTDBID(id)) % 4294967296ul);
  }

  // From the VendorHubMenuGameController wrapper: Cyberpunk's vendor hub has just been set up.
  public static func Opened(hub: ref<VendorHubMenuGameController>, manager: ref<VendorDataManager>, panel: ref<VendorPanelData>,
    ripperdoc: Bool, viktorTutorial: Bool, player: ref<PlayerPuppet>) -> Void {
    let s = CyberblockVendorService.Get();
    if !IsDefined(s) || !IsDefined(player) || !IsDefined(manager) {
      return;
    }
    let game = player.GetGame();
    let system = CyberblockSystem.Get(game);
    if !IsDefined(system) || !system.UiOn() || !Cyberblock_IsLinked() {
      s.bypassOnce = false;
      return;
    }
    if !IsDefined(panel) {
      return; // a stash: Cyberpunk's own screen
    }
    let name = manager.GetVendorName();
    let why = CyberblockVendors.PassReason(game, player, s, manager, ripperdoc, viktorTutorial);
    if StrLen(why) > 0 {
      Cyberblock_Log(s"vendors: \(name) pass-through (\(why))");
      return;
    }
    s.manager = manager;
    s.ripperdoc = ripperdoc;
    s.name = name;
    if !CyberblockVendors.Read(s, player) {
      CyberblockVendors.Path(s, "pass-through (Phase 0 f fallback: no stock readable through MarketSystem.GetVendorItemsForSale)");
      Cyberblock_Log(s"vendors: \(name) pass-through (stock not readable)");
      s.manager = null;
      return;
    }
    CyberblockVendors.Path(s, "minecraft screens (stock readable through MarketSystem.GetVendorItemsForSale)");
    s.panel = panel;
    s.hub = hub;
    s.session += 1;
    s.phase = 1;
    s.wait = 0;
    s.settleIn = 0;
    CyberblockVendors.Send(s);
    Cyberblock_Log(s"vendors: \(name) (\(ripperdoc ? "ripperdoc" : "vendor")) -> minecraft: \(ArraySize(s.buyItems)) to buy, \(ArraySize(s.sellItems)) to sell");
  }

  // Why this vendor stays Cyberpunk's own screen ("" = Minecraft takes it).
  private static func PassReason(game: GameInstance, player: ref<PlayerPuppet>, s: ref<CyberblockVendorService>,
    manager: ref<VendorDataManager>, ripperdoc: Bool, viktorTutorial: Bool) -> String {
    if s.bypassOnce {
      s.bypassOnce = false;
      return "Night City vendor... asked for it";
    }
    let quests = GameInstance.GetQuestsSystem(game);
    if viktorTutorial || (ripperdoc && Equals(manager.GetVendorID(), t"Vendors.wat_lch_ripperdoc_01") && quests.GetFact(n"q001_ripperdoc_done") == 0) {
      return "the prologue ripperdoc";
    }
    let tutorial = (quests.GetFact(n"mq048_active") > 0 && quests.GetFact(n"mq048_done") == 0) || quests.GetFact(n"ep1_standalone") > 0;
    if ripperdoc && tutorial && quests.GetFact(n"ep1_ripperdoc_tutorial_seen") == 0 {
      return "a ripperdoc tutorial";
    }
    if StatusEffectSystem.ObjectHasStatusEffectWithTag(player, n"LockInHubMenu") {
      return "quest lock";
    }
    // No CyberblockEffects.LockReason here: a ripperdoc seats V in its chair (a workspot) and many vendors trade inside
    // their dialogue scene, so the hub's own workspot or scene is no block (7V review 2026-10-04); quest-critical vendors
    // carry LockInHubMenu or the facts above.
    if !IsDefined(manager.GetVendorInstance()) {
      return "the vendor is not streamed";
    }
    if Cyberblock_ScreenOpen() {
      return "a Minecraft screen is open";
    }
    return "";
  }

  private static func Path(s: ref<CyberblockVendorService>, path: String) -> Void {
    if NotEquals(s.path, path) {
      s.path = path;
      Cyberblock_Log(s"vendors: path: \(path)");
    }
  }

  // Reads both sides into the service in reply order; false when the vendor has nothing readable to buy or sell.
  private static func Read(s: ref<CyberblockVendorService>, player: ref<PlayerPuppet>) -> Bool {
    ArrayClear(s.buyItems);
    ArrayClear(s.buyTexts);
    ArrayClear(s.buyPrices);
    ArrayClear(s.buyCats);
    ArrayClear(s.buyOk);
    ArrayClear(s.sellItems);
    ArrayClear(s.sellTexts);
    ArrayClear(s.sellPrices);
    ArrayClear(s.sellCats);
    let manager = s.manager;
    if !IsDefined(manager) || !IsDefined(manager.GetVendorInstance()) {
      return false;
    }
    let stock: array<ref<VendorGameItemData>> = manager.GetVendorInventoryItems();
    if s.ripperdoc {
      for item in manager.GetRipperDocItems() {
        ArrayPush(stock, item);
      }
    }
    let ids: array<ItemID>;
    let data: array<wref<gameItemData>>;
    let ok: array<Bool>;
    for item in stock {
      if IsDefined(item) && IsDefined(item.gameItemData) && !ArrayContains(ids, item.gameItemData.GetID()) {
        ArrayPush(ids, item.gameItemData.GetID());
        ArrayPush(data, item.gameItemData);
        ArrayPush(ok, CyberblockVendors.Buyable(player, item));
      }
    }
    CyberblockVendors.Sort(manager, ids, data, ok, true, 320, s.buyItems, s.buyTexts, s.buyPrices, s.buyCats, s.buyOk);
    let sellIds: array<ItemID>;
    let sellData: array<wref<gameItemData>>;
    let sellOk: array<Bool>;
    for item in manager.GetItemsPlayerCanSellFast(player, true) {
      if IsDefined(item) && !ItemID.HasFlag(item.GetID(), gameEItemIDFlag.Preview) && manager.CanPlayerSellItem(item.GetID()) && !ArrayContains(sellIds, item.GetID()) {
        ArrayPush(sellIds, item.GetID());
        ArrayPush(sellData, item);
        ArrayPush(sellOk, true);
      }
    }
    let sellOkSorted: array<Bool>;
    CyberblockVendors.Sort(manager, sellIds, sellData, sellOk, false, 190, s.sellItems, s.sellTexts, s.sellPrices, s.sellCats, sellOkSorted);
    return ArraySize(s.buyItems) > 0 || ArraySize(s.sellItems) > 0;
  }

  // Vanilla's buy rules the hub screens check before BuyItemFromVendor: the vendor ware's availability prereq
  // (Vendor.PlayerCanBuy sets itemStack.isAvailable; ripperdoc.swift refuses the click) and a ripperdoc's
  // MustBeWearableToPurchase (V must be able to equip it). MarketSystem's BuyRequest builds a fresh stack without the flag,
  // so the check is ours.
  private static func Buyable(player: ref<PlayerPuppet>, item: ref<VendorGameItemData>) -> Bool {
    if !item.itemStack.isAvailable {
      return false;
    }
    if !item.gameItemData.HasTag(n"MustBeWearableToPurchase") {
      return true;
    }
    let equipment = EquipmentSystem.GetInstance(player);
    return IsDefined(equipment) && equipment.GetPlayerData(player).IsEquippable(item.gameItemData);
  }

  // Vanilla GetMaxQuantity (fullscreenVendorGameController.swift): one grenade, injector or inhaler per purchase, ammo up to
  // the Quantity cap less what V holds (0 = ammo full), else the vendor's stack.
  private static func BuyLimit(player: ref<PlayerPuppet>, id: ItemID, itemData: wref<gameItemData>) -> Int32 {
    let vendorQuantity = itemData.GetQuantity();
    let tags = RPGManager.GetItemRecord(id).Tags();
    if ArrayContains(tags, n"Grenade") || ArrayContains(tags, n"Injector") || ArrayContains(tags, n"Inhaler") {
      return 1;
    }
    if !ArrayContains(tags, n"Ammo") {
      return Max(1, vendorQuantity);
    }
    let cap = Cast<Int32>(itemData.GetStatValueByType(gamedataStatType.Quantity));
    let held = GameInstance.GetTransactionSystem(player.GetGame()).GetItemQuantity(player, id);
    return Min(cap - held, vendorQuantity);
  }

  // One side, category by category (the reply order the screen indexes by), at most `cap` items. A line that is not ok
  // (requirements not met) says "(locked)".
  private static func Sort(manager: ref<VendorDataManager>, ids: array<ItemID>, data: array<wref<gameItemData>>, ok: array<Bool>, buy: Bool,
    cap: Int32, out items: array<ItemID>, out texts: array<String>, out prices: array<Int32>, out cats: array<Int32>, out oks: array<Bool>) -> Void {
    let cat = 0;
    while cat < CyberblockVendors.CategoryCount() {
      let i = 0;
      while i < ArraySize(ids) && ArraySize(items) < cap {
        if CyberblockVendors.Category(ids[i]) == cat {
          let name = UIItemsHelper.GetItemName(ItemID.GetTDBID(ids[i]), data[i]);
          if StrLen(name) == 0 {
            name = TDBID.ToStringDEBUG(ItemID.GetTDBID(ids[i]));
          }
          let count = data[i].GetQuantity();
          if count > 1 {
            name = s"\(name) x\(count)";
          }
          if !ok[i] {
            name = s"\(name) (locked)";
          }
          ArrayPush(items, ids[i]);
          ArrayPush(texts, name);
          ArrayPush(oks, ok[i]);
          ArrayPush(prices, Max(0, buy ? manager.GetBuyingPrice(ids[i]) : manager.GetSellingPrice(ids[i])));
          ArrayPush(cats, cat);
        }
        i += 1;
      }
      cat += 1;
    }
  }

  // The `reply kind:"vendor"` of the current lists.
  private static func Send(s: ref<CyberblockVendorService>) -> Void {
    let vendor = IsDefined(s.manager) ? s.manager.GetVendorInstance() : null;
    Cyberblock_ReplyBegin(n"vendor", s.session);
    if IsDefined(vendor) {
      Cyberblock_ReplyPin(vendor.GetWorldPosition(), s.ripperdoc ? n"ripperdoc" : n"vendor", s.name, -1, "");
    }
    CyberblockVendors.SendSide(s.buyItems, s.buyTexts, s.buyPrices, s.buyCats, false);
    CyberblockVendors.SendSide(s.sellItems, s.sellTexts, s.sellPrices, s.sellCats, true);
    Cyberblock_ReplyEnd();
  }

  private static func SendSide(items: array<ItemID>, texts: array<String>, prices: array<Int32>, cats: array<Int32>, sell: Bool) -> Void {
    let last = -1;
    let i = 0;
    while i < ArraySize(items) {
      if cats[i] != last {
        last = cats[i];
        Cyberblock_ReplyGroup(Cast<Uint32>(last), CyberblockVendors.CategoryTitle(last), sell ? n"sell" : n"buy", false, sell);
      }
      Cyberblock_ReplyLine(CyberblockVendors.Hash(items[i]), texts[i], prices[i]);
      i += 1;
    }
  }

  // From CyberblockRestyle.Update (each UI pump, up to 30 Hz, also while paused). on: Cyberblock is On.
  public static func Update(game: GameInstance, on: Bool) -> Void {
    let s = CyberblockVendorService.Get();
    if !IsDefined(s) || s.phase == 0 {
      return;
    }
    if !on || !Cyberblock_IsLinked() {
      CyberblockVendors.End(s, "Cyberblock off or Minecraft gone");
      return;
    }
    if s.phase == 1 {
      s.wait += 1;
      if !IsDefined(s.hub) {
        CyberblockVendors.End(s, "Night City's vendor screen closed first");
        return;
      }
      if Cyberblock_ScreenOpen() {
        s.hub.CyberblockClose();
        s.phase = 2;
        s.wait = 0;
        Cyberblock_Log(s"vendors: \(s.name) -> minecraft screen open, Night City's closed");
        return;
      }
      if s.wait > 45 {
        Cyberblock_Log(s"vendors: \(s.name) pass-through (Minecraft opened no vendor screen within 1.5 s)");
        s.phase = 0;
        s.manager = null;
      }
      return;
    }
    if s.settleIn > 0 {
      s.settleIn -= 1;
      if s.settleIn == 0 {
        CyberblockVendors.Settle(game, s);
      }
      return;
    }
    if Cyberblock_ScreenOpen() {
      s.wait = 0;
    } else {
      s.wait += 1;
      if s.wait > 15 {
        CyberblockVendors.End(s, "Minecraft's vendor screen closed");
      }
    }
  }

  private static func End(s: ref<CyberblockVendorService>, why: String) -> Void {
    if s.phase != 0 {
      Cyberblock_Log(s"vendors: \(s.name) ended (\(why))");
    }
    s.phase = 0;
    s.wait = 0;
    s.settleIn = 0;
    s.manager = null;
  }

  // `uiact buy` / `uiact sell` (Menus.reds): hash and index name the line, arg the quantity.
  public static func Trade(game: GameInstance, buy: Bool, hash: Uint32, index: Int32, arg: String) -> Void {
    let s = CyberblockVendorService.Get();
    let what = buy ? "buy" : "sell";
    if !IsDefined(s) || s.phase != 2 || !IsDefined(s.manager) || s.settleIn > 0 {
      Cyberblock_Log(s"vendors: \(what) ignored (no vendor screen, or a trade is still settling)");
      return;
    }
    let player = GetPlayer(game);
    let vendor = s.manager.GetVendorInstance();
    if !IsDefined(player) || !IsDefined(vendor) || Vector4.Distance(player.GetWorldPosition(), vendor.GetWorldPosition()) > 12.0 {
      Cyberblock_Notify(n"warning", s.name, "Too far from the vendor", 3.0);
      CyberblockVendors.End(s, "V left the vendor");
      return;
    }
    let i = CyberblockVendors.Find(buy ? s.buyItems : s.sellItems, hash, index);
    if i < 0 {
      Cyberblock_Log(s"vendors: \(what) \(hash) at \(index) not in the current list; sending it again");
      CyberblockVendors.Refresh(s, player);
      return;
    }
    let id = buy ? s.buyItems[i] : s.sellItems[i];
    let owner: wref<GameObject> = vendor;
    if !buy {
      owner = player;
    }
    let itemData = GameInstance.GetTransactionSystem(game).GetItemData(owner, id);
    if !IsDefined(itemData) {
      CyberblockVendors.Refresh(s, player);
      return;
    }
    let price = buy ? s.buyPrices[i] : s.sellPrices[i];
    let most = Max(1, itemData.GetQuantity());
    if buy {
      if !s.buyOk[i] {
        Cyberblock_Notify(n"warning", s.name, "Requirements not met", 3.0);
        Cyberblock_Log(s"vendors: buy \(s.buyTexts[i]) refused (requirements not met)");
        return;
      }
      most = CyberblockVendors.BuyLimit(player, id, itemData);
      if most < 1 {
        Cyberblock_Notify(n"warning", s.name, "Ammo full", 3.0);
        return;
      }
    }
    let count = Clamp(StringToInt(arg, 1), 1, most);
    let money = s.manager.GetLocalPlayerCurrencyAmount();
    if buy {
      if price > 0 {
        count = Min(count, money / price);
      }
      if count < 1 {
        Cyberblock_Notify(n"warning", s.name, "Not enough eddies", 3.0);
        return;
      }
      s.manager.BuyItemFromVendor(itemData, count);
    } else {
      if !s.manager.CanPlayerSellItem(id) {
        CyberblockVendors.Refresh(s, player);
        return;
      }
      if price > 0 {
        count = Min(count, MarketSystem.GetVendorMoney(vendor) / price);
      }
      if count < 1 {
        Cyberblock_Notify(n"warning", s.name, "The vendor cannot pay for that", 3.0);
        return;
      }
      s.manager.SellItemToVendor(itemData, count);
    }
    let text = buy ? s.buyTexts[i] : s.sellTexts[i];
    s.tradeName = text;
    s.tradeCount = count;
    s.tradeBuy = buy;
    s.moneyBefore = money;
    s.settleIn = 9;
    Cyberblock_Log(s"vendors: \(what) \(text) x\(count) at \(price) each");
  }

  // The line at `index` when its hash still matches, else the first line with that hash (-1 none).
  private static func Find(items: array<ItemID>, hash: Uint32, index: Int32) -> Int32 {
    if index >= 0 && index < ArraySize(items) && CyberblockVendors.Hash(items[index]) == hash {
      return index;
    }
    let i = 0;
    while i < ArraySize(items) {
      if CyberblockVendors.Hash(items[i]) == hash {
        return i;
      }
      i += 1;
    }
    return -1;
  }

  // 0.3 s after a trade: the money tells whether Cyberpunk made it; then the fresh lists.
  private static func Settle(game: GameInstance, s: ref<CyberblockVendorService>) -> Void {
    let player = GetPlayer(game);
    if !IsDefined(s.manager) || !IsDefined(player) {
      return;
    }
    let money = s.manager.GetLocalPlayerCurrencyAmount();
    let what = s.tradeCount > 1 ? s"\(s.tradeName) (x\(s.tradeCount))" : s.tradeName;
    if s.tradeBuy && money < s.moneyBefore {
      Cyberblock_Notify(n"money", s"Bought \(what)", s"-\(s.moneyBefore - money) eddies", 3.0);
    } else {
      if !s.tradeBuy && money > s.moneyBefore {
        Cyberblock_Notify(n"money", s"Sold \(what)", s"+\(money - s.moneyBefore) eddies", 3.0);
      } else {
        Cyberblock_Notify(n"warning", s.name, s.tradeBuy ? s"Could not buy \(what)" : s"Could not sell \(what)", 3.0);
        Cyberblock_Log(s"vendors: \(s.tradeBuy ? "buy" : "sell") \(what) made no money change");
      }
    }
    CyberblockVendors.Refresh(s, player);
  }

  private static func Refresh(s: ref<CyberblockVendorService>, player: ref<PlayerPuppet>) -> Void {
    CyberblockVendors.Read(s, player);
    CyberblockVendors.Send(s);
  }

  // `uiact cpmenu arg:"vendor"`: Cyberpunk's own hub for the vendor of the Minecraft screen, once.
  public static func OpenVanilla(game: GameInstance) -> Void {
    let s = CyberblockVendorService.Get();
    if !IsDefined(s) || !IsDefined(s.panel) {
      Cyberblock_Log("vendors: Night City vendor... with no vendor to open");
      return;
    }
    let panel = s.panel;
    CyberblockVendors.End(s, "Night City vendor... asked for Cyberpunk's screen");
    s.bypassOnce = true;
    GameInstance.GetUISystem(game).RequestVendorMenu(panel);
    Cyberblock_Log(s"vendors: \(s.name) reopened as Night City's own screen");
  }
}

@wrapMethod(VendorHubMenuGameController)
protected cb func OnSetUserData(userData: ref<IScriptable>) -> Bool {
  let result = wrappedMethod(userData);
  CyberblockVendors.Opened(this, this.m_VendorDataManager, userData as VendorPanelData, this.m_isRipperdoc, this.m_isRipperdocViktorTutorial, this.m_player);
  return result;
}

// Closes the hub as its own Back does (the scenario's OnVendorClose).
@addMethod(VendorHubMenuGameController)
public func CyberblockClose() -> Void {
  this.CloseVendor();
}
