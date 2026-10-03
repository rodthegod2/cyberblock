// Layers 3-6 in the DLL (plan 2026-10-03 sections 1.2, 1.3, 1.5): the new Minecraft -> Cyberpunk events, how they are
// validated and converted, and which of them the queue keeps while the mashup is not Active.
#include "test.hpp"
#include "world.hpp"
#include <cmath>
#include <iterator>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::Event;
	using cb::world::EventKind;
	using Status = cb::world::Parsed::Status;

	constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
	constexpr double kInf = std::numeric_limits<double>::infinity();

	cb::world::Parsed parse(const std::string &text)
	{
		return cb::world::parse_event(json::parse(text));
	}

	/// The one event of a valid message (a default Event, kind None, when the message is not valid or expands otherwise).
	Event one(const std::string &text)
	{
		const cb::world::Parsed p = parse(text);
		return p.status == Status::Event && p.events.size() == 1 ? p.events[0] : Event{};
	}

	bool rejected(const std::string &text)
	{
		const cb::world::Parsed p = parse(text);
		return p.status == Status::Rejected && !p.reason.empty() && p.events.empty();
	}

	bool invalid(const json &message)
	{
		return cb::world::parse_event(message).status == Status::Rejected;
	}

	bool approx(double a, double b)
	{
		return std::abs(a - b) <= 1e-9;
	}

	bool approx(const cb::Vec3 &a, double x, double y, double z)
	{
		return approx(a.x, x) && approx(a.y, y) && approx(a.z, z);
	}

	std::string mob_hit(const std::string &mob)
	{
		return R"({"t":"hit","target":"9","dmg":4,"kind":"mob","mob":)" + mob + "}";
	}

	Event event(EventKind kind)
	{
		Event e;
		e.kind = kind;
		return e;
	}
}

// ---- hit.mob -----------------------------------------------------------------------------------------------------------

TEST(hit_carries_the_mob_that_dealt_it_or_minus_one)
{
	CHECK(one(mob_hit("3")).id == 3);
	CHECK(one(mob_hit("0")).id == 0);               // mob id 0 is a mob, not "none"
	CHECK(one(mob_hit("2147483647")).id == 2147483647);
	CHECK(one(mob_hit("7.0")).id == 7);             // a whole number written as a float
	CHECK(one(R"({"t":"hit","target":"9","dmg":4})").id == -1); // absent: V's own hit
	CHECK(one(mob_hit("null")).id == -1);
	const Event e = one(mob_hit("5"));
	CHECK(e.kind == EventKind::Hit && e.target == 9 && approx(e.amount, 0.2)); // the layer 2 fields are unchanged
}

TEST(bad_mob_ids_reject_the_hit)
{
	CHECK(rejected(mob_hit("-1")));
	CHECK(rejected(mob_hit("2147483648")));
	CHECK(rejected(mob_hit("1.5")));
	CHECK(rejected(mob_hit("\"3\"")));
	CHECK(rejected(mob_hit("true")));
	json nan_mob = {{"t", "hit"}, {"target", "9"}, {"dmg", 4}, {"mob", kNaN}};
	CHECK(invalid(nan_mob));
}

// ---- teleport ----------------------------------------------------------------------------------------------------------

TEST(teleport_converts_feet_and_yaw)
{
	const Event e = one(R"({"t":"teleport","p":[10.5,64,-20.25],"yaw":0})");
	CHECK(e.kind == EventKind::Teleport);
	CHECK(approx(e.pos, 10.5, 20.25, 64)); // mc (x, y, z) -> cp (x, -z, y)
	CHECK(approx(e.amount, 180));          // Minecraft yaw 0 (+Z) is Cyberpunk yaw 180 (-Y)
	CHECK(approx(one(R"({"t":"teleport","p":[0,0,0],"yaw":90})").amount, 90));
	CHECK(approx(one(R"({"t":"teleport","p":[0,0,0],"yaw":-90})").amount, -90));
	CHECK(approx(one(R"({"t":"teleport","p":[0,0,0],"yaw":720.5})").amount, 179.5)); // an unnormalised client yaw: 180 - 720.5 = -540.5 = 179.5
}

TEST(bad_teleports_are_rejected)
{
	CHECK(rejected(R"({"t":"teleport","yaw":0})"));
	CHECK(rejected(R"({"t":"teleport","p":[0,0,0]})"));
	CHECK(rejected(R"({"t":"teleport","p":[0,0],"yaw":0})"));
	CHECK(rejected(R"({"t":"teleport","p":[0,0,3e7],"yaw":0})"));
	CHECK(rejected(R"({"t":"teleport","p":[0,0,0],"yaw":"0"})"));
	CHECK(rejected(R"({"t":"teleport","p":[0,0,0],"yaw":4e7})"));
	json nan_yaw = {{"t", "teleport"}, {"p", {0, 0, 0}}, {"yaw", kNaN}};
	json inf_pos = {{"t", "teleport"}, {"p", {kInf, 0, 0}}, {"yaw", 0}};
	CHECK(invalid(nan_yaw) && invalid(inf_pos));
}

