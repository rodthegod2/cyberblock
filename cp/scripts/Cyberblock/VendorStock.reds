// A host's Cyberpunk vendor as data for a Minecraft screen: the stock reply and the buy/sell transactions, paid with the
// host's real Cyberpunk money (megaplan 2026-10-04 task 7-S1, the scripts part of 7-S1/7-D1; section 1.8 Phase 7 row,
// 1.9 `reply kind:"vendor"`, 1.16; Phase 0 check (f)). Package 7V's screen code (Menus.reds, Vendors.reds) decides when
// a vendor becomes a Minecraft screen; this file only reads and trades. Its entry points:
//   SetVendor(game, vendor)        the vendor whose menu 7V took over (else Reply finds one, see Current).
//   Reply(game, id) -> Bool        answers a stock request with `reply kind:"vendor"` (false: no vendor; an empty reply
//                                  is still sent so Minecraft never waits).
//   Buy / Sell(game, hash, n, id)  one transaction for the item `hash` of the last reply; every request is answered by a
//                                  fresh reply with the same `id` (after the money changed, or with the refusal toasted).
//   Readable() -> Bool             the last reply listed any stock (false: 7V keeps that vendor pass-through, 7-V1).
//   OnEvent(game, kind, on)        the DLL's n"buy" / n"sell" events (section 1.8): Cyberblock_EventTarget = the item
//                                  hash (the line hash, Uint32), Cyberblock_EventAmount = how many, Cyberblock_EventId =
//                                  the reply id. Events.reds hands them here whatever the state; refused unless On.
//   Pump(game)                     from CyberblockUi.Pump (also while paused): a reply waiting for the stock and a trade
//                                  waiting for its money change.
//
// The reply (Cyberblock_ReplyBegin(n"vendor", id) ... Cyberblock_ReplyEnd()), in this order:
//   1. a header group: hash 0, title = the vendor's name, kind = its screen role (Roles.reds: n"vendor",
//      n"ripperdoc", n"bartender", n"medic", n"mechanic"), no lines;
//   2. the stock, one group per item category in order of first appearance: hash 1.., title = the category name
//      (gamedataItemCategory: Weapon, Clothing, Consumable, Cyberware, ...), kind n"buy"; one line per item the host may
//      buy now (SItemStack.isAvailable): hash = the item hash below, text = the item's name plus " x<n>" when the vendor
//      has more than one, state = the unit price in eddies. A ripperdoc's cyberware (GetRipperDocItems) is one more
//      n"buy" group, "Cyberware" (buying puts it in V's backpack; installing stays the Night City ripperdoc's, 7-V1);
//   3. what V can sell, grouped the same way: hash 1001.., kind n"sell"; text = name plus " x<n>" for V's count,
//      state = the unit price the vendor pays.
//   At most 384 buy lines and 127 sell lines (the DLL keeps 512 lines). Item hash: the low 32 bits of
//   ItemID.GetCombinedHash; Buy and Sell look it up in the last reply's tables (buy and sell apart).
//
// Phase 0 (f) says vendor stock is readable (MarketSystem.GetVendorItemsForSale) but the CET line F1 was not run, so it
// is feature-checked at runtime: the stock comes from vanilla's VendorDataManager (the vendor menu's own reader, prices
// included); the vendor is attached asynchronously, so an empty stock is asked again for up to 30 pump passes (1 s at
// 30 Hz) before the reply goes. Still empty: the written fallback (7-V1) applies, the vendor stays pass-through
// (Readable() false). One log line per reply names the path:
//   'vendor: reply <id> <name> (<role>): N to buy in G groups, M to sell (stock from the vendor data manager)'
//   'vendor: reply <id> <name> (<role>): stock not readable after 1 s (fallback: pass-through); M to sell'
//   'vendor: reply <id>: no vendor (none named, none looked at or within 6 m)'.
// A trade is checked the same way (BuyItemFromVendor / SellItemToVendor queue MarketSystem requests): V's money must
// change within 30 pump passes, else it counts as failed:
//   'vendor: bought|sold <item> xN for T eddies (ok)', 'vendor: buy|sell <item> xN failed (money unchanged after 1 s)',
//   'vendor: buy|sell <hash> refused (<why>)'. Failures and refusals are also Minecraft toasts (Cyberblock_Notify).
//
// 2.31 script API (NativeDB, redscript), compile-checked by cp/scripts/compile_check.py: VendorDataManager (Initialize(
// GameObject, EntityID), GetVendorName, GetLocalPlayerCurrencyAmount, GetVendorInventoryItems -> array<ref<
// VendorGameItemData>>, GetRipperDocItems, GetItemsPlayerCanSell -> array<ref<gameItemData>>, GetBuyingPrice(ItemID),
// GetSellingPrice(ItemID), BuyItemFromVendor(gameItemData, Int32, Int32), SellItemToVendor(gameItemData, Int32, Int32)),
// VendorGameItemData.gameItemData/itemStack, SItemStack.itemID/quantity/isAvailable, gameItemData.GetID/GetQuantity,
// ItemID.GetTDBID/GetCombinedHash, TweakDBInterface.GetItemRecord, Item_Record.DisplayName/ItemCategory,
// ItemCategory_Record.Type, GetLocalizedTextByKey, EnumValueToName, Cast<Uint32>(Uint64).

