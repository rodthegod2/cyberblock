// Layer 2 in the DLL: parsing and converting Minecraft's events, the bounded event queue, and the dmg / npcs messages.
#include "test.hpp"
#include "world.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iterator>
#include <limits>
#include <string>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::Event;
	using cb::world::EventKind;
	using Status = cb::world::Parsed::Status;

	constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
	constexpr double kInf = std::numeric_limits<double>::infinity();

	cb::world::Parsed parse(const char *text)
	{
		return cb::world::parse_event(json::parse(text));
	}

	bool rejected(const char *text)
	{
		const cb::world::Parsed p = parse(text);
		return p.status == Status::Rejected && !p.reason.empty();
	}

	bool ignored(const json &message)
	{
		return cb::world::parse_event(message).status == Status::Ignored;
	}

	bool approx(double a, double b)
	{
		return std::abs(a - b) <= 1e-9;
	}

	bool approx(const cb::Vec3 &a, double x, double y, double z)
	{
		return approx(a.x, x) && approx(a.y, y) && approx(a.z, z);
	}

	Event explosion(double power)
	{
		Event e;
		e.kind = EventKind::Explosion;
		e.amount = power;
		return e;
	}

	Event block(EventKind kind, double x)
	{
		Event e;
		e.kind = kind;
		e.pos = {x, 0, 0};
		return e;
	}
}

// ---- parse_event: accepted ---------------------------------------------------------------------------------------

TEST(block_add_becomes_the_cyberpunk_aabb_min_corner)
{
	const auto p = parse(R"({"t":"block","op":"add","pos":[10,4,-21]})");
	CHECK(p.status == Status::Event);
	CHECK(p.events.at(0).kind == EventKind::BlockAdd);
	CHECK(approx(p.events.at(0).pos, 10, 20, 4)); // plan 1.1: block (10,4,-21) -> AABB min (10,20,4)
}

TEST(block_remove_and_whole_numbers_written_as_floats)
{
	const auto p = parse(R"({"t":"block","op":"remove","pos":[-1.0,-1,-1.0]})");
	CHECK(p.status == Status::Event);
	CHECK(p.events.at(0).kind == EventKind::BlockRemove);
	CHECK(approx(p.events.at(0).pos, -1, 0, -1)); // block (-1,-1,-1) -> min (-1, -(-1+1), -1)
}

TEST(explosion_converts_position_and_keeps_power_and_fire)
{
	const auto p = parse(R"({"t":"explosion","pos":[1.5,3,-2],"power":4,"fire":true})");
	CHECK(p.status == Status::Event);
	CHECK(p.events.at(0).kind == EventKind::Explosion);
	CHECK(approx(p.events.at(0).pos, 1.5, 2, 3));
	CHECK(approx(p.events.at(0).amount, 4));
	CHECK(p.events.at(0).flag);
	CHECK(!parse(R"({"t":"explosion","pos":[0,0,0],"power":1.5})").events.at(0).flag); // fire defaults to false
}

TEST(hit_converts_impulse_and_scales_damage_to_a_fraction)
{
	const auto p = parse(R"({"t":"hit","target":"18446744073709551615","dmg":10,"kind":"melee","impulse":[1,2,3]})");
	CHECK(p.status == Status::Event);
	CHECK(p.events.at(0).kind == EventKind::Hit);
	CHECK(p.events.at(0).target == 18446744073709551615ull);
	CHECK(approx(p.events.at(0).amount, 0.5)); // dmg / 20
	CHECK(approx(p.events.at(0).vector, 1, -3, 2));
	const auto bare = parse(R"({"t":"hit","target":"7","dmg":3})");
	CHECK(bare.status == Status::Event);
	CHECK(approx(bare.events.at(0).vector, 0, 0, 0));
}

TEST(ignite_keeps_target_and_seconds)
{
	const auto p = parse(R"({"t":"ignite","target":"42","seconds":5})");
	CHECK(p.status == Status::Event);
	CHECK(p.events.at(0).kind == EventKind::Ignite);
	CHECK(p.events.at(0).target == 42 && approx(p.events.at(0).amount, 5));
}

TEST(limits_are_inclusive)
{
	CHECK(parse(R"({"t":"explosion","pos":[0,0,0],"power":0})").status == Status::Event);
	CHECK(parse(R"({"t":"explosion","pos":[0,0,0],"power":20})").status == Status::Event);
	CHECK(parse(R"({"t":"explosion","pos":[29999999,0,-29999999],"power":4})").status == Status::Event);
	CHECK(parse(R"({"t":"hit","target":"1","dmg":0})").status == Status::Event);
	CHECK(parse(R"({"t":"hit","target":"1","dmg":1000})").status == Status::Event);
	CHECK(parse(R"({"t":"ignite","target":"1","seconds":0})").status == Status::Event);
	CHECK(parse(R"({"t":"ignite","target":"1","seconds":120})").status == Status::Event);
	CHECK(parse(R"({"t":"block","op":"add","pos":[29999999,-64,-29999999]})").status == Status::Event);
}

// ---- parse_event: ignored and rejected ---------------------------------------------------------------------------

TEST(other_messages_are_ignored)
{
	CHECK(parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})").status == Status::Ignored);
	CHECK(parse(R"({"t":"status","hp":20})").status == Status::Ignored);
	CHECK(parse(R"({"t":"from_layer_9"})").status == Status::Ignored);
	CHECK(ignored(json::parse(R"({"pos":[0,0,0]})")));        // no t
	CHECK(ignored(json::parse(R"({"t":5,"pos":[0,0,0]})")));  // t is not a string
	CHECK(ignored(json::parse(R"(["explosion"])")));
	CHECK(ignored(json::parse(R"("explosion")")));
	CHECK(ignored(json()));
}

TEST(bad_blocks_are_rejected)
{
	CHECK(rejected(R"({"t":"block","op":"toggle","pos":[0,0,0]})"));
	CHECK(rejected(R"({"t":"block","pos":[0,0,0]})"));
	CHECK(rejected(R"({"t":"block","op":1,"pos":[0,0,0]})"));
	CHECK(rejected(R"({"t":"block","op":"add"})"));
	CHECK(rejected(R"({"t":"block","op":"add","pos":[0,0]})"));
	CHECK(rejected(R"({"t":"block","op":"add","pos":[0,0,0,0]})"));
	CHECK(rejected(R"({"t":"block","op":"add","pos":"0,0,0"})"));
	CHECK(rejected(R"({"t":"block","op":"add","pos":[0,"1",0]})"));
	CHECK(rejected(R"({"t":"block","op":"add","pos":[0.5,0,0]})"));    // not a whole number
	CHECK(rejected(R"({"t":"block","op":"add","pos":[30000000,0,0]})")); // |coord| < 3e7
	CHECK(rejected(R"({"t":"block","op":"add","pos":[0,0,-30000000]})"));
}

