// Phase 8 (megaplan 2026-10-04 task 8-D1): `spawn` / `spawnresult`, `heat`, `attitude` and `glitch`.
#include "modes.hpp"
#include "probes.hpp"
#include "test.hpp"
#include "world.hpp"
#include <cmath>
#include <string>

namespace
{
	using nlohmann::json;
	using cb::modes::Request;
	using cb::world::EventKind;

	Request spawn(const char *text, double off = 0.0)
	{
		return cb::modes::parse_spawn(json::parse(text), off);
	}

	Request heat(const char *text)
	{
		return cb::modes::parse_heat(json::parse(text));
	}

	Request attitude(const char *text)
	{
		return cb::modes::parse_attitude(json::parse(text));
	}

	Request glitch(const char *text)
	{
		return cb::modes::parse_glitch(json::parse(text));
	}

	bool rejected(const Request &r)
	{
		return r.status == Request::Status::Rejected && !r.reason.empty() && !r.reply;
	}

	/// Pops every queued event (the queue is shared by the whole test run).
	void drain()
	{
		while (cb::world::events().next() != EventKind::None)
		{
		}
	}
}

TEST(modes_event_names_are_spawn_heat_attitude_and_glitch)
{
	CHECK(std::string(cb::world::event_name(EventKind::Spawn)) == "spawn");
	CHECK(std::string(cb::world::event_name(EventKind::Heat)) == "heat");
	CHECK(std::string(cb::world::event_name(EventKind::Attitude)) == "attitude");
	CHECK(std::string(cb::world::event_name(EventKind::Glitch)) == "glitch");
}

// ---- spawn ----

TEST(spawn_becomes_an_event_in_cyberpunk_space)
{
	const Request r = spawn(R"({"t":"spawn","id":12,"rec":"ganger","p":[10,64,-5],"n":3,"hostile":true,"faction":"tygerClaws"})",
							0.25);
	CHECK(r.status == Request::Status::Event && !r.reply);
	CHECK(r.event.kind == EventKind::Spawn && r.event.id == 12 && r.event.name == "ganger");
	CHECK(r.event.amount == 3.0 && r.event.flag && r.event.text == "tygerClaws");
	// Minecraft (x, y, z) -> Cyberpunk (x, -z, y + off)
	CHECK(r.event.pos.x == 10.0 && r.event.pos.y == 5.0 && r.event.pos.z == 64.25);
}

TEST(spawn_takes_every_record_kind_and_an_absent_or_empty_faction)
{
	for (const char *rec : cb::modes::kSpawnRecords)
	{
		json m = {{"t", "spawn"}, {"id", 1}, {"rec", rec}, {"p", {0, 0, 0}}, {"n", 1}, {"hostile", false}};
		const Request r = cb::modes::parse_spawn(m, 0.0);
		CHECK(r.status == Request::Status::Event && r.event.name == rec && !r.event.flag && r.event.text.empty());
		m["faction"] = "";
		CHECK(cb::modes::parse_spawn(m, 0.0).status == Request::Status::Event);
	}
	CHECK(spawn(R"({"t":"spawn","id":1,"rec":"maxtac","p":[0,0,0],"n":8,"hostile":true,"faction":"6thStreet"})").status ==
		  Request::Status::Event);
}

TEST(spawn_of_an_unknown_record_or_a_turret_is_answered_unsupported_at_once)
{
	for (const char *text : {R"({"t":"spawn","id":7,"rec":"turret","p":[0,0,0],"n":1,"hostile":true})",
							 R"({"t":"spawn","id":7,"rec":"dragon","p":[0,0,0],"n":1,"hostile":true})"})
	{
		const Request r = spawn(text);
		CHECK(r.status == Request::Status::Unsupported && r.reply);
		const json &reply = *r.reply;
		CHECK(reply["t"] == "spawnresult" && reply["id"] == 7 && reply["ok"] == false && reply["n"] == 0);
		CHECK(reply["reason"] == "unsupported");
	}
}

TEST(spawn_rejects_bad_fields)
{
	const char *bad[] = {
		R"({"t":"spawn","rec":"ganger","p":[0,0,0],"n":1,"hostile":true})",               // no id
		R"({"t":"spawn","id":-1,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true})",       // negative id
		R"({"t":"spawn","id":2147483648,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true})", // past Int32
		R"({"t":"spawn","id":1.5,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true})",      // not whole
		R"({"t":"spawn","id":1,"p":[0,0,0],"n":1,"hostile":true})",                       // no rec
		R"({"t":"spawn","id":1,"rec":5,"p":[0,0,0],"n":1,"hostile":true})",               // rec not a string
		R"({"t":"spawn","id":1,"rec":"ganger","n":1,"hostile":true})",                    // no p
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0],"n":1,"hostile":true})",          // p of two
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,4e7,0],"n":1,"hostile":true})",      // p out of range
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":0,"hostile":true})",        // n below 1
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":9,"hostile":true})",        // n above 8
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":2.5,"hostile":true})",      // n not whole
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":1})",                       // no hostile
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":1,"hostile":1})",           // hostile not a bool
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true,"faction":7})",
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true,"faction":"tyger claws"})",
		R"({"t":"spawn","id":1,"rec":"ganger","p":[0,0,0],"n":1,"hostile":true,"faction":"abcdefghijabcdefghijabcdefghijabc"})",
	};
	for (const char *text : bad)
		CHECK(rejected(spawn(text)));
	// an unknown record with a bad id is a bad message, not an unsupported one (no reply without an id to answer)
	CHECK(rejected(spawn(R"({"t":"spawn","id":-3,"rec":"dragon","p":[0,0,0],"n":1,"hostile":true})")));
}