public class CyberblockVendorStockService extends ScriptableService {
  // The vendor named by SetVendor (or found by the last Reply) and the data manager reading it.
  public let vendor: wref<GameObject>;
  public let manager: ref<VendorDataManager>;
  public let managerFor: wref<GameObject>;
  public let vendorName: String;
  public let role: CName;
  // The last reply's tables (parallel arrays), buy and sell apart.
  public let buyHashes: array<Uint32>;
  public let buyItems: array<wref<gameItemData>>;
  public let buyPrices: array<Int32>;
  public let buyCounts: array<Int32>;
  public let buyLabels: array<String>;
  public let sellHashes: array<Uint32>;
  public let sellItems: array<wref<gameItemData>>;
  public let sellPrices: array<Int32>;
  public let sellCounts: array<Int32>;
  public let sellLabels: array<String>;
  public let readable: Bool;
  // A reply waiting for the vendor's stock: its id and the pump passes left.
  public let waiting: Bool;
  public let waitId: Int32;
  public let waitPasses: Int32;
  // A trade waiting for V's money to change (tradeKind n"" none).
  public let tradeKind: CName;
  public let tradeLabel: String;
  public let tradeCount: Int32;
  public let tradeMoney: Int32;
  public let tradePasses: Int32;
  public let tradeId: Int32;

  public static func Get() -> ref<CyberblockVendorStockService> {
    return GameInstance.GetScriptableServiceContainer().GetService(n"CyberblockVendorStockService") as CyberblockVendorStockService;
  }
}

public abstract class CyberblockVendorStock {
  public static func SetVendor(game: GameInstance, vendor: ref<GameObject>) -> Void {
    let s = CyberblockVendorStockService.Get();
    if !IsDefined(s) || !IsDefined(vendor) {
      return;
    }
    s.vendor = vendor;
    Cyberblock_Log(s"vendor: \(NameToString(vendor.GetClassName())) named by the vendor screen");
  }

  public static func Readable() -> Bool {
    let s = CyberblockVendorStockService.Get();
    return IsDefined(s) && s.readable;
  }

  public static func Reply(game: GameInstance, id: Int32) -> Bool {
    let s = CyberblockVendorStockService.Get();
    if !IsDefined(s) {
      return false;
    }
    if !CyberblockVendorStock.Prepare(game, s) {
      CyberblockVendorStock.Clear(s);
      Cyberblock_ReplyBegin(n"vendor", id);
      Cyberblock_ReplyEnd();
      Cyberblock_Log(s"vendor: reply \(id): no vendor (none named, none looked at or within 6 m)");
      return false;
    }
    if CyberblockVendorStock.StockCount(s) == 0 {
      // Attached asynchronously: asked again by Pump for up to 30 passes.
      s.waiting = true;
      s.waitId = id;
      s.waitPasses = 30;
      return true;
    }
    CyberblockVendorStock.Send(s, id, false);
    return true;
  }

  public static func Buy(game: GameInstance, hash: Uint32, count: Int32, id: Int32) -> Bool {
    return CyberblockVendorStock.Trade(game, n"buy", hash, count, id);
  }

  public static func Sell(game: GameInstance, hash: Uint32, count: Int32, id: Int32) -> Bool {
    return CyberblockVendorStock.Trade(game, n"sell", hash, count, id);
  }