TEST(bad_explosions_are_rejected)
{
	CHECK(rejected(R"({"t":"explosion","power":4})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0,0]})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0,0],"power":-0.1})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0,0],"power":20.5})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0,0],"power":"4"})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0,0],"power":4,"fire":1})")); // present, but not a bool
	CHECK(rejected(R"({"t":"explosion","pos":[3e7,0,0],"power":4})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,-3e7,0],"power":4})"));
	CHECK(rejected(R"({"t":"explosion","pos":[0,0],"power":4})"));
	CHECK(rejected(R"({"t":"explosion","pos":null,"power":4})"));
}

TEST(non_finite_numbers_built_in_memory_are_rejected)
{
	// JSON text cannot carry NaN or infinity (the parser throws on 1e999, which Link::handle logs), but a json value can
	json inf_block = {{"t", "block"}, {"op", "add"}, {"pos", {kInf, 0, 0}}};
	json nan_pos = {{"t", "explosion"}, {"pos", {kNaN, 0, 0}}, {"power", 4}};
	json nan_power = {{"t", "explosion"}, {"pos", {0, 0, 0}}, {"power", kNaN}};
	json inf_power = {{"t", "explosion"}, {"pos", {0, 0, 0}}, {"power", kInf}};
	json nan_dmg = {{"t", "hit"}, {"target", "1"}, {"dmg", kNaN}};
	json inf_impulse = {{"t", "hit"}, {"target", "1"}, {"dmg", 1}, {"impulse", {0, kInf, 0}}};
	json nan_seconds = {{"t", "ignite"}, {"target", "1"}, {"seconds", kNaN}};
	for (const json *m : {&inf_block, &nan_pos, &nan_power, &inf_power, &nan_dmg, &inf_impulse, &nan_seconds})
		CHECK(cb::world::parse_event(*m).status == Status::Rejected);
}

TEST(bad_hits_are_rejected)
{
	CHECK(rejected(R"({"t":"hit","dmg":3})"));
	CHECK(rejected(R"({"t":"hit","target":"1"})"));
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":-0.5})"));
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":1000.5})"));
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":"3"})"));
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":3,"impulse":[1,2]})")); // present but malformed
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":3,"impulse":[1,2,3e7]})"));
	CHECK(rejected(R"({"t":"hit","target":"1","dmg":3,"impulse":5})"));
}

TEST(bad_ids_are_rejected)
{
	for (const char *id : {"", "-1", "+1", " 1", "1 ", "12a", "0x10", "1.5", "18446744073709551616", "999999999999999999999"})
	{
		json hit = {{"t", "hit"}, {"target", id}, {"dmg", 1}};
		json ignite = {{"t", "ignite"}, {"target", id}, {"seconds", 1}};
		CHECK(cb::world::parse_event(hit).status == Status::Rejected);
		CHECK(cb::world::parse_event(ignite).status == Status::Rejected);
	}
	CHECK(rejected(R"({"t":"hit","target":5,"dmg":1})"));   // numbers lose precision; the wire uses strings
	CHECK(rejected(R"({"t":"hit","target":null,"dmg":1})"));
	CHECK(parse(R"({"t":"hit","target":"0042","dmg":1})").events.at(0).target == 42); // leading zeros are still decimal
}

TEST(bad_ignites_are_rejected)
{
	CHECK(rejected(R"({"t":"ignite","target":"1"})"));
	CHECK(rejected(R"({"t":"ignite","target":"1","seconds":-1})"));
	CHECK(rejected(R"({"t":"ignite","target":"1","seconds":120.5})"));
	CHECK(rejected(R"({"t":"ignite","seconds":5})"));
}

// ---- EventQueue ---------------------------------------------------------------------------------------------------

TEST(empty_queue_has_no_event_and_resets_the_current_one)
{
	cb::world::EventQueue q;
	q.set_active(true);
	CHECK(q.next() == EventKind::None);
	CHECK(q.current().kind == EventKind::None);
	q.push(explosion(3));
	CHECK(q.next() == EventKind::Explosion);
	CHECK(approx(q.current().amount, 3));
	CHECK(q.next() == EventKind::None); // drained: the current event is cleared too
	CHECK(q.current().kind == EventKind::None && q.current().amount == 0);
}

TEST(events_come_out_in_order_and_stay_current_until_the_next_pop)
{
	cb::world::EventQueue q;
	q.set_active(true);
	for (int i = 1; i <= 3; ++i)
		q.push(explosion(i));
	CHECK(q.stats().queued == 3);
	for (int i = 1; i <= 3; ++i)
	{
		CHECK(q.next() == EventKind::Explosion);
		CHECK(approx(q.current().amount, i));
		CHECK(approx(q.current().amount, i)); // reading does not consume
	}
	CHECK(q.stats().queued == 0);
}

TEST(queue_holds_256_events_and_drops_the_oldest)
{
	cb::world::EventQueue q;
	q.set_active(true);
	for (int i = 1; i <= 300; ++i)
		q.push(explosion(i));
	const cb::world::QueueStats s = q.stats();
	CHECK(s.queued == 256 && s.dropped == 44);
	CHECK(q.next() == EventKind::Explosion);
	CHECK(approx(q.current().amount, 45)); // 1..44 are gone
	for (int i = 0; i < 255; ++i)
		q.next();
	CHECK(approx(q.current().amount, 300));
	CHECK(q.next() == EventKind::None);
	CHECK(q.stats().dropped == 44); // the count is cumulative
}

TEST(inactive_queue_discards_transient_events_but_keeps_blocks)
{
	cb::world::EventQueue q;
	q.push(explosion(4));
	Event hit;
	hit.kind = EventKind::Hit;
	q.push(hit);
	q.push(block(EventKind::BlockAdd, 1));
	CHECK(q.stats().queued == 1);
	CHECK(q.next() == EventKind::BlockAdd);
}

TEST(each_change_of_active_discards_queued_transient_events_only)
{
	cb::world::EventQueue q;
	q.set_active(true);
	q.push(explosion(4));
	q.push(block(EventKind::BlockAdd, 1));
	Event ignite;
	ignite.kind = EventKind::Ignite;
	q.push(ignite);
	q.push(block(EventKind::BlockRemove, 2));
	q.set_active(true); // no change: nothing is discarded
	CHECK(q.stats().queued == 4);
	q.set_active(false);
	CHECK(q.stats().queued == 2);
	q.set_active(true); // activation: still the two blocks, in order, and no stale explosion
	CHECK(q.next() == EventKind::BlockAdd);
	CHECK(q.next() == EventKind::BlockRemove);
	CHECK(q.next() == EventKind::None);
}