// ---- lead --------------------------------------------------------------------------------------------------------------

TEST(lead_with_a_fixed_anchor)
{
	const Event e = one(R"({"t":"lead","target":"18446744073709551615","anchor":[1,2,3],"length":8.5})");
	CHECK(e.kind == EventKind::Lead);
	CHECK(e.target == 18446744073709551615ull);
	CHECK(e.flag && approx(e.pos, 1, -3, 2));
	CHECK(approx(e.amount, 8.5));
}

TEST(lead_held_by_steve_has_no_anchor)
{
	for (const char *text : {R"({"t":"lead","target":"5","anchor":null,"length":4})", R"({"t":"lead","target":"5","length":4})"})
	{
		const Event e = one(text);
		CHECK(e.kind == EventKind::Lead && !e.flag && approx(e.pos, 0, 0, 0) && approx(e.amount, 4));
	}
}

TEST(lead_release)
{
	const Event e = one(R"({"t":"lead","target":"5","release":true})");
	CHECK(e.kind == EventKind::LeadRelease && e.target == 5);
	CHECK(approx(e.amount, 0) && !e.flag);
	CHECK(one(R"({"t":"lead","target":"5","release":false,"length":3})").kind == EventKind::Lead);
}

TEST(lead_limits)
{
	CHECK(one(R"({"t":"lead","target":"5","length":1})").kind == EventKind::Lead);
	CHECK(one(R"({"t":"lead","target":"5","length":32})").kind == EventKind::Lead);
	CHECK(rejected(R"({"t":"lead","target":"5","length":0.99})"));
	CHECK(rejected(R"({"t":"lead","target":"5","length":32.01})"));
	CHECK(rejected(R"({"t":"lead","target":"5"})"));                                // a lead needs a length
	CHECK(rejected(R"({"t":"lead","length":4})"));                                    // and a target
	CHECK(rejected(R"({"t":"lead","target":5,"length":4})"));                         // ids are strings
	CHECK(rejected(R"({"t":"lead","target":"5","release":1})"));                      // present but not a bool
	CHECK(rejected(R"({"t":"lead","target":"5","anchor":[1,2],"length":4})"));
	CHECK(rejected(R"({"t":"lead","target":"5","anchor":"here","length":4})"));
	CHECK(rejected(R"({"t":"lead","target":"5","anchor":[0,0,3e7],"length":4})"));
	CHECK(rejected(R"({"t":"lead","release":true})"));
}

// ---- pull and knock ----------------------------------------------------------------------------------------------------

TEST(pull_converts_the_point_and_keeps_strength)
{
	const Event e = one(R"({"t":"pull","target":"77","toward":[4,70,-9],"strength":12.5})");
	CHECK(e.kind == EventKind::Pull && e.target == 77);
	CHECK(approx(e.pos, 4, 9, 70));
	CHECK(approx(e.amount, 12.5));
}

TEST(pull_limits)
{
	CHECK(one(R"({"t":"pull","target":"1","toward":[0,0,0],"strength":0})").kind == EventKind::Pull);
	CHECK(one(R"({"t":"pull","target":"1","toward":[0,0,0],"strength":50})").kind == EventKind::Pull);
	CHECK(rejected(R"({"t":"pull","target":"1","toward":[0,0,0],"strength":50.5})"));
	CHECK(rejected(R"({"t":"pull","target":"1","toward":[0,0,0],"strength":-1})"));
	CHECK(rejected(R"({"t":"pull","target":"1","toward":[0,0,0]})"));
	CHECK(rejected(R"({"t":"pull","target":"1","strength":5})"));
	CHECK(rejected(R"({"t":"pull","toward":[0,0,0],"strength":5})"));
	json nan_strength = {{"t", "pull"}, {"target", "1"}, {"toward", {0, 0, 0}}, {"strength", kNaN}};
	CHECK(invalid(nan_strength));
}

TEST(knock_fills_centre_radius_and_strength)
{
	const Event e = one(R"({"t":"knock","pos":[1.5,3,-2],"radius":6,"strength":9.5})");
	CHECK(e.kind == EventKind::Knock);
	CHECK(approx(e.pos, 1.5, 2, 3));
	CHECK(approx(e.amount, 6));  // radius
	CHECK(approx(e.extra, 9.5)); // strength
}