  // An n"buy" or n"sell" event is current (Cyberblock_NextEvent). on: Cyberblock is On.
  public static func OnEvent(game: GameInstance, kind: CName, on: Bool) -> Void {
    let hash = Cast<Uint32>(Cyberblock_EventTarget());
    let count = RoundF(Cyberblock_EventAmount());
    let id = Cyberblock_EventId();
    if !on {
      CyberblockVendorStock.Refuse(game, kind, hash, id, "Cyberblock is not active", false);
      return;
    }
    CyberblockVendorStock.Trade(game, kind, hash, count, id);
  }

  public static func Pump(game: GameInstance) -> Void {
    let s = CyberblockVendorStockService.Get();
    if !IsDefined(s) {
      return;
    }
    if s.waiting {
      s.waitPasses -= 1;
      if !IsDefined(s.manager) {
        s.waiting = false;
        Cyberblock_ReplyBegin(n"vendor", s.waitId);
        Cyberblock_ReplyEnd();
        Cyberblock_Log(s"vendor: reply \(s.waitId): no vendor (gone while its stock was read)");
      } else {
        if CyberblockVendorStock.StockCount(s) > 0 || s.waitPasses <= 0 {
          s.waiting = false;
          CyberblockVendorStock.Send(s, s.waitId, false);
        }
      }
    }
    if NotEquals(s.tradeKind, n"") {
      s.tradePasses -= 1;
      let money = IsDefined(s.manager) ? s.manager.GetLocalPlayerCurrencyAmount() : s.tradeMoney;
      let buy = Equals(s.tradeKind, n"buy");
      let done = buy ? money < s.tradeMoney : money > s.tradeMoney;
      if done || s.tradePasses <= 0 || !IsDefined(s.manager) {
        let what = s"\(s.tradeLabel) x\(s.tradeCount)";
        if done {
          Cyberblock_Log(s"vendor: \(buy ? "bought" : "sold") \(what) for \(buy ? s.tradeMoney - money : money - s.tradeMoney) eddies (ok)");
          Cyberblock_Notify(n"money", buy ? "Bought" : "Sold", what, 2.0);
        } else {
          Cyberblock_Log(s"vendor: \(NameToString(s.tradeKind)) \(what) failed (money unchanged after 1 s)");
          Cyberblock_Notify(n"warning", buy ? "Purchase failed" : "Sale failed", "Use the Night City Menu... for this vendor", 4.0);
        }
        s.tradeKind = n"";
        if IsDefined(s.manager) {
          CyberblockVendorStock.Send(s, s.tradeId, true);
        }
      }
    }
  }

  // On every load and at session end: nothing of the previous session's vendor is kept.
  public static func Forget() -> Void {
    let s = CyberblockVendorStockService.Get();
    if !IsDefined(s) {
      return;
    }
    s.vendor = null;
    s.manager = null;
    s.managerFor = null;
    s.waiting = false;
    s.tradeKind = n"";
    s.readable = false;
    CyberblockVendorStock.Clear(s);
  }

  // The vendor to read: the named one while V is within 15 m of it, else Roles.reds' Vendor (look-at, nearest within
  // 6 m). A data manager is made for it when it changed. False when there is none.
  private static func Prepare(game: GameInstance, s: ref<CyberblockVendorStockService>) -> Bool {
    let player = GetPlayer(game);
    if !IsDefined(player) {
      return false;
    }
    let vendor: ref<GameObject> = s.vendor;
    if !IsDefined(vendor) || Vector4.DistanceSquared(vendor.GetWorldPosition(), player.GetWorldPosition()) > 15.0 * 15.0 {
      vendor = CyberblockRoles.Vendor(game, player, 6.0);
    }
    if !IsDefined(vendor) {
      s.manager = null;
      s.managerFor = null;
      return false;
    }
    s.vendor = vendor;
    if !IsDefined(s.manager) || s.managerFor != vendor {
      let entity = vendor.GetEntityID();
      s.manager = new VendorDataManager();
      s.manager.Initialize(player, entity);
      s.managerFor = vendor;
      s.vendorName = s.manager.GetVendorName();
      if StrLen(s.vendorName) == 0 {
        s.vendorName = "Vendor";
      }
      let type = CyberblockRoles.VendorTypeOf(vendor);
      s.role = IsNameValid(type) ? CyberblockRoles.ScreenRole(type) : n"vendor";
    }
    return true;
  }

  private static func StockCount(s: ref<CyberblockVendorStockService>) -> Int32 {
    let n = ArraySize(s.manager.GetVendorInventoryItems());
    if Equals(s.role, n"ripperdoc") {
      n += ArraySize(s.manager.GetRipperDocItems());
    }
    return n;
  }