TEST(a_resync_burst_while_inactive_loses_no_block_change)
{
	// Minecraft resyncs while Cyberpunk is still loading: nothing drains the queue meanwhile.
	cb::world::EventQueue q;
	Event reset;
	reset.kind = EventKind::BlockReset;
	q.push(reset);
	for (int i = 0; i < 5000; ++i)
		q.push(block(EventKind::BlockAdd, i));
	for (int i = 0; i < 300; ++i) // a burst of other kept events on top
	{
		Event perk;
		perk.kind = EventKind::Perk;
		q.push(perk);
	}
	CHECK(q.stats().queued == 1 + 5000 + 256 && q.stats().dropped == 44);
	for (int i = 0; i < 256; ++i)
		CHECK(q.next() == EventKind::Perk);
	CHECK(q.next() == EventKind::BlockReset);
	for (int i = 0; i < 5000; ++i)
		CHECK(q.next() == EventKind::BlockAdd && approx(q.current().pos.x, i));
	CHECK(q.next() == EventKind::None);
}

TEST(block_changes_keep_the_last_one_per_cell_and_a_reset_forgets_the_earlier_ones)
{
	cb::world::EventQueue q;
	q.push(block(EventKind::BlockAdd, 1));
	q.push(block(EventKind::BlockRemove, 1)); // the same cell: only the remove is left
	q.push(block(EventKind::BlockAdd, 2));
	CHECK(q.stats().queued == 2);
	CHECK(q.next() == EventKind::BlockRemove && approx(q.current().pos.x, 1));
	CHECK(q.next() == EventKind::BlockAdd && approx(q.current().pos.x, 2));

	q.push(block(EventKind::BlockAdd, 3));
	Event reset;
	reset.kind = EventKind::BlockReset;
	q.push(reset); // the add of cell 3 is moot
	q.push(block(EventKind::BlockAdd, 4));
	q.push(reset); // so is the first reset and the add of cell 4
	q.push(block(EventKind::BlockAdd, 5));
	CHECK(q.stats().queued == 2);
	CHECK(q.next() == EventKind::BlockReset);
	CHECK(q.next() == EventKind::BlockAdd && approx(q.current().pos.x, 5));
	CHECK(q.next() == EventKind::None);
}

TEST(a_full_queue_drops_transient_events_before_kept_ones)
{
	cb::world::EventQueue q;
	q.set_active(true);
	Event hack;
	hack.kind = EventKind::Hack;
	hack.id = 7;
	q.push(hack);
	for (int i = 1; i <= 256; ++i)
		q.push(explosion(i)); // explosion 1 makes way for 256
	CHECK(q.stats().queued == 256 && q.stats().dropped == 1);
	CHECK(q.next() == EventKind::Hack && q.current().id == 7);
	CHECK(q.next() == EventKind::Explosion && approx(q.current().amount, 2));

	cb::world::EventQueue kept;
	kept.set_active(true);
	for (int i = 0; i < 256; ++i)
	{
		hack.id = i;
		kept.push(hack);
	}
	kept.push(explosion(1)); // nothing transient to drop: the new one goes
	CHECK(kept.stats().queued == 256 && kept.stats().dropped == 1);
	CHECK(kept.next() == EventKind::Hack && kept.current().id == 0);
}

TEST(clear_forgets_everything_including_the_current_event)
{
	cb::world::EventQueue q;
	q.set_active(true);
	q.push(explosion(2));
	q.push(explosion(3));
	q.next();
	q.clear();
	CHECK(q.stats().queued == 0);
	CHECK(q.current().kind == EventKind::None);
	CHECK(q.next() == EventKind::None);
}

TEST(rejections_are_counted)
{
	cb::world::EventQueue q;
	q.note_rejected();
	q.note_rejected();
	CHECK(q.stats().rejected == 2);
}

TEST(event_names_match_the_natives_contract)
{
	CHECK(std::string(cb::world::event_name(EventKind::Explosion)) == "explosion");
	CHECK(std::string(cb::world::event_name(EventKind::Hit)) == "hit");
	CHECK(std::string(cb::world::event_name(EventKind::Ignite)) == "ignite");
	CHECK(std::string(cb::world::event_name(EventKind::BlockAdd)) == "block_add");
	CHECK(std::string(cb::world::event_name(EventKind::BlockRemove)) == "block_remove");
	CHECK(std::string(cb::world::event_name(EventKind::None)).empty());
}

// ---- the link observer (feeds the plugin's global queue) ----------------------------------------------------------

TEST(observer_queues_valid_events_and_counts_invalid_ones)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(true);
	const uint64_t rejectedBefore = q.stats().rejected;
	cb::world::on_minecraft_message(json::parse(R"({"t":"explosion","pos":[1,2,3],"power":4,"fire":true})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hit","target":"9","dmg":5})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hit","target":"nine","dmg":5})")); // invalid: counted, not queued
	cb::world::on_minecraft_message(json::parse(R"({"t":"status","hp":20})"));             // not ours: ignored
	cb::world::on_minecraft_message(json::parse(R"([1,2])"));
	CHECK(q.stats().queued == 2);
	CHECK(q.stats().rejected == rejectedBefore + 1);
	CHECK(q.next() == EventKind::Explosion && q.current().flag);
	CHECK(q.next() == EventKind::Hit && q.current().target == 9 && approx(q.current().amount, 0.25));
	CHECK(q.next() == EventKind::None);
	q.set_active(false);
}

TEST(observer_forgets_the_old_session_on_hello)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(true);
	cb::world::on_minecraft_message(json::parse(R"({"t":"explosion","pos":[0,0,0],"power":1})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"block","op":"add","pos":[0,0,0]})"));
	CHECK(q.stats().queued == 2);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(q.stats().queued == 0);
	CHECK(q.next() == EventKind::None);
	q.set_active(false);
}

// ---- dmg ---------------------------------------------------------------------------------------------------------

TEST(dmg_message_converts_from_to_minecraft)
{
	const auto m = cb::world::make_dmg(0.25, "bullet", {1.0, 2.0, 3.0}, true, 0.0);
	CHECK(m.has_value());
	CHECK(m->at("t") == "dmg" && m->at("kind") == "bullet");
	CHECK(approx(m->at("frac"), 0.25));
	CHECK(m->at("from").is_array() && m->at("from").size() == 3);
	CHECK(approx(m->at("from")[0], 1) && approx(m->at("from")[1], 3) && approx(m->at("from")[2], -2)); // cp (1,2,3) -> mc (1,3,-2)
}

TEST(dmg_from_is_null_without_a_source_or_with_a_bad_one)
{
	CHECK(cb::world::make_dmg(1.0, "melee", {1, 2, 3}, false, 0.0)->at("from").is_null());
	CHECK(cb::world::make_dmg(1.0, "melee", {kNaN, 2, 3}, true, 0.0)->at("from").is_null());
	CHECK(cb::world::make_dmg(1.0, "melee", {1, kInf, 3}, true, 0.0)->at("from").is_null());
}

TEST(dmg_frac_is_clamped_to_0_4_and_must_be_finite)
{
	CHECK(approx(cb::world::make_dmg(7.5, "fall", {}, false, 0.0)->at("frac"), 4));
	CHECK(approx(cb::world::make_dmg(4.0, "fall", {}, false, 0.0)->at("frac"), 4));
	CHECK(approx(cb::world::make_dmg(-1.0, "fall", {}, false, 0.0)->at("frac"), 0));
	CHECK(approx(cb::world::make_dmg(0.0, "fall", {}, false, 0.0)->at("frac"), 0));
	CHECK(!cb::world::make_dmg(kNaN, "fall", {}, false, 0.0));
	CHECK(!cb::world::make_dmg(kInf, "fall", {}, false, 0.0));
	CHECK(!cb::world::make_dmg(-kInf, "fall", {}, false, 0.0));
}