TEST(knock_limits)
{
	CHECK(one(R"({"t":"knock","pos":[0,0,0],"radius":0,"strength":0})").kind == EventKind::Knock);
	CHECK(one(R"({"t":"knock","pos":[0,0,0],"radius":16,"strength":50})").kind == EventKind::Knock);
	CHECK(rejected(R"({"t":"knock","pos":[0,0,0],"radius":16.1,"strength":5})"));
	CHECK(rejected(R"({"t":"knock","pos":[0,0,0],"radius":-0.1,"strength":5})"));
	CHECK(rejected(R"({"t":"knock","pos":[0,0,0],"radius":5,"strength":50.1})"));
	CHECK(rejected(R"({"t":"knock","pos":[0,0,0],"radius":5})"));
	CHECK(rejected(R"({"t":"knock","pos":[0,0,0],"strength":5})"));
	CHECK(rejected(R"({"t":"knock","radius":5,"strength":5})"));
}

// ---- effect ------------------------------------------------------------------------------------------------------------

TEST(effect_fills_name_seconds_and_amplifier)
{
	const Event e = one(R"({"t":"effect","name":"jump_boost","seconds":30.5,"amp":2})");
	CHECK(e.kind == EventKind::Effect && e.name == "jump_boost");
	CHECK(approx(e.amount, 30.5) && approx(e.extra, 2));
	CHECK(approx(one(R"({"t":"effect","name":"speed","seconds":0,"amp":0})").amount, 0)); // 0 seconds: removed
	CHECK(approx(one(R"({"t":"effect","name":"speed","seconds":5,"amp":2.0})").extra, 2));
}

TEST(effect_limits)
{
	CHECK(one(R"({"t":"effect","name":"speed","seconds":86400,"amp":255})").kind == EventKind::Effect);
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":86400.5,"amp":0})"));
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":-1,"amp":0})"));
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":5,"amp":256})"));
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":5,"amp":-1})"));
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":5,"amp":1.5})")); // an amplifier is a whole number
	CHECK(rejected(R"({"t":"effect","name":"speed","seconds":5})"));
	CHECK(rejected(R"({"t":"effect","seconds":5,"amp":0})"));
	json nan_seconds = {{"t", "effect"}, {"name", "speed"}, {"seconds", kNaN}, {"amp", 0}};
	CHECK(invalid(nan_seconds));
}

TEST(effect_names_are_lowercase_letters_and_underscores_up_to_32)
{
	const std::string longest(32, 'a');
	CHECK(one(R"({"t":"effect","name":")" + longest + R"(","seconds":1,"amp":0})").kind == EventKind::Effect);
	for (const std::string &bad : {std::string(), std::string(33, 'a'), std::string("Speed"), std::string("speed2"),
								   std::string("jump boost"), std::string("jump-boost"), std::string("minecraft:speed"),
								   std::string("sp\xc3\xa9""ed")})
	{
		json m = {{"t", "effect"}, {"name", bad}, {"seconds", 1}, {"amp", 0}};
		CHECK(invalid(m));
	}
	CHECK(rejected(R"({"t":"effect","name":5,"seconds":1,"amp":0})"));
}

// ---- hack and scan -----------------------------------------------------------------------------------------------------

TEST(hack_fills_target_chain_name_and_request_id)
{
	const Event e = one(R"({"t":"hack","id":41,"target":"9007199254740993","hack":"overheat","chain":true})");
	CHECK(e.kind == EventKind::Hack && e.id == 41 && e.target == 9007199254740993ull);
	CHECK(e.flag && e.name == "overheat");
	CHECK(!one(R"({"t":"hack","id":0,"target":"1","hack":"ping","chain":false})").flag);
	CHECK(!one(R"({"t":"hack","id":0,"target":"1","hack":"ping"})").flag); // chain defaults to false
}

TEST(every_hack_name_of_section_1_5_is_accepted)
{
	CHECK(std::size(cb::world::kHackNames) == 10);
	for (const char *name : cb::world::kHackNames)
	{
		json m = {{"t", "hack"}, {"id", 1}, {"target", "2"}, {"hack", name}, {"chain", false}};
		const cb::world::Parsed p = cb::world::parse_event(m);
		CHECK(p.status == Status::Event && p.events.size() == 1 && p.events[0].name == name);
	}
	for (const char *name : {"", "Overheat", "overheat ", "shock", "toggle_take_over_control", "short-circuit"})
	{
		json m = {{"t", "hack"}, {"id", 1}, {"target", "2"}, {"hack", name}};
		CHECK(invalid(m));
	}
}