  // Builds and sends the reply, filling the tables Buy and Sell look items up in. after: the answer to a trade.
  private static func Send(s: ref<CyberblockVendorStockService>, id: Int32, after: Bool) -> Void {
    CyberblockVendorStock.Clear(s);
    let m = s.manager;
    Cyberblock_ReplyBegin(n"vendor", id);
    Cyberblock_ReplyGroup(0u, s.vendorName, s.role, false, false);

    // The stock, by category: the items with the category index of each (parallel arrays), then one group per category.
    let cats: array<CName>;
    let items: array<wref<gameItemData>>;
    let itemCats: array<Int32>;
    let unavailable = 0;
    for entry in m.GetVendorInventoryItems() {
      if IsDefined(entry) && IsDefined(entry.gameItemData) {
        if entry.itemStack.isAvailable {
          CyberblockVendorStock.File(entry.gameItemData, CyberblockVendorStock.Category(entry.gameItemData.GetID()), cats, items, itemCats);
        } else {
          unavailable += 1;
        }
      }
    }
    if Equals(s.role, n"ripperdoc") {
      for entry in m.GetRipperDocItems() {
        if IsDefined(entry) && IsDefined(entry.gameItemData) && entry.itemStack.isAvailable {
          CyberblockVendorStock.File(entry.gameItemData, n"Cyberware", cats, items, itemCats);
        }
      }
    }
    let c = 0;
    while c < ArraySize(cats) {
      Cyberblock_ReplyGroup(Cast<Uint32>(1 + c), NameToString(cats[c]), n"buy", false, false);
      let k = 0;
      while k < ArraySize(items) {
        if itemCats[k] == c && ArraySize(s.buyHashes) < 384 {
          let data = items[k];
          let item = data.GetID();
          CyberblockVendorStock.Line(data, m.GetBuyingPrice(item), data.GetQuantity(), s.buyHashes, s.buyItems, s.buyPrices, s.buyCounts, s.buyLabels);
        }
        k += 1;
      }
      c += 1;
    }
    let buyGroups = ArraySize(cats);

    // What V can sell, grouped the same way.
    ArrayClear(cats);
    ArrayClear(items);
    ArrayClear(itemCats);
    for data in m.GetItemsPlayerCanSell() {
      if IsDefined(data) {
        CyberblockVendorStock.File(data, CyberblockVendorStock.Category(data.GetID()), cats, items, itemCats);
      }
    }
    c = 0;
    while c < ArraySize(cats) {
      Cyberblock_ReplyGroup(Cast<Uint32>(1001 + c), NameToString(cats[c]), n"sell", false, false);
      let k = 0;
      while k < ArraySize(items) {
        if itemCats[k] == c && ArraySize(s.sellHashes) < 127 {
          let data = items[k];
          let item = data.GetID();
          CyberblockVendorStock.Line(data, m.GetSellingPrice(item), data.GetQuantity(), s.sellHashes, s.sellItems, s.sellPrices, s.sellCounts, s.sellLabels);
        }
        k += 1;
      }
      c += 1;
    }
    Cyberblock_ReplyEnd();

    s.readable = ArraySize(s.buyHashes) > 0 || unavailable > 0;
    let head = s"vendor: reply \(id) \(s.vendorName) (\(NameToString(s.role)))\(after ? " after a trade" : "")";
    if s.readable {
      Cyberblock_Log(s"\(head): \(ArraySize(s.buyHashes)) to buy in \(buyGroups) groups (\(unavailable) locked left out), \(ArraySize(s.sellHashes)) to sell (stock from the vendor data manager)");
    } else {
      Cyberblock_Log(s"\(head): stock not readable after 1 s (fallback: pass-through); \(ArraySize(s.sellHashes)) to sell");
    }
  }

  // Adds an item with its category's index, adding the category in order of first appearance.
  private static func File(data: wref<gameItemData>, cat: CName, out cats: array<CName>, out items: array<wref<gameItemData>>, out itemCats: array<Int32>) -> Void {
    let at = ArrayFindFirst(cats, cat);
    if at < 0 {
      ArrayPush(cats, cat);
      at = ArraySize(cats) - 1;
    }
    ArrayPush(items, data);
    ArrayPush(itemCats, at);
  }