TEST(dmg_kind_is_one_of_the_known_seven_else_other)
{
	for (const char *kind : cb::world::kDamageKinds)
		CHECK(cb::world::make_dmg(0.5, kind, {}, false, 0.0)->at("kind") == kind);
	CHECK(cb::world::make_dmg(0.5, "", {}, false, 0.0)->at("kind") == "other");
	CHECK(cb::world::make_dmg(0.5, "Bullet", {}, false, 0.0)->at("kind") == "other"); // case matters
	CHECK(cb::world::make_dmg(0.5, "laser", {}, false, 0.0)->at("kind") == "other");
}

TEST(dmg_message_is_valid_json_text)
{
	// what actually goes on the wire: no NaN or null where a number belongs
	const std::string text = cb::world::make_dmg(0.1, "bullet", {0.1, 0.2, 0.3}, true, 0.0)->dump();
	const json back = json::parse(text);
	CHECK(back.at("frac").is_number() && back.at("from")[0].is_number());
}

// ---- npcs --------------------------------------------------------------------------------------------------------

TEST(npcs_message_converts_positions_ids_and_yaw)
{
	const std::vector<uint64_t> ids{18446744073709551615ull, 7};
	const std::vector<cb::Vec3> feet{{10.0, 20.0, 5.0}, {-3.5, 8.25, 70.0}};
	const std::vector<double> yaws{90.0, 0.0};
	const std::vector<uint8_t> vehicles{0, 1};
	const std::vector<uint8_t> hostile{1, 0};
	const auto m = cb::world::make_npcs(ids, feet, yaws, vehicles, hostile, 0.0);
	CHECK(m.has_value());
	CHECK(m->at("t") == "npcs");
	const json &list = m->at("list");
	CHECK(list.size() == 2);
	CHECK(list[0].at("id") == "18446744073709551615"); // decimal string, not a number
	CHECK(approx(list[0].at("p")[0], 10) && approx(list[0].at("p")[1], 5) && approx(list[0].at("p")[2], -20)); // cp (10,20,5) -> mc (10,5,-20)
	CHECK(approx(list[0].at("yaw"), 90)); // cp 90 (facing -X) is mc 90 (also -X)
	CHECK(list[0].at("kind") == "human" && list[0].at("hostile") == true);
	CHECK(list[1].at("id") == "7");
	CHECK(approx(list[1].at("p")[0], -3.5) && approx(list[1].at("p")[1], 70) && approx(list[1].at("p")[2], -8.25));
	CHECK(approx(list[1].at("yaw"), 180)); // cp 0 (facing +Y) is mc 180 (facing -Z)
	CHECK(list[1].at("kind") == "vehicle" && list[1].at("hostile") == false);
}

TEST(npcs_empty_list_is_still_sent_so_proxies_despawn)
{
	const auto m = cb::world::make_npcs({}, {}, {}, {}, {}, 0.0);
	CHECK(m.has_value());
	CHECK(m->at("list").is_array() && m->at("list").empty());
}

TEST(npcs_arrays_of_different_length_are_dropped)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {1, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> flags{0, 0};
	CHECK(cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0).has_value());
	const std::vector<uint64_t> oneId{1};
	const std::vector<cb::Vec3> oneFoot{{0, 0, 0}};
	const std::vector<double> oneYaw{0};
	const std::vector<uint8_t> oneFlag{0};
	CHECK(!cb::world::make_npcs(oneId, feet, yaws, flags, flags, 0.0));
	CHECK(!cb::world::make_npcs(ids, oneFoot, yaws, flags, flags, 0.0));
	CHECK(!cb::world::make_npcs(ids, feet, oneYaw, flags, flags, 0.0));
	CHECK(!cb::world::make_npcs(ids, feet, yaws, oneFlag, flags, 0.0));
	CHECK(!cb::world::make_npcs(ids, feet, yaws, flags, oneFlag, 0.0));
}

TEST(npcs_entries_with_non_finite_numbers_are_left_out)
{
	const std::vector<uint64_t> ids{1, 2, 3};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {kNaN, 0, 0}, {5, 6, 7}};
	const std::vector<double> yaws{0, 0, kInf};
	const std::vector<uint8_t> flags{0, 0, 0};
	const json list = cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0)->at("list");
	CHECK(list.size() == 1 && list[0].at("id") == "1");
	CHECK(json::parse(cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0)->dump()).at("list").size() == 1);
}

TEST(npcs_dead_flag_is_sent_only_when_set)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {1, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> flags{0, 0};
	const std::vector<uint8_t> dead{0, 1};
	const json list = cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0, dead)->at("list");
	CHECK(!list[0].contains("dead"));
	CHECK(list[1].at("dead") == true);
	CHECK(!cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0)->at("list")[1].contains("dead"));
	const std::vector<uint8_t> oneDead{1};
	CHECK(!cb::world::make_npcs(ids, feet, yaws, flags, flags, 0.0, oneDead));
}

TEST(cp_yaw_to_mc_yaw_agrees_with_the_quaternion_conversion)
{
	// a Cyberpunk yaw of a degrees about +Z is the quaternion (0, 0, sin(a/2), cos(a/2)); cbmath's truth gives the
	// Minecraft yaw of its forward vector
	for (int a = -350; a <= 350; a += 25)
	{
		const double h = a * 3.14159265358979323846 / 360.0;
		const double want = cb::cp_quat_to_mc_yaw_pitch(0, 0, std::sin(h), std::cos(h)).yaw;
		const double got = cb::world::cp_yaw_to_mc_yaw(a);
		const double d = std::fmod(std::abs(got - want), 360.0);
		CHECK(std::min(d, 360.0 - d) <= 1e-9);
		CHECK(got > -180.0 - 1e-9 && got <= 180.0 + 1e-9);
	}
	CHECK(approx(cb::world::cp_yaw_to_mc_yaw(0), 180));
	CHECK(approx(cb::world::cp_yaw_to_mc_yaw(90), 90));
	CHECK(approx(cb::world::cp_yaw_to_mc_yaw(180), 0));
	CHECK(approx(cb::world::cp_yaw_to_mc_yaw(-90), -90));
}

// ---- re-test 2 (plan 2026-10-03-retest2 sections 1.1 and 1.2): the ground alignment -------------------------------------

namespace
{
	constexpr double kOff57 = 57.0 / 64.0; // 0.890625

	cb::world::Parsed parse_at(const char *text, double off)
	{
		return cb::world::parse_event(json::parse(text), off);
	}
}