TEST(spawn_parsers_ignore_other_types)
{
	CHECK(spawn(R"({"t":"rhack","id":1})").status == Request::Status::Ignored);
	CHECK(heat(R"({"t":"spawn","tier":1})").status == Request::Status::Ignored);
	CHECK(attitude(R"({"t":"heat","faction":"police","toward":"hostile"})").status == Request::Status::Ignored);
	CHECK(glitch(R"({"t":"attitude","seconds":1})").status == Request::Status::Ignored);
	CHECK(cb::modes::parse_glitch(json::array()).status == Request::Status::Ignored);
}

TEST(spawnresult_builder)
{
	auto m = cb::modes::make_spawnresult(12, true, 3, "ignored when ok");
	CHECK(m && (*m)["t"] == "spawnresult" && (*m)["id"] == 12 && (*m)["ok"] == true && (*m)["n"] == 3 && (*m)["reason"] == "");
	m = cb::modes::make_spawnresult(4, false, 0, "spawns are off");
	CHECK(m && (*m)["ok"] == false && (*m)["n"] == 0 && (*m)["reason"] == "spawns are off");
	// n is clamped to 0..8; a failure always says why; the reason is cut to 64 bytes without control characters
	CHECK((*cb::modes::make_spawnresult(1, true, 99, ""))["n"] == 8);
	CHECK((*cb::modes::make_spawnresult(1, false, -2, ""))["n"] == 0);
	CHECK((*cb::modes::make_spawnresult(1, false, 0, ""))["reason"] == "failed");
	CHECK((*cb::modes::make_spawnresult(1, false, 0, "a\nb\tc"))["reason"] == "abc");
	const std::string longReason(100, 'x');
	CHECK((*cb::modes::make_spawnresult(1, false, 0, longReason))["reason"].get<std::string>().size() == 64);
	CHECK(!cb::modes::make_spawnresult(-1, true, 1, ""));
	// exactly the four keys and `t`
	CHECK(cb::modes::make_spawnresult(9, true, 1, "")->size() == 5);
}

// ---- heat, attitude, glitch ----

TEST(heat_becomes_an_event_with_the_tier)
{
	for (int tier = 0; tier <= 5; ++tier)
	{
		const Request r = cb::modes::parse_heat(json{{"t", "heat"}, {"tier", tier}});
		CHECK(r.status == Request::Status::Event && r.event.kind == EventKind::Heat && r.event.amount == tier);
	}
	CHECK(rejected(heat(R"({"t":"heat"})")));
	CHECK(rejected(heat(R"({"t":"heat","tier":6})")));
	CHECK(rejected(heat(R"({"t":"heat","tier":-1})")));
	CHECK(rejected(heat(R"({"t":"heat","tier":2.5})")));
	CHECK(rejected(heat(R"({"t":"heat","tier":"3"})")));
}

TEST(attitude_becomes_an_event_with_faction_and_direction)
{
	const Request friendly = attitude(R"({"t":"attitude","faction":"tygerClaws","toward":"friendly"})");
	CHECK(friendly.status == Request::Status::Event && friendly.event.kind == EventKind::Attitude);
	CHECK(friendly.event.name == "tygerClaws" && friendly.event.text == "friendly" && friendly.event.amount == 1.0);
	const Request neutral = attitude(R"({"t":"attitude","faction":"maelstrom","toward":"neutral"})");
	CHECK(neutral.status == Request::Status::Event && neutral.event.amount == 0.0 && neutral.event.text == "neutral");
	const Request hostile = attitude(R"({"t":"attitude","faction":"6thStreet","toward":"hostile"})");
	CHECK(hostile.status == Request::Status::Event && hostile.event.amount == -1.0 && hostile.event.name == "6thStreet");
	CHECK(rejected(attitude(R"({"t":"attitude","toward":"hostile"})")));
	CHECK(rejected(attitude(R"({"t":"attitude","faction":"","toward":"hostile"})")));
	CHECK(rejected(attitude(R"({"t":"attitude","faction":"a-b","toward":"hostile"})")));
	CHECK(rejected(attitude(R"({"t":"attitude","faction":"police"})")));
	CHECK(rejected(attitude(R"({"t":"attitude","faction":"police","toward":"love"})")));
	CHECK(rejected(attitude(R"({"t":"attitude","faction":"police","toward":1})")));
}

