// Layer 2 in the DLL: parsing and converting Minecraft's events, the bounded event queue, and the dmg / npcs messages.
#include "test.hpp"
#include "world.hpp"
#include <cmath>
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
	const auto m = cb::world::make_dmg(0.25, "bullet", {1.0, 2.0, 3.0}, true);
	CHECK(m.has_value());
	CHECK(m->at("t") == "dmg" && m->at("kind") == "bullet");
	CHECK(approx(m->at("frac"), 0.25));
	CHECK(m->at("from").is_array() && m->at("from").size() == 3);
	CHECK(approx(m->at("from")[0], 1) && approx(m->at("from")[1], 3) && approx(m->at("from")[2], -2)); // cp (1,2,3) -> mc (1,3,-2)
}

TEST(dmg_from_is_null_without_a_source_or_with_a_bad_one)
{
	CHECK(cb::world::make_dmg(1.0, "melee", {1, 2, 3}, false)->at("from").is_null());
	CHECK(cb::world::make_dmg(1.0, "melee", {kNaN, 2, 3}, true)->at("from").is_null());
	CHECK(cb::world::make_dmg(1.0, "melee", {1, kInf, 3}, true)->at("from").is_null());
}

TEST(dmg_frac_is_clamped_to_0_4_and_must_be_finite)
{
	CHECK(approx(cb::world::make_dmg(7.5, "fall", {}, false)->at("frac"), 4));
	CHECK(approx(cb::world::make_dmg(4.0, "fall", {}, false)->at("frac"), 4));
	CHECK(approx(cb::world::make_dmg(-1.0, "fall", {}, false)->at("frac"), 0));
	CHECK(approx(cb::world::make_dmg(0.0, "fall", {}, false)->at("frac"), 0));
	CHECK(!cb::world::make_dmg(kNaN, "fall", {}, false));
	CHECK(!cb::world::make_dmg(kInf, "fall", {}, false));
	CHECK(!cb::world::make_dmg(-kInf, "fall", {}, false));
}

TEST(dmg_kind_is_one_of_the_known_seven_else_other)
{
	for (const char *kind : cb::world::kDamageKinds)
		CHECK(cb::world::make_dmg(0.5, kind, {}, false)->at("kind") == kind);
	CHECK(cb::world::make_dmg(0.5, "", {}, false)->at("kind") == "other");
	CHECK(cb::world::make_dmg(0.5, "Bullet", {}, false)->at("kind") == "other"); // case matters
	CHECK(cb::world::make_dmg(0.5, "laser", {}, false)->at("kind") == "other");
}

TEST(dmg_message_is_valid_json_text)
{
	// what actually goes on the wire: no NaN or null where a number belongs
	const std::string text = cb::world::make_dmg(0.1, "bullet", {0.1, 0.2, 0.3}, true)->dump();
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
	const auto m = cb::world::make_npcs(ids, feet, yaws, vehicles, hostile);
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
	const auto m = cb::world::make_npcs({}, {}, {}, {}, {});
	CHECK(m.has_value());
	CHECK(m->at("list").is_array() && m->at("list").empty());
}

TEST(npcs_arrays_of_different_length_are_dropped)
{
	const std::vector<uint64_t> ids{1, 2};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {1, 1, 1}};
	const std::vector<double> yaws{0, 0};
	const std::vector<uint8_t> flags{0, 0};
	CHECK(cb::world::make_npcs(ids, feet, yaws, flags, flags).has_value());
	const std::vector<uint64_t> oneId{1};
	const std::vector<cb::Vec3> oneFoot{{0, 0, 0}};
	const std::vector<double> oneYaw{0};
	const std::vector<uint8_t> oneFlag{0};
	CHECK(!cb::world::make_npcs(oneId, feet, yaws, flags, flags));
	CHECK(!cb::world::make_npcs(ids, oneFoot, yaws, flags, flags));
	CHECK(!cb::world::make_npcs(ids, feet, oneYaw, flags, flags));
	CHECK(!cb::world::make_npcs(ids, feet, yaws, oneFlag, flags));
	CHECK(!cb::world::make_npcs(ids, feet, yaws, flags, oneFlag));
}

TEST(npcs_entries_with_non_finite_numbers_are_left_out)
{
	const std::vector<uint64_t> ids{1, 2, 3};
	const std::vector<cb::Vec3> feet{{0, 0, 0}, {kNaN, 0, 0}, {5, 6, 7}};
	const std::vector<double> yaws{0, 0, kInf};
	const std::vector<uint8_t> flags{0, 0, 0};
	const json list = cb::world::make_npcs(ids, feet, yaws, flags, flags)->at("list");
	CHECK(list.size() == 1 && list[0].at("id") == "1");
	CHECK(json::parse(cb::world::make_npcs(ids, feet, yaws, flags, flags)->dump()).at("list").size() == 1);
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