TEST(block_events_sit_at_by_plus_off)
{
	const auto p = parse_at(R"({"t":"block","op":"add","pos":[10,4,-21]})", kOff57);
	CHECK(approx(p.events.at(0).pos, 10, 20, 4.890625));
	const auto b = parse_at(R"({"t":"blocks","reset":true,"c":[10,4,-21, 0,70,0]})", kOff57);
	CHECK(b.events.size() == 3 && approx(b.events[1].pos, 10, 20, 4.890625) && approx(b.events[2].pos, 0, -1, 70.890625));
	CHECK(approx(parse(R"({"t":"block","op":"add","pos":[10,4,-21]})").events.at(0).pos, 10, 20, 4)); // the test default is k 0
}

TEST(event_positions_take_plus_off_and_the_impulse_does_not)
{
	CHECK(approx(parse_at(R"({"t":"explosion","pos":[1.5,3,-2],"power":4})", kOff57).events.at(0).pos, 1.5, 2, 3.890625));
	CHECK(approx(parse_at(R"({"t":"teleport","p":[10.5,64,-20.25],"yaw":0})", kOff57).events.at(0).pos, 10.5, 20.25, 64.890625));
	CHECK(approx(parse_at(R"({"t":"lead","target":"5","anchor":[1,2,3],"length":8})", kOff57).events.at(0).pos, 1, -3, 2.890625));
	CHECK(approx(parse_at(R"({"t":"pull","target":"5","toward":[4,70,-9],"strength":1})", kOff57).events.at(0).pos, 4, 9, 70.890625));
	CHECK(approx(parse_at(R"({"t":"knock","pos":[1.5,3,-2],"radius":6,"strength":9})", kOff57).events.at(0).pos, 1.5, 2, 3.890625));
	const auto hit = parse_at(R"({"t":"hit","target":"9","dmg":10,"impulse":[1,2,3]})", kOff57);
	CHECK(approx(hit.events.at(0).vector, 1, -3, 2)); // a velocity: never offset
	CHECK(approx(parse_at(R"({"t":"teleport","p":[0,0,0],"yaw":90})", kOff57).events.at(0).amount, 90)); // nor a yaw
}

TEST(align_sets_k_and_a_hello_resets_it)
{
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(cb::world::level_k() == 0);
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":57})"));
	CHECK(cb::world::level_k() == 57 && cb::world::level_offset(cb::world::level_k()) == 0.890625);
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":63.0})")); // a whole number written as a float
	CHECK(cb::world::level_k() == 63);
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":0})"));
	CHECK(cb::world::level_k() == 0);
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":12})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	CHECK(cb::world::level_k() == 0); // a new Minecraft session sends its own align
}

TEST(a_bad_align_is_counted_and_keeps_the_old_k)
{
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":32})"));
	const uint64_t before = cb::world::events().stats().rejected;
	for (const char *text : {R"({"t":"align","k":64})", R"({"t":"align","k":-1})", R"({"t":"align","k":1.5})",
							 R"({"t":"align","k":"5"})", R"({"t":"align"})", R"({"t":"align","k":null})"})
		cb::world::on_minecraft_message(json::parse(text));
	CHECK(cb::world::events().stats().rejected == before + 6);
	CHECK(cb::world::level_k() == 32);
	json nan_k = {{"t", "align"}, {"k", kNaN}};
	cb::world::on_minecraft_message(nan_k);
	CHECK(cb::world::level_k() == 32);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
}

TEST(the_observer_converts_with_the_current_alignment)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::world::on_minecraft_message(json::parse(R"({"t":"align","k":57})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"block","op":"add","pos":[0,70,0]})"));
	CHECK(q.next() == EventKind::BlockAdd && approx(q.current().pos, 0, -1, 70.890625));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"block","op":"add","pos":[0,70,0]})"));
	CHECK(q.next() == EventKind::BlockAdd && approx(q.current().pos, 0, -1, 70));
	CHECK(cb::world::parse_event(json::parse(R"({"t":"align","k":5})")).status == Status::Ignored); // not an event
	q.clear();
}

// ---- Phase Q (megaplan sections 1.9 and Q-D1): prefs ------------------------------------------------------------------

namespace
{
	using cb::world::ViewPrefs;

	ViewPrefs prefs_of(const json &message, const ViewPrefs &current, size_t &bad)
	{
		std::string why;
		const ViewPrefs p = cb::world::parse_prefs(message, current, bad, why);
		CHECK((bad == 0) == why.empty());
		return p;
	}
}

TEST(prefs_defaults_are_3_2_m_0_45_m_right_game_fov_recenter_on_game_vehicle_distance_stepup_on)
{
	const ViewPrefs d;
	CHECK(approx(d.dist, 3.2) && approx(d.shoulder, 0.45) && approx(d.fov, 0) && d.recenter && approx(d.vdist, 0) &&
		  d.stepup && !d.debug);
}

TEST(prefs_parse_every_field_and_ignore_others)
{
	size_t bad = 9;
	const ViewPrefs p = prefs_of(json::parse(R"({"t":"prefs","dist":5,"shoulder":-0.3,"fov":90,"recenter":false,
		"vdist":6.5,"stepup":false,"debug":true,"scale":0.75})"), {}, bad);
	CHECK(bad == 0);
	CHECK(approx(p.dist, 5) && approx(p.shoulder, -0.3) && approx(p.fov, 90) && !p.recenter && approx(p.vdist, 6.5) &&
		  !p.stepup && p.debug);
}

TEST(prefs_numbers_are_clamped_into_their_ranges)
{
	size_t bad = 0;
	ViewPrefs p = prefs_of(json::parse(R"({"dist":0.5,"shoulder":-4,"fov":20,"vdist":1})"), {}, bad);
	CHECK(bad == 0 && approx(p.dist, 1.5) && approx(p.shoulder, -1) && approx(p.fov, 50) && approx(p.vdist, 3));
	p = prefs_of(json::parse(R"({"dist":80,"shoulder":4,"fov":170,"vdist":99})"), {}, bad);
	CHECK(bad == 0 && approx(p.dist, 8) && approx(p.shoulder, 1) && approx(p.fov, 110) && approx(p.vdist, 14));
	p = prefs_of(json::parse(R"({"fov":-5,"vdist":-1})"), {}, bad); // below 0: the game's own
	CHECK(bad == 0 && approx(p.fov, 0) && approx(p.vdist, 0));
	p = prefs_of(json::parse(R"({"fov":0,"vdist":0})"), ViewPrefs{.fov = 80, .vdist = 5}, bad);
	CHECK(bad == 0 && approx(p.fov, 0) && approx(p.vdist, 0));
}