TEST(bad_hacks_are_rejected)
{
	CHECK(rejected(R"({"t":"hack","target":"1","hack":"ping"})"));                   // no request id
	CHECK(rejected(R"({"t":"hack","id":-1,"target":"1","hack":"ping"})"));
	CHECK(rejected(R"({"t":"hack","id":2147483648,"target":"1","hack":"ping"})"));
	CHECK(rejected(R"({"t":"hack","id":1.5,"target":"1","hack":"ping"})"));
	CHECK(rejected(R"({"t":"hack","id":"1","target":"1","hack":"ping"})"));
	CHECK(rejected(R"({"t":"hack","id":1,"hack":"ping"})"));                          // no target
	CHECK(rejected(R"({"t":"hack","id":1,"target":null,"hack":"ping"})"));            // a hack needs a target
	CHECK(rejected(R"({"t":"hack","id":1,"target":"x","hack":"ping"})"));
	CHECK(rejected(R"({"t":"hack","id":1,"target":"1"})"));
	CHECK(rejected(R"({"t":"hack","id":1,"target":"1","hack":7})"));
	CHECK(rejected(R"({"t":"hack","id":1,"target":"1","hack":"ping","chain":"yes"})"));
	CHECK(one(R"({"t":"hack","id":2147483647,"target":"1","hack":"ping"})").kind == EventKind::Hack);
}

TEST(scan_target_is_an_id_or_zero_for_the_look_at)
{
	const Event e = one(R"({"t":"scan","id":7,"target":"123456789012"})");
	CHECK(e.kind == EventKind::Scan && e.id == 7 && e.target == 123456789012ull);
	CHECK(one(R"({"t":"scan","id":8,"target":null})").target == 0);
	const Event absent = one(R"({"t":"scan","id":9})");
	CHECK(absent.kind == EventKind::Scan && absent.id == 9 && absent.target == 0);
}

TEST(bad_scans_are_rejected)
{
	CHECK(rejected(R"({"t":"scan","target":"1"})"));
	CHECK(rejected(R"({"t":"scan","id":-1,"target":"1"})"));
	CHECK(rejected(R"({"t":"scan","id":1.5,"target":"1"})"));
	CHECK(rejected(R"({"t":"scan","id":1,"target":12})"));
	CHECK(rejected(R"({"t":"scan","id":1,"target":"-4"})"));
}

// ---- perks and perkfx --------------------------------------------------------------------------------------------------

TEST(perks_becomes_one_perk_event_per_entry_in_order)
{
	const cb::world::Parsed p = parse(R"({"t":"perks","list":[{"id":"iron_skin","rank":1},{"id":"dodge","rank":0}]})");
	CHECK(p.status == Status::Event && p.droppedEntries == 0 && p.events.size() == 2);
	CHECK(p.events[0].kind == EventKind::Perk && p.events[0].name == "iron_skin" && approx(p.events[0].amount, 1));
	CHECK(p.events[1].kind == EventKind::Perk && p.events[1].name == "dodge" && approx(p.events[1].amount, 0));
}

TEST(all_twenty_perk_ids_are_accepted)
{
	CHECK(std::size(cb::world::kPerkIds) == 20);
	json list = json::array();
	for (const char *id : cb::world::kPerkIds)
		list.push_back({{"id", id}, {"rank", 1}});
	const cb::world::Parsed p = cb::world::parse_event({{"t", "perks"}, {"list", list}});
	CHECK(p.status == Status::Event && p.events.size() == 20 && p.droppedEntries == 0);
	for (size_t i = 0; i < 20; ++i)
		CHECK(p.events[i].name == cb::world::kPerkIds[i]);
}

TEST(a_bad_perk_entry_is_dropped_and_counted_the_rest_is_kept)
{
	const cb::world::Parsed p = parse(
		R"({"t":"perks","list":[{"id":"iron_skin","rank":1},{"id":"fly","rank":1},{"id":"dodge","rank":2},)"
		R"(7,{"id":"painkiller"},{"rank":1},{"id":"overclock","rank":0.5},{"id":"overclock","rank":1}]})");
	CHECK(p.status == Status::Event);
	CHECK(p.droppedEntries == 6 && !p.reason.empty());
	CHECK(p.events.size() == 2 && p.events[0].name == "iron_skin" && p.events[1].name == "overclock");
	CHECK(approx(p.events[1].amount, 1));
}

TEST(perks_without_a_list_is_rejected_and_an_empty_list_is_valid)
{
	CHECK(rejected(R"({"t":"perks"})"));
	CHECK(rejected(R"({"t":"perks","list":{"id":"dodge","rank":1}})"));
	CHECK(rejected(R"({"t":"perks","list":"dodge"})"));
	const cb::world::Parsed empty = parse(R"({"t":"perks","list":[]})");
	CHECK(empty.status == Status::Event && empty.events.empty() && empty.droppedEntries == 0);
}

TEST(perks_past_32_entries_are_dropped)
{
	json list = json::array();
	for (int i = 0; i < 35; ++i)
		list.push_back({{"id", "dodge"}, {"rank", 1}});
	const cb::world::Parsed p = cb::world::parse_event({{"t", "perks"}, {"list", list}});
	CHECK(p.status == Status::Event && p.events.size() == 32 && p.droppedEntries == 3);
}