TEST(glitch_becomes_an_event_with_the_seconds)
{
	const Request r = glitch(R"({"t":"glitch","seconds":2.5})");
	CHECK(r.status == Request::Status::Event && r.event.kind == EventKind::Glitch && r.event.amount == 2.5);
	CHECK(glitch(R"({"t":"glitch","seconds":0})").status == Request::Status::Event);
	CHECK(glitch(R"({"t":"glitch","seconds":10})").status == Request::Status::Event);
	CHECK(rejected(glitch(R"({"t":"glitch"})")));
	CHECK(rejected(glitch(R"({"t":"glitch","seconds":10.5})")));
	CHECK(rejected(glitch(R"({"t":"glitch","seconds":-1})")));
	CHECK(rejected(glitch(R"({"t":"glitch","seconds":"2"})")));
}

// ---- the observer: the queue, the state kept while not Active, the glitch's screen effect ----

TEST(modes_observer_queues_events_and_answers_unsupported_spawns)
{
	auto &q = cb::world::events();
	q.clear();
	q.set_active(true);
	cb::modes::on_minecraft_message(json::parse(R"({"t":"spawn","id":3,"rec":"drone","p":[1,2,3],"n":2,"hostile":true})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"attitude","faction":"police","toward":"hostile"})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":1})"));
	CHECK(q.next() == EventKind::Spawn && q.current().id == 3 && q.current().amount == 2.0);
	CHECK(q.next() == EventKind::Attitude && q.current().name == "police");
	CHECK(q.next() == EventKind::Glitch && q.current().amount == 1.0);
	CHECK(q.next() == EventKind::None);
	const uint64_t before = q.stats().rejected;
	cb::modes::on_minecraft_message(json::parse(R"({"t":"heat","tier":9})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"spawn","id":4,"rec":"turret","p":[1,2,3],"n":1,"hostile":true})"));
	CHECK(q.stats().rejected == before + 1); // the bad heat; the turret is answered, not counted
	CHECK(q.next() == EventKind::None);
	cb::modes::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})")); // other types: nothing
	CHECK(q.next() == EventKind::None);
	q.set_active(false);
}

TEST(modes_spawn_heat_and_attitude_are_kept_while_not_active_and_glitch_is_not)
{
	auto &q = cb::world::events();
	q.clear();
	q.set_active(false);
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":3})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"spawn","id":5,"rec":"ncpd","p":[0,0,0],"n":1,"hostile":true})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"heat","tier":2})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"attitude","faction":"valentinos","toward":"neutral"})"));
	CHECK(q.next() == EventKind::Spawn);
	CHECK(q.next() == EventKind::Heat && q.current().amount == 2.0);
	CHECK(q.next() == EventKind::Attitude);
	CHECK(q.next() == EventKind::None);
	drain();
}

TEST(modes_a_newer_heat_replaces_the_queued_one)
{
	auto &q = cb::world::events();
	q.clear();
	q.set_active(true);
	cb::modes::on_minecraft_message(json::parse(R"({"t":"heat","tier":1})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"attitude","faction":"animals","toward":"hostile"})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"heat","tier":4})"));
	CHECK(q.next() == EventKind::Heat && q.current().amount == 4.0); // in the first one's place
	CHECK(q.next() == EventKind::Attitude);
	CHECK(q.next() == EventKind::None);
	q.set_active(false);
}

TEST(modes_glitch_drives_cb_glitch_until_a_hello_a_link_drop_or_a_non_host_session)
{
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})")); // no session yet (acts as Host)
	cb::modes::glitch_clear();
	const double now = cb::steady_seconds();
	CHECK(cb::modes::glitch_uniform(now, false)[0] == 0.0f);
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":5})"));
	auto u = cb::modes::glitch_uniform(now + 1.0, false);
	CHECK(u[0] == 1.0f && std::abs(u[2] - 5.0f) < 1e-4f);
	CHECK(cb::modes::glitch_uniform(now + 1.0, true)[0] == 0.0f); // a Cyberpunk menu
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":0})")); // cured: eases out
	CHECK(cb::modes::glitch_uniform(cb::steady_seconds() + 1.0, false)[0] == 0.0f);

	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":5})"));
	cb::modes::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	CHECK(cb::modes::glitch_uniform(cb::steady_seconds() + 1.0, false)[0] == 0.0f);

	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":5})"));
	cb::modes::on_link_dropped();
	CHECK(cb::modes::glitch_uniform(cb::steady_seconds() + 1.0, false)[0] == 0.0f);

	// a session that is not host (world::on_minecraft_message runs first and takes it)
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":5})"));
	const json guest = json::parse(R"({"t":"session","state":"guest","reason":"","perms":{}})");
	cb::world::on_minecraft_message(guest);
	cb::modes::on_minecraft_message(guest);
	CHECK(cb::modes::glitch_uniform(cb::steady_seconds() + 1.0, false)[0] == 0.0f);
	// a bad glitch starts nothing
	cb::modes::on_minecraft_message(json::parse(R"({"t":"glitch","seconds":50})"));
	CHECK(cb::modes::glitch_uniform(cb::steady_seconds() + 1.0, false)[0] == 0.0f);
	cb::world::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})")); // forget the session for later tests
	drain();
}