TEST(prefs_keep_a_missing_or_bad_field_and_count_the_bad_ones)
{
	const ViewPrefs current{.dist = 4, .shoulder = 0.2, .fov = 70, .recenter = false, .vdist = 5, .stepup = false};
	size_t bad = 0;
	ViewPrefs p = prefs_of(json::parse(R"({"t":"prefs"})"), current, bad); // nothing given: nothing changes
	CHECK(bad == 0 && p == current);
	p = prefs_of(json::parse(R"({"dist":"far","shoulder":true,"fov":null,"recenter":1,"stepup":"yes","vdist":[3]})"),
				 current, bad);
	CHECK(bad == 6 && p == current);
	json nan = {{"dist", kNaN}, {"shoulder", kInf}, {"fov", 75}};
	p = prefs_of(nan, current, bad);
	CHECK(bad == 2 && approx(p.dist, 4) && approx(p.shoulder, 0.2) && approx(p.fov, 75));
	p = prefs_of(json::array({1, 2}), current, bad); // not an object
	CHECK(bad == 0 && p == current);
}

// shared/vectors.json `prefs_apply` (tools/cbmath.py apply_prefs): the same rules as tools and Minecraft's protocol tests
TEST(parse_prefs_matches_the_shared_prefs_apply_vectors_and_their_debug_flag_drives_vdbg)
{
	std::ifstream file(CB_VECTORS);
	const json cases = json::parse(file).at("prefs_apply");
	CHECK(cases.size() >= 12);
	const auto prefs_from = [](const json &o)
	{
		return ViewPrefs{.dist = o.at("dist").get<double>(),
						 .shoulder = o.at("shoulder").get<double>(),
						 .fov = o.at("fov").get<double>(),
						 .recenter = o.at("recenter").get<bool>(),
						 .vdist = o.at("vdist").get<double>(),
						 .stepup = o.at("stepup").get<bool>(),
						 .debug = o.at("debug").get<bool>()};
	};
	constexpr std::array<double, 3> kValues{3.2, 2.85, 14.0};
	size_t debugOn = 0;
	for (const auto &c : cases)
	{
		size_t bad = 99;
		std::string why;
		const ViewPrefs got = cb::world::parse_prefs(c.at("given"), prefs_from(c.at("current")), bad, why);
		const ViewPrefs expect = prefs_from(c.at("expect"));
		CHECK(approx(got.dist, expect.dist) && approx(got.shoulder, expect.shoulder) && approx(got.fov, expect.fov) &&
			  approx(got.vdist, expect.vdist) && got.recenter == expect.recenter && got.stepup == expect.stepup &&
			  got.debug == expect.debug);
		CHECK(bad == c.at("bad").get<size_t>());
		if (prefs_from(c.at("current")) == ViewPrefs{})
		{
			// the same message through the link after a hello (the defaults): vdbg follows the resulting flag
			cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
			json message = c.at("given");
			message["t"] = "prefs";
			cb::world::on_minecraft_message(message);
			CHECK(cb::world::vdbg_to_send(kValues).has_value() == expect.debug);
			debugOn += expect.debug ? 1 : 0;
		}
	}
	CHECK(debugOn >= 1);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	cb::world::events().clear();
}

TEST(the_prefs_event_carries_vdist_as_its_amount_for_the_vehicle_camera_scripts)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","vdist":6.5})"));
	CHECK(q.next() == EventKind::Prefs && approx(q.current().amount, 6.5) && q.current().flag);
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","vdist":99})")); // clamped like the prefs
	CHECK(q.next() == EventKind::Prefs && approx(q.current().amount, 14));
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","vdist":0})")); // the game's own
	CHECK(q.next() == EventKind::Prefs && approx(q.current().amount, 0));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	q.clear();
}

TEST(prefs_reach_the_scripts_as_the_event_prefs_with_stepup_and_reset_on_hello_and_drop)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false); // a setting: kept while Cyberblock is off
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	CHECK(cb::world::view_prefs() == ViewPrefs{});
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","dist":2,"shoulder":0,"fov":0,"recenter":true,"vdist":0,"stepup":false})"));
	CHECK(approx(cb::world::view_prefs().dist, 2) && approx(cb::world::view_prefs().shoulder, 0) && !cb::world::view_prefs().stepup);
	CHECK(q.next() == EventKind::Prefs && !q.current().flag);
	CHECK(std::string(cb::world::event_name(EventKind::Prefs)) == "prefs");
	q.set_active(true);
	const uint64_t rejectedBefore = q.stats().rejected;
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","dist":"x","stepup":true})")); // one bad field: counted
	CHECK(q.stats().rejected == rejectedBefore + 1);
	CHECK(approx(cb::world::view_prefs().dist, 2) && cb::world::view_prefs().stepup);
	CHECK(q.next() == EventKind::Prefs && q.current().flag);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	CHECK(cb::world::view_prefs() == ViewPrefs{});
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","dist":6})"));
	cb::world::on_link_dropped();
	CHECK(cb::world::view_prefs() == ViewPrefs{});
	CHECK(cb::world::parse_event(json::parse(R"({"t":"prefs","dist":6})")).status == Status::Ignored); // not parse_event's
	q.set_active(false);
	q.clear();
}

TEST(a_contract_exact_six_field_prefs_leaves_the_debug_flag_and_only_the_debug_signal_turns_vdbg_on)
{
	constexpr std::array<double, 3> kValues{3.2, 2.85, 14.0};
	const json six = json::parse(R"({"t":"prefs","dist":3.2,"shoulder":0.45,"fov":0,"recenter":true,"vdist":0,"stepup":true})");
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	cb::world::on_minecraft_message(six);
	CHECK(!cb::world::view_prefs().debug && !cb::world::vdbg_to_send(kValues)); // default: off
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","debug":true})")); // the (pending) debug signal
	CHECK(cb::world::view_prefs().debug && cb::world::vdbg_to_send(kValues) == kValues);
	cb::world::on_minecraft_message(six); // a six-field prefs after it keeps the flag
	CHECK(cb::world::view_prefs().debug && cb::world::vdbg_to_send(kValues));
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","debug":false})"));
	CHECK(!cb::world::vdbg_to_send(kValues));
	cb::world::on_minecraft_message(json::parse(R"({"t":"prefs","debug":true})"));
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})")); // a new session starts with it off
	CHECK(!cb::world::vdbg_to_send(kValues));
	cb::world::events().clear();
}

TEST(cam_carries_vdbg_only_when_given_and_clamped_to_its_ranges)
{
	const json plain = cb::world::make_cam(1, {}, {}, 70.0, {}, 0.0, {}, false, false, 0);
	CHECK(!plain.contains("vdbg"));
	const json debug = cb::world::make_cam(2, {}, {}, 70.0, {}, 0.0, {}, false, false, 0, std::array<double, 3>{3.2, 2.85, 14.0});
	CHECK(debug["vdbg"].size() == 3 && approx(debug["vdbg"][0].get<double>(), 3.2) &&
		  approx(debug["vdbg"][1].get<double>(), 2.85) && approx(debug["vdbg"][2].get<double>(), 14.0));
	const json wild = cb::world::make_cam(3, {}, {}, 70.0, {}, 0.0, {}, false, false, 0, std::array<double, 3>{-1.0, 500.0, kNaN});
	CHECK(approx(wild["vdbg"][0].get<double>(), 0) && approx(wild["vdbg"][1].get<double>(), 100) &&
		  approx(wild["vdbg"][2].get<double>(), 0));
	CHECK(approx(cb::world::make_cam(4, {}, {}, 70.0, {}, 0.0, {}, false, false, 0, std::array<double, 3>{0, 0, 1e9})["vdbg"][2].get<double>(), 60000));
}