TEST(perkfx_fills_id_and_seconds)
{
	const Event e = one(R"({"t":"perkfx","id":"sandevistan","seconds":5})");
	CHECK(e.kind == EventKind::PerkFx && e.name == "sandevistan" && approx(e.amount, 5));
	CHECK(one(R"({"t":"perkfx","id":"sandevistan","seconds":0})").kind == EventKind::PerkFx);
	CHECK(one(R"({"t":"perkfx","id":"sandevistan","seconds":30})").kind == EventKind::PerkFx);
	CHECK(rejected(R"({"t":"perkfx","id":"sandevistan","seconds":30.5})"));
	CHECK(rejected(R"({"t":"perkfx","id":"sandevistan","seconds":-1})"));
	CHECK(rejected(R"({"t":"perkfx","id":"teleport","seconds":5})"));
	CHECK(rejected(R"({"t":"perkfx","seconds":5})"));
	CHECK(rejected(R"({"t":"perkfx","id":"sandevistan"})"));
}

// ---- blocks ------------------------------------------------------------------------------------------------------------

TEST(blocks_expand_to_a_reset_then_one_add_per_cell_in_order)
{
	const cb::world::Parsed p = parse(R"({"t":"blocks","reset":true,"c":[10,4,-21, 0,0,0, -1,-1,-1]})");
	CHECK(p.status == Status::Event && p.droppedEntries == 0 && p.events.size() == 4);
	CHECK(p.events[0].kind == EventKind::BlockReset);
	CHECK(p.events[1].kind == EventKind::BlockAdd && approx(p.events[1].pos, 10, 20, 4)); // plan layer 2 1.1 example
	CHECK(p.events[2].kind == EventKind::BlockAdd && approx(p.events[2].pos, 0, -1, 0));
	CHECK(p.events[3].kind == EventKind::BlockAdd && approx(p.events[3].pos, -1, 0, -1));
}

TEST(blocks_without_reset_are_just_adds)
{
	for (const char *text : {R"({"t":"blocks","reset":false,"c":[1,2,3]})", R"({"t":"blocks","c":[1,2,3]})"})
	{
		const cb::world::Parsed p = parse(text);
		CHECK(p.status == Status::Event && p.events.size() == 1 && p.events[0].kind == EventKind::BlockAdd);
	}
}

TEST(a_reset_may_have_no_cells_and_no_cells_without_a_reset_is_still_valid)
{
	const cb::world::Parsed reset = parse(R"({"t":"blocks","reset":true,"c":[]})");
	CHECK(reset.status == Status::Event && reset.events.size() == 1 && reset.events[0].kind == EventKind::BlockReset);
	const cb::world::Parsed nothing = parse(R"({"t":"blocks","reset":false,"c":[]})");
	CHECK(nothing.status == Status::Event && nothing.events.empty());
}

TEST(a_bad_block_cell_is_dropped_and_counted_the_rest_is_kept)
{
	const cb::world::Parsed p = parse(R"({"t":"blocks","reset":true,"c":[1,2,3, 0.5,0,0, 4,5,6, "7",8,9, 3e7,0,0, 7,8,9]})");
	CHECK(p.status == Status::Event && p.droppedEntries == 3 && !p.reason.empty());
	CHECK(p.events.size() == 4); // the reset and three good cells
	CHECK(p.events[0].kind == EventKind::BlockReset);
	CHECK(approx(p.events[1].pos, 1, -4, 2) && approx(p.events[2].pos, 4, -7, 5) && approx(p.events[3].pos, 7, -10, 8));
	CHECK(parse(R"({"t":"blocks","c":[29999999,-64,-29999999]})").events.size() == 1);
}

TEST(a_malformed_blocks_message_is_rejected_whole)
{
	CHECK(rejected(R"({"t":"blocks","reset":true})"));                      // no c
	CHECK(rejected(R"({"t":"blocks","reset":true,"c":[1,2,3,4]})"));       // not a multiple of three
	CHECK(rejected(R"({"t":"blocks","reset":true,"c":[1,2]})"));
	CHECK(rejected(R"({"t":"blocks","reset":true,"c":"1,2,3"})"));
	CHECK(rejected(R"({"t":"blocks","reset":true,"c":{"x":1}})"));
	CHECK(rejected(R"({"t":"blocks","reset":1,"c":[1,2,3]})"));            // present but not a bool
	// the reset is not queued when the cells are bad: a rejected message queues nothing
	CHECK(parse(R"({"t":"blocks","reset":true,"c":[1,2,3,4]})").events.empty());
}

TEST(blocks_hold_at_most_64_cells)
{
	json c = json::array();
	for (int i = 0; i < 64; ++i)
		for (int k = 0; k < 3; ++k)
			c.push_back(i);
	const cb::world::Parsed full = cb::world::parse_event({{"t", "blocks"}, {"reset", true}, {"c", c}});
	CHECK(full.status == Status::Event && full.events.size() == 65);
	for (int k = 0; k < 3; ++k)
		c.push_back(0);
	const cb::world::Parsed over = cb::world::parse_event({{"t", "blocks"}, {"reset", true}, {"c", c}});
	CHECK(over.status == Status::Rejected && over.events.empty());
}