  // One reply line, remembered in the given tables.
  private static func Line(data: wref<gameItemData>, price: Int32, count: Int32, out hashes: array<Uint32>,
    out items: array<wref<gameItemData>>, out prices: array<Int32>, out counts: array<Int32>, out labels: array<String>) -> Void {
    let item = data.GetID();
    let hash = Cast<Uint32>(ItemID.GetCombinedHash(item));
    let label = CyberblockVendorStock.Label(item);
    let n = Max(1, count);
    Cyberblock_ReplyLine(hash, n > 1 ? s"\(label) x\(n)" : label, Max(0, price));
    ArrayPush(hashes, hash);
    ArrayPush(items, data);
    ArrayPush(prices, Max(0, price));
    ArrayPush(counts, n);
    ArrayPush(labels, label);
  }

  private static func Label(item: ItemID) -> String {
    let record = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(item));
    let label = IsDefined(record) ? GetLocalizedTextByKey(record.DisplayName()) : "";
    return StrLen(label) > 0 ? label : "Item";
  }

  private static func Category(item: ItemID) -> CName {
    let record = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(item));
    if IsDefined(record) {
      let category = record.ItemCategory();
      if IsDefined(category) {
        let name = EnumValueToName(n"gamedataItemCategory", Cast<Int64>(EnumInt(category.Type())));
        if IsNameValid(name) {
          return name;
        }
      }
    }
    return n"Other";
  }

  // Checks and queues one trade (see the header). False when it was refused.
  private static func Trade(game: GameInstance, kind: CName, hash: Uint32, count: Int32, id: Int32) -> Bool {
    let s = CyberblockVendorStockService.Get();
    if !IsDefined(s) {
      return false;
    }
    let system = CyberblockSystem.Get(game);
    if !IsDefined(system) || !system.UiOn() {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, "Cyberblock is not active", false);
    }
    if NotEquals(s.tradeKind, n"") {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, "another trade is still running", false);
    }
    if !IsDefined(s.manager) || !IsDefined(s.managerFor) {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, "no vendor open", false);
    }
    let buy = Equals(kind, n"buy");
    let at = buy ? ArrayFindFirst(s.buyHashes, hash) : ArrayFindFirst(s.sellHashes, hash);
    if at < 0 {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, "not in the last list", true);
    }
    let data = buy ? s.buyItems[at] : s.sellItems[at];
    if !IsDefined(data) {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, "item gone", true);
    }
    let n = Min(Max(1, count), buy ? s.buyCounts[at] : s.sellCounts[at]);
    let total = n * (buy ? s.buyPrices[at] : s.sellPrices[at]);
    let money = s.manager.GetLocalPlayerCurrencyAmount();
    if buy && money < total {
      return CyberblockVendorStock.Refuse(game, kind, hash, id, s"not enough eddies (\(money) of \(total))", true);
    }
    s.tradeKind = kind;
    s.tradeLabel = buy ? s.buyLabels[at] : s.sellLabels[at];
    s.tradeCount = n;
    s.tradeMoney = money;
    s.tradePasses = 30;
    s.tradeId = id;
    if buy {
      s.manager.BuyItemFromVendor(data, n, id);
    } else {
      s.manager.SellItemToVendor(data, n, id);
    }
    return true;
  }

  // Logs and toasts a refused trade; answers it with a fresh reply when a vendor is open (resend).
  private static func Refuse(game: GameInstance, kind: CName, hash: Uint32, id: Int32, why: String, resend: Bool) -> Bool {
    Cyberblock_Log(s"vendor: \(NameToString(kind)) \(hash) refused (\(why))");
    Cyberblock_Notify(n"warning", Equals(kind, n"buy") ? "Can't buy" : "Can't sell", why, 3.0);
    let s = CyberblockVendorStockService.Get();
    if resend && IsDefined(s) && IsDefined(s.manager) {
      CyberblockVendorStock.Send(s, id, true);
    } else {
      // Minecraft waits for an answer to every request.
      Cyberblock_ReplyBegin(n"vendor", id);
      Cyberblock_ReplyEnd();
    }
    return false;
  }

  private static func Clear(s: ref<CyberblockVendorStockService>) -> Void {
    ArrayClear(s.buyHashes);
    ArrayClear(s.buyItems);
    ArrayClear(s.buyPrices);
    ArrayClear(s.buyCounts);
    ArrayClear(s.buyLabels);
    ArrayClear(s.sellHashes);
    ArrayClear(s.sellItems);
    ArrayClear(s.sellPrices);
    ArrayClear(s.sellCounts);
    ArrayClear(s.sellLabels);
  }
}