TEST(a_slider_burst_of_prefs_keeps_one_queued_prefs_event_with_the_newest_stepup)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	q.set_active(false);
	const uint64_t droppedBefore = q.stats().dropped;
	cb::world::on_minecraft_message(json::parse(R"({"t":"perks","list":[{"id":"dodge","rank":1}]})"));
	for (int i = 0; i < 500; ++i) // more than the queue holds: the perk must survive
		cb::world::on_minecraft_message(json{{"t", "prefs"}, {"dist", 2.0 + i * 0.01}, {"stepup", i % 2 == 0}});
	cb::world::on_minecraft_message(json{{"t", "prefs"}, {"stepup", false}});
	CHECK(q.stats().queued == 2 && q.stats().dropped == droppedBefore);
	CHECK(q.next() == EventKind::Perk);
	CHECK(q.next() == EventKind::Prefs && !q.current().flag);
	CHECK(q.next() == EventKind::None);
	cb::world::on_link_dropped();
	q.clear();
}

// ---- Phase 3 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, task 3-D1) --------------------------

TEST(vteleport_becomes_an_event_in_cyberpunk_space)
{
	const double off = 57.0 / 64.0;
	const cb::world::Parsed p = cb::world::parse_event(
		json::parse(R"({"t":"vteleport","id":7,"p":[1.5,64,-2.5],"yaw":90,"why":"meetup"})"), off);
	CHECK(p.status == Status::Event && p.events.size() == 1);
	const Event &e = p.events.at(0);
	CHECK(e.kind == EventKind::VTeleport);
	CHECK(e.id == 7);
	const cb::Vec3 cp = cb::mc_to_cp_pos(1.5, 64.0, -2.5, off);
	CHECK(approx(e.pos, cp.x, cp.y, cp.z));
	CHECK(approx(e.amount, cb::mc_yaw_to_cp_yaw(90.0)));
	CHECK(e.name == "meetup");
	for (const char *why : {"holo", "hub_exit"})
	{
		json m = json::parse(R"({"t":"vteleport","id":0,"p":[0,0,0],"yaw":0})");
		m["why"] = why;
		CHECK(cb::world::parse_event(m).events.at(0).name == why);
	}
	CHECK(std::string(cb::world::event_name(EventKind::VTeleport)) == "vteleport");
}

TEST(a_bad_vteleport_is_rejected)
{
	CHECK(rejected(R"({"t":"vteleport","p":[0,0,0],"yaw":0,"why":"meetup"})"));            // no id
	CHECK(rejected(R"({"t":"vteleport","id":-1,"p":[0,0,0],"yaw":0,"why":"meetup"})"));    // negative id
	CHECK(rejected(R"({"t":"vteleport","id":1.5,"p":[0,0,0],"yaw":0,"why":"meetup"})"));   // not whole
	CHECK(rejected(R"({"t":"vteleport","id":1,"yaw":0,"why":"meetup"})"));                 // no p
	CHECK(rejected(R"({"t":"vteleport","id":1,"p":[0,0],"yaw":0,"why":"meetup"})"));       // p not a triple
	CHECK(rejected(R"({"t":"vteleport","id":1,"p":[0,4e7,0],"yaw":0,"why":"meetup"})"));   // out of range
	CHECK(rejected(R"({"t":"vteleport","id":1,"p":[0,0,0],"why":"meetup"})"));             // no yaw
	CHECK(rejected(R"({"t":"vteleport","id":1,"p":[0,0,0],"yaw":0,"why":"party"})"));      // unknown why
	CHECK(rejected(R"({"t":"vteleport","id":1,"p":[0,0,0],"yaw":0})"));                    // no why
}

TEST(park_becomes_an_event_with_its_flag)
{
	const cb::world::Parsed on = parse(R"({"t":"park","on":true})");
	CHECK(on.status == Status::Event && on.events.size() == 1);
	CHECK(on.events.at(0).kind == EventKind::Park && on.events.at(0).flag);
	const cb::world::Parsed off = parse(R"({"t":"park","on":false})");
	CHECK(off.events.at(0).kind == EventKind::Park && !off.events.at(0).flag);
	CHECK(rejected(R"({"t":"park"})"));
	CHECK(rejected(R"({"t":"park","on":1})"));
	CHECK(std::string(cb::world::event_name(EventKind::Park)) == "park");
}

// ---- Phase 7 (megaplan 2026-10-04 sections 1.8 and 1.9, task 7-D1) ---------------------------------------------------------

TEST(hub_becomes_an_event_with_its_flag)
{
	const cb::world::Parsed in = parse(R"({"t":"hub","in":true})");
	CHECK(in.status == Status::Event && in.events.size() == 1);
	CHECK(in.events.at(0).kind == EventKind::Hub && in.events.at(0).flag);
	const cb::world::Parsed out = parse(R"({"t":"hub","in":false})");
	CHECK(out.status == Status::Event && out.events.at(0).kind == EventKind::Hub && !out.events.at(0).flag);
	CHECK(std::string(cb::world::event_name(EventKind::Hub)) == "hub");
}

TEST(a_bad_hub_is_rejected)
{
	CHECK(rejected(R"({"t":"hub"})"));
	CHECK(rejected(R"({"t":"hub","in":1})"));
	CHECK(rejected(R"({"t":"hub","in":"true"})"));
	CHECK(rejected(R"({"t":"hub","in":null})"));
	CHECK(rejected(R"({"t":"hub","on":true})")); // the field is `in`, not `on`
}

TEST(hub_is_kept_while_not_active)
{
	// the hub is state, like a park: Hub.reds re-parks V while its copy says in, so an `out` must reach it whatever the state
	cb::world::EventQueue q; // not Active
	Event h;
	h.kind = EventKind::Hub;
	h.flag = false;
	q.push(h);
	q.set_active(true); // switching keeps it too
	CHECK(q.next() == EventKind::Hub && !q.current().flag);
	q.set_active(false);
	h.flag = true;
	q.push(h);
	CHECK(q.next() == EventKind::Hub && q.current().flag);
}

TEST(vteleport_and_park_are_kept_while_not_active)
{
	// the server waits for `vteleported` / `parked`: the scripts must see the request (and refuse it) whatever the state
	cb::world::EventQueue q; // not Active
	Event v;
	v.kind = EventKind::VTeleport;
	v.id = 3;
	q.push(v);
	Event p;
	p.kind = EventKind::Park;
	p.flag = true;
	q.push(p);
	q.set_active(true);
	q.set_active(false); // a state change drops transient events only
	CHECK(q.next() == EventKind::VTeleport && q.current().id == 3);
	CHECK(q.next() == EventKind::Park && q.current().flag);
	CHECK(q.next() == EventKind::None);
}