// ---- messages that stay ignored -----------------------------------------------------------------------------------------

TEST(messages_of_the_other_direction_are_ignored)
{
	for (const char *text : {R"({"t":"mobdmg","id":1,"frac":0.5,"by":null})", R"({"t":"pcells","c":[1,2,3]})",
							 R"({"t":"unlead","target":"1"})", R"({"t":"scaninfo","id":1,"ok":false})",
							 R"({"t":"binds","keys":{"hack":71,"skills":75}})", R"({"t":"mobs","list":[]})",
							 R"({"t":"flight","active":false})", R"({"t":"proj","list":[]})"})
	{
		const cb::world::Parsed p = parse(text);
		CHECK(p.status == Status::Ignored && p.events.empty());
	}
}

// ---- the queue ---------------------------------------------------------------------------------------------------------

TEST(inactive_queue_keeps_block_perk_scan_and_hack_events_only)
{
	cb::world::EventQueue q;
	for (EventKind kind : {EventKind::Explosion, EventKind::Hit, EventKind::Ignite, EventKind::Teleport, EventKind::Lead,
						   EventKind::LeadRelease, EventKind::Pull, EventKind::Knock, EventKind::Effect, EventKind::PerkFx})
		q.push(event(kind));
	CHECK(q.stats().queued == 0); // transient: nothing can act on them while not Active
	Event removed = event(EventKind::BlockRemove);
	removed.pos = {1, 0, 0};
	for (const Event &e : {event(EventKind::BlockReset), event(EventKind::BlockAdd), removed, event(EventKind::Perk),
						   event(EventKind::Scan), event(EventKind::Hack)})
		q.push(e);
	CHECK(q.stats().queued == 6);
	// the FIFO kinds first, then the reset, then the block changes
	for (EventKind kind : {EventKind::Perk, EventKind::Scan, EventKind::Hack, EventKind::BlockReset, EventKind::BlockAdd,
						   EventKind::BlockRemove})
		CHECK(q.next() == kind);
	CHECK(q.next() == EventKind::None);
}

TEST(active_queue_keeps_everything_and_deactivating_discards_only_the_transient_events)
{
	cb::world::EventQueue q;
	q.set_active(true);
	const EventKind all[] = {EventKind::Hack,    EventKind::Teleport, EventKind::Perk,       EventKind::Lead,
							 EventKind::Scan,    EventKind::Knock,    EventKind::BlockReset, EventKind::Effect,
							 EventKind::PerkFx,  EventKind::Pull,     EventKind::LeadRelease, EventKind::BlockAdd};
	for (EventKind kind : all)
		q.push(event(kind));
	CHECK(q.stats().queued == 12);
	q.set_active(false);
	CHECK(q.stats().queued == 5);
	for (EventKind kind : {EventKind::Hack, EventKind::Perk, EventKind::Scan, EventKind::BlockReset, EventKind::BlockAdd})
		CHECK(q.next() == kind);
	CHECK(q.next() == EventKind::None);
	// a hack queued while inactive survives the activation and is answered once Active
	q.push(event(EventKind::Hack));
	q.set_active(true);
	CHECK(q.next() == EventKind::Hack);
}

TEST(the_current_event_keeps_name_extra_and_id)
{
	cb::world::EventQueue q;
	q.set_active(true);
	for (const char *text : {R"({"t":"effect","name":"speed","seconds":9,"amp":3})",
							 R"({"t":"hack","id":12,"target":"34","hack":"ping","chain":true})",
							 R"({"t":"knock","pos":[0,0,0],"radius":4,"strength":7})", R"({"t":"hit","target":"1","dmg":2,"mob":6})"})
		q.push(one(text));
	CHECK(q.next() == EventKind::Effect && q.current().name == "speed" && approx(q.current().extra, 3));
	CHECK(q.next() == EventKind::Hack && q.current().name == "ping" && q.current().id == 12 && q.current().flag);
	CHECK(q.next() == EventKind::Knock && approx(q.current().extra, 7) && approx(q.current().amount, 4));
	CHECK(q.next() == EventKind::Hit && q.current().id == 6);
	CHECK(q.next() == EventKind::None);
	CHECK(q.current().name.empty() && q.current().id == 0 && q.current().extra == 0);
}

TEST(event_names_are_the_section_1_3_kinds)
{
	const std::pair<EventKind, const char *> names[] = {
		{EventKind::BlockReset, "block_reset"}, {EventKind::Teleport, "teleport"}, {EventKind::Lead, "lead"},
		{EventKind::LeadRelease, "lead_release"}, {EventKind::Pull, "pull"},   {EventKind::Knock, "knock"},
		{EventKind::Effect, "effect"},         {EventKind::Hack, "hack"},         {EventKind::Scan, "scan"},
		{EventKind::Perk, "perk"},             {EventKind::PerkFx, "perkfx"}};
	for (const auto &[kind, name] : names)
		CHECK(std::string(cb::world::event_name(kind)) == name);
}

// ---- the link observer -------------------------------------------------------------------------------------------------

TEST(observer_queues_every_event_of_a_blocks_and_a_perks_message)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::world::on_minecraft_message(json::parse(R"({"t":"blocks","reset":true,"c":[1,2,3,4,5,6]})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"perks","list":[{"id":"dodge","rank":1}]})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hack","id":1,"target":"2","hack":"ping","chain":false})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"scan","id":2,"target":null})"));
	CHECK(q.stats().queued == 6); // reset, 2 adds, perk, hack, scan: all kept while inactive
	for (EventKind kind : {EventKind::Perk, EventKind::Hack, EventKind::Scan, EventKind::BlockReset, EventKind::BlockAdd,
						   EventKind::BlockAdd})
		CHECK(q.next() == kind);
	q.clear();
}

TEST(observer_counts_every_bad_message_and_bad_list_entry)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(true);
	const uint64_t before = q.stats().rejected;
	cb::world::on_minecraft_message(json::parse(R"({"t":"perks","list":[{"id":"dodge","rank":1},{"id":"nope","rank":1},{"id":"x","rank":1}]})"));
	CHECK(q.stats().rejected == before + 2 && q.stats().queued == 1);
	cb::world::on_minecraft_message(json::parse(R"({"t":"effect","name":"Speed","seconds":1,"amp":0})"));
	CHECK(q.stats().rejected == before + 3 && q.stats().queued == 1);
	cb::world::on_minecraft_message(json::parse(R"({"t":"blocks","reset":true,"c":[1,2,3,0.5,0,0]})"));
	CHECK(q.stats().rejected == before + 4 && q.stats().queued == 3); // + reset + one cell
	q.set_active(false);
	q.clear();
}

TEST(a_hello_clears_the_kept_events_too)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::world::on_minecraft_message(json::parse(R"({"t":"perks","list":[{"id":"dodge","rank":1}]})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hack","id":1,"target":"2","hack":"ping"})"));
	CHECK(q.stats().queued == 2);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(q.stats().queued == 0 && q.next() == EventKind::None);
}

// ---- re-test 2 (plan 2026-10-03-retest2 sections 1.2, 1.3 and 1.7): cyberware and cyberfx --------------------------------

TEST(cyberware_becomes_one_cyber_event_per_entry_in_order)
{
	const cb::world::Parsed p = parse(R"({"t":"cyberware","list":[{"id":"mantis_blades","on":1},{"id":"sandevistan","on":0}]})");
	CHECK(p.status == Status::Event && p.droppedEntries == 0 && p.events.size() == 2);
	CHECK(p.events[0].kind == EventKind::Cyber && p.events[0].name == "mantis_blades" && approx(p.events[0].amount, 1));
	CHECK(p.events[1].kind == EventKind::Cyber && p.events[1].name == "sandevistan" && approx(p.events[1].amount, 0));
	CHECK(approx(one(R"({"t":"cyberware","list":[{"id":"cyberdeck","on":1.0}]})").amount, 1)); // a whole float
}

TEST(all_seventeen_cyberware_ids_are_accepted_in_the_section_1_7_order)
{
	const char *const expected[] = {"mantis_blades",  "gorilla_arms",   "monowire",     "projectile_launch_system",
									"reinforced_tendons", "fortified_ankles", "lynx_paws", "sandevistan",
									"berserk",        "cyberdeck",      "kerenzikov",   "optical_camo",
									"subdermal_armor", "kiroshi_optics", "biomonitor",  "second_heart",
									"ram_upgrade"};
	CHECK(std::size(cb::world::kCyberwareIds) == 17 && std::size(expected) == 17);
	json list = json::array();
	for (size_t i = 0; i < 17; ++i)
	{
		CHECK(std::string(cb::world::kCyberwareIds[i]) == expected[i]);
		list.push_back({{"id", expected[i]}, {"on", int(i % 2)}});
	}
	const cb::world::Parsed p = cb::world::parse_event({{"t", "cyberware"}, {"list", list}});
	CHECK(p.status == Status::Event && p.events.size() == 17 && p.droppedEntries == 0);
	for (size_t i = 0; i < 17; ++i)
		CHECK(p.events[i].name == expected[i] && approx(p.events[i].amount, double(i % 2)));
}