TEST(teleport_reasons_follow_the_contract)
{
	for (const char *good : {"", "no ground", "timeout", "blocked (in a vehicle)", "blocked (combat)"})
		CHECK(cb::world::valid_teleport_reason(good));
	for (const char *bad : {"nope", "blocked", "blocked ()", "blocked (x", "blocked x)", "Timeout", "no ground ",
							"blocked (a\nb)"})
		CHECK(!cb::world::valid_teleport_reason(bad));
	CHECK(!cb::world::valid_teleport_reason("blocked (" + std::string(60, 'x') + ")")); // over 64 bytes
}

TEST(vteleported_carries_the_snapped_feet_in_minecraft_space)
{
	const double off = 0.5;
	const auto ok = cb::world::make_vteleported(7, true, "", {10.0, 20.0, 5.25}, off);
	CHECK(ok.has_value());
	CHECK((*ok)["t"] == "vteleported" && (*ok)["id"] == 7 && (*ok)["ok"] == true && (*ok)["reason"] == "");
	const cb::Vec3 mc = cb::cp_to_mc_pos(10.0, 20.0, 5.25, off);
	CHECK(approx((*ok)["p"][0].get<double>(), mc.x) && approx((*ok)["p"][1].get<double>(), mc.y) &&
		  approx((*ok)["p"][2].get<double>(), mc.z));
	// ok sends no reason even if one was given
	CHECK((*cb::world::make_vteleported(7, true, "timeout", {}, 0.0))["reason"] == "");
	const auto failed = cb::world::make_vteleported(8, false, "blocked (in a vehicle)", {}, 0.0);
	CHECK(failed && (*failed)["ok"] == false && (*failed)["reason"] == "blocked (in a vehicle)");
	CHECK(!cb::world::make_vteleported(8, false, "", {}, 0.0));         // a failure must say why
	CHECK(!cb::world::make_vteleported(8, false, "it broke", {}, 0.0)); // not a contract reason
	CHECK(!cb::world::make_vteleported(-1, true, "", {}, 0.0));
	CHECK(!cb::world::make_vteleported(1, true, "", {kNaN, 0.0, 0.0}, 0.0));
}

TEST(parked_answers_a_park)
{
	const auto on = cb::world::make_parked(true, true, "");
	CHECK(on && *on == json({{"t", "parked"}, {"on", true}, {"ok", true}, {"reason", ""}}));
	const auto refused = cb::world::make_parked(true, false, "blocked (in combat)");
	CHECK(refused && (*refused)["ok"] == false && (*refused)["reason"] == "blocked (in combat)");
	CHECK((*cb::world::make_parked(false, true, "timeout"))["reason"] == "");
	CHECK(!cb::world::make_parked(false, false, ""));
	CHECK(!cb::world::make_parked(false, false, "whatever"));
}

TEST(weather_presets_map_to_the_env_enum)
{
	const auto w = [](const char *preset) { return std::string(cb::world::weather_from_preset(preset)); };
	CHECK(w("24h_weather_sunny") == "clear");
	CHECK(w("24H_Weather_Sunny") == "clear");
	CHECK(w("24h_weather_deep_blue") == "clear");
	CHECK(w("24h_weather_light_clouds") == "cloudy");
	CHECK(w("24h_weather_heavy_clouds") == "cloudy");
	CHECK(w("24h_weather_rain") == "rain");
	CHECK(w("24h_weather_toxic_rain") == "rain");
	CHECK(w("q306_rainy_night") == "rain");
	CHECK(w("24h_weather_storm") == "storm");
	CHECK(w("24h_weather_fog") == "fog");
	CHECK(w("24h_weather_pollution") == "smog");
	CHECK(w("24h_weather_sandstorm") == "sandstorm");
	bool known = true;
	CHECK(std::string(cb::world::weather_from_preset("q302_squat_morning", &known)) == "other" && !known);
	known = false;
	CHECK(std::string(cb::world::weather_from_preset("", &known)) == "other" && known); // nothing read: nothing to log
	for (const char *preset : {"24h_weather_sunny", "x", "24h_weather_fog"})
	{
		const std::string out = cb::world::weather_from_preset(preset);
		CHECK(std::find(std::begin(cb::world::kWeathers), std::end(cb::world::kWeathers), out) != std::end(cb::world::kWeathers));
	}
}

TEST(env_carries_the_game_time_and_the_weather)
{
	const auto env = cb::world::make_env(21, 7, "24h_weather_rain");
	CHECK(env && *env == json({{"t", "env"}, {"time", {21, 7}}, {"weather", "rain"}}));
	bool known = true;
	CHECK((*cb::world::make_env(0, 0, "mystery", &known))["weather"] == "other" && !known);
	CHECK(cb::world::make_env(23, 59, "").has_value());
	CHECK(!cb::world::make_env(24, 0, ""));
	CHECK(!cb::world::make_env(-1, 0, ""));
	CHECK(!cb::world::make_env(12, 60, ""));
	CHECK(!cb::world::make_env(12, -1, ""));
}

TEST(a_long_blocked_reason_is_shortened_not_dropped)
{
	const auto v = cb::world::make_vteleported(2, false, "blocked (" + std::string(100, 'x') + ")", {}, 0.0);
	CHECK(v.has_value());
	const std::string reason = (*v)["reason"];
	CHECK(reason.size() <= cb::world::kMaxTextBytes && reason.starts_with("blocked (xxx") && reason.ends_with("x)"));
	CHECK(cb::world::valid_teleport_reason(reason));
	CHECK(!cb::world::make_vteleported(2, false, std::string(100, 'x'), {}, 0.0)); // not a contract reason at any length
}

TEST(park_log_lines_become_parked_answers)
{
	using cb::world::parked_from_log;
	const auto on = parked_from_log("park: on (invisible, invulnerable, saving locked, movement and camera locked)");
	CHECK(on && *on == json({{"t", "parked"}, {"on", true}, {"ok", true}, {"reason", ""}}));
	CHECK((*parked_from_log("park: on (fallback: nearest fast-travel point X, 12 m away; unavailable: unseen)"))["ok"] == true);
	CHECK((*parked_from_log("park: on (already parked)"))["ok"] == true);
	const auto refused = parked_from_log("park: on refused (in a vehicle)");
	CHECK(refused && (*refused)["on"] == true && (*refused)["ok"] == false && (*refused)["reason"] == "blocked (in a vehicle)");
	const auto off = parked_from_log("park: off (requested)");
	CHECK(off && (*off)["on"] == false && (*off)["ok"] == true);
	CHECK((*parked_from_log("park: off (Cyberblock off, V back from the fast-travel point)"))["on"] == false);
	CHECK(!parked_from_log("teleport: 3 done at (1, 2, 3) after 2 s"));
	CHECK(!parked_from_log("park: maybe"));
	CHECK(!parked_from_log("park: on refused ()")); // "blocked ()" says nothing
}