TEST(a_bad_cyberware_entry_is_dropped_and_counted_the_rest_is_kept)
{
	const cb::world::Parsed p = parse(
		R"({"t":"cyberware","list":[{"id":"monowire","on":1},{"id":"jetpack","on":1},{"id":"berserk","on":2},)"
		R"(7,{"id":"berserk"},{"on":1},{"id":"berserk","on":0.5},{"id":"Berserk","on":1},{"id":"berserk","on":true},)"
		R"({"id":"biomonitor","on":0}]})");
	CHECK(p.status == Status::Event && p.droppedEntries == 8 && !p.reason.empty());
	CHECK(p.events.size() == 2 && p.events[0].name == "monowire" && p.events[1].name == "biomonitor");
	// past 32 entries the rest is dropped
	json list = json::array();
	for (int i = 0; i < 35; ++i)
		list.push_back({{"id", "berserk"}, {"on", 1}});
	const cb::world::Parsed many = cb::world::parse_event({{"t", "cyberware"}, {"list", list}});
	CHECK(many.status == Status::Event && many.events.size() == 32 && many.droppedEntries == 3);
}

TEST(cyberware_without_a_list_is_rejected_and_an_empty_list_is_valid)
{
	CHECK(rejected(R"({"t":"cyberware"})"));
	CHECK(rejected(R"({"t":"cyberware","list":{"id":"berserk","on":1}})"));
	const cb::world::Parsed empty = parse(R"({"t":"cyberware","list":[]})");
	CHECK(empty.status == Status::Event && empty.events.empty() && empty.droppedEntries == 0);
}

TEST(cyberfx_fills_id_and_seconds_within_0_30)
{
	for (const char *id : cb::world::kCyberFxIds)
	{
		json m = {{"t", "cyberfx"}, {"id", id}, {"seconds", 6}};
		const cb::world::Parsed p = cb::world::parse_event(m);
		CHECK(p.status == Status::Event && p.events.size() == 1 && p.events[0].kind == EventKind::CyberFx);
		CHECK(p.events[0].name == id && approx(p.events[0].amount, 6));
	}
	CHECK(std::size(cb::world::kCyberFxIds) == 3);
	CHECK(one(R"({"t":"cyberfx","id":"sandevistan","seconds":0})").kind == EventKind::CyberFx); // 0 = end now
	CHECK(one(R"({"t":"cyberfx","id":"kerenzikov","seconds":30})").kind == EventKind::CyberFx);
	CHECK(rejected(R"({"t":"cyberfx","id":"sandevistan","seconds":30.5})"));
	CHECK(rejected(R"({"t":"cyberfx","id":"sandevistan","seconds":-1})"));
	CHECK(rejected(R"({"t":"cyberfx","id":"optical_camo","seconds":5})")); // not one of the three effects
	CHECK(rejected(R"({"t":"cyberfx","seconds":5})"));
	CHECK(rejected(R"({"t":"cyberfx","id":"berserk"})"));
	json nan_seconds = {{"t", "cyberfx"}, {"id", "berserk"}, {"seconds", kNaN}};
	CHECK(invalid(nan_seconds));
	// perkfx is still accepted
	CHECK(one(R"({"t":"perkfx","id":"sandevistan","seconds":5})").kind == EventKind::PerkFx);
}

TEST(cyber_is_kept_while_inactive_and_cyberfx_is_dropped)
{
	cb::world::EventQueue q;
	q.push(event(EventKind::CyberFx));
	CHECK(q.stats().queued == 0);
	q.push(event(EventKind::Cyber));
	CHECK(q.stats().queued == 1);
	q.set_active(true);
	q.push(event(EventKind::CyberFx));
	CHECK(q.stats().queued == 2);
	q.set_active(false); // the cyberfx goes, the install state stays
	CHECK(q.stats().queued == 1 && q.next() == EventKind::Cyber);
	// a full queue drops transient events first: a cyber event outranks them like a perk
	cb::world::EventQueue full;
	full.set_active(true);
	full.push(event(EventKind::Cyber));
	for (int i = 0; i < 256; ++i)
		full.push(event(EventKind::CyberFx));
	CHECK(full.next() == EventKind::Cyber);
}

TEST(cyber_event_names_are_cyber_and_cyberfx)
{
	CHECK(std::string(cb::world::event_name(EventKind::Cyber)) == "cyber");
	CHECK(std::string(cb::world::event_name(EventKind::CyberFx)) == "cyberfx");
}

TEST(the_observer_queues_a_cyber_event_per_entry_while_inactive)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::world::on_minecraft_message(json::parse(R"({"t":"cyberware","list":[{"id":"kiroshi_optics","on":1},{"id":"x","on":1}]})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"cyberfx","id":"sandevistan","seconds":6})")); // transient: dropped
	CHECK(q.stats().queued == 1 && q.next() == EventKind::Cyber && q.current().name == "kiroshi_optics");
	CHECK(q.current().amount == 1.0);
	q.clear();
}

TEST(four_key_binds_and_align_stay_out_of_the_event_queue)
{
	for (const char *text : {R"({"t":"binds","keys":{"hack":71,"skills":75,"cyberware":85,"activate":89}})", R"({"t":"align","k":57})"})
		CHECK(parse(text).status == Status::Ignored);
}
