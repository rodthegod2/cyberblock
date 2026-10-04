// Phase 6 (megaplan 2026-10-04 task 6-D1): the `rhack` and `daemon` requests from Minecraft.
#include "rhack.hpp"
#include "test.hpp"
#include "world.hpp"
#include <string>

namespace
{
	using nlohmann::json;
	using cb::rhack::Request;
	using cb::world::EventKind;

	Request rhack(const char *text)
	{
		return cb::rhack::parse_rhack(json::parse(text));
	}

	Request daemon(const char *text)
	{
		return cb::rhack::parse_daemon(json::parse(text));
	}
}

TEST(rhack_event_names_are_rhack_and_daemon)
{
	CHECK(std::string(cb::world::event_name(EventKind::RHack)) == "rhack");
	CHECK(std::string(cb::world::event_name(EventKind::Daemon)) == "daemon");
}

TEST(rhack_with_a_vehicle_hack_becomes_an_event)
{
	const Request r = rhack(R"({"t":"rhack","id":41,"target":"18446744073709551615","hack":"veh_engine_off"})");
	CHECK(r.status == Request::Status::Event && !r.reply);
	CHECK(r.event.kind == EventKind::RHack && r.event.id == 41);
	CHECK(r.event.target == 18446744073709551615ull && r.event.name == "veh_engine_off");
}

TEST(rhack_takes_every_remote_hack_and_every_quickhack)
{
	for (const char *hack : cb::rhack::kRemoteHacks)
	{
		json m = {{"t", "rhack"}, {"id", 1}, {"target", "7"}, {"hack", hack}};
		CHECK(cb::rhack::parse_rhack(m).status == Request::Status::Event);
	}
	for (const char *hack : cb::world::kHackNames)
	{
		json m = {{"t", "rhack"}, {"id", 1}, {"target", "7"}, {"hack", hack}};
		CHECK(cb::rhack::parse_rhack(m).status == Request::Status::Event);
	}
	CHECK(cb::rhack::known_hack("blackout") && cb::rhack::known_hack("ping") && !cb::rhack::known_hack("nuke"));
}

TEST(rhack_area_hacks_may_come_without_a_target)
{
	for (const char *hack : {"traffic_switch", "gridlock", "blackout", "outline", "ping_area"})
	{
		CHECK(!cb::rhack::needs_target(hack));
		json absent = {{"t", "rhack"}, {"id", 2}, {"hack", hack}};
		json null = {{"t", "rhack"}, {"id", 2}, {"target", nullptr}, {"hack", hack}};
		const Request a = cb::rhack::parse_rhack(absent), b = cb::rhack::parse_rhack(null);
		CHECK(a.status == Request::Status::Event && a.event.target == 0);
		CHECK(b.status == Request::Status::Event && b.event.target == 0);
	}
	// a targeted hack needs its target
	for (const char *hack : {"veh_lock", "veh_engine_off", "veh_brake", "veh_start", "veh_jam", "cam_feed", "ping"})
	{
		CHECK(cb::rhack::needs_target(hack));
		json absent = {{"t", "rhack"}, {"id", 2}, {"hack", hack}};
		json zero = {{"t", "rhack"}, {"id", 2}, {"target", "0"}, {"hack", hack}};
		CHECK(cb::rhack::parse_rhack(absent).status == Request::Status::Rejected);
		CHECK(cb::rhack::parse_rhack(zero).status == Request::Status::Rejected);
	}
}

TEST(rhack_with_an_unknown_hack_is_answered_unsupported)
{
	const Request r = rhack(R"({"t":"rhack","id":9,"target":"5","hack":"orbital_strike"})");
	CHECK(r.status == Request::Status::Unsupported && r.reply);
	CHECK((*r.reply)["t"] == "hackresult" && (*r.reply)["id"] == 9 && (*r.reply)["ok"] == false);
	CHECK((*r.reply)["reason"] == "unsupported");
	CHECK(r.event.kind == EventKind::None);
}

TEST(a_bad_rhack_is_rejected_and_others_ignored)
{
	const auto rejected = [](const char *text) { return rhack(text).status == Request::Status::Rejected; };
	CHECK(rejected(R"({"t":"rhack","target":"5","hack":"veh_lock"})"));                  // no id
	CHECK(rejected(R"({"t":"rhack","id":-1,"target":"5","hack":"veh_lock"})"));          // negative
	CHECK(rejected(R"({"t":"rhack","id":1.5,"target":"5","hack":"veh_lock"})"));         // not whole
	CHECK(rejected(R"({"t":"rhack","id":2147483648,"target":"5","hack":"veh_lock"})"));  // past Int32
	CHECK(rejected(R"({"t":"rhack","id":1,"target":5,"hack":"veh_lock"})"));             // target not a string
	CHECK(rejected(R"({"t":"rhack","id":1,"target":"5x","hack":"veh_lock"})"));          // not decimal
	CHECK(rejected(R"({"t":"rhack","id":1,"target":"18446744073709551616","hack":"veh_lock"})")); // past uint64
	CHECK(rejected(R"({"t":"rhack","id":1,"target":"5"})"));                             // no hack
	CHECK(rejected(R"({"t":"rhack","id":1,"target":"5","hack":7})"));                    // hack not a string
	CHECK(rhack(R"({"t":"hack","id":1,"target":"5","hack":"ping"})").status == Request::Status::Ignored);
	CHECK(rhack(R"([1,2])").status == Request::Status::Ignored);
	CHECK(rhack(R"({"id":1})").status == Request::Status::Ignored);
}

TEST(daemon_becomes_one_event_with_the_ids_in_its_text)
{
	const Request r =
		daemon(R"({"t":"daemon","id":77,"dev":"12345","list":["cyberblock:disable_cameras","cyberblock:open_door"],"strength":3})");
	CHECK(r.status == Request::Status::Event && !r.reply);
	CHECK(r.event.kind == EventKind::Daemon && r.event.id == 77 && r.event.target == 12345);
	CHECK(r.event.amount == 3.0);
	CHECK(r.event.text == "cyberblock:disable_cameras,cyberblock:open_door");
	CHECK(r.event.name == "cyberblock:disable_cameras");
}

TEST(daemon_limits)
{
	const auto rejected = [](const char *text) { return daemon(text).status == Request::Status::Rejected; };
	CHECK(rejected(R"({"t":"daemon","dev":"5","list":["a"],"strength":1})"));                 // no id
	CHECK(rejected(R"({"t":"daemon","id":1,"list":["a"],"strength":1})"));                   // no dev
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"0","list":["a"],"strength":1})"));          // dev 0
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","strength":1})"));                       // no list
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":"a","strength":1})"));            // list not an array
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":[],"strength":1})"));             // empty
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":[3],"strength":1})"));            // id not a string
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":[""],"strength":1})"));           // empty id
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["A"],"strength":1})"));          // upper case
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a,b"],"strength":1})"));        // the separator
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a"]})"));                       // no strength
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a"],"strength":0})"));          // below 1
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a"],"strength":4})"));          // above 3
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a"],"strength":1.5})"));        // not whole
	// nine ids, and an id of 65 bytes
	CHECK(rejected(R"({"t":"daemon","id":1,"dev":"5","list":["a","b","c","d","e","f","g","h","i"],"strength":1})"));
	json longId = {{"t", "daemon"}, {"id", 1}, {"dev", "5"}, {"list", {std::string(65, 'a')}}, {"strength", 1}};
	CHECK(cb::rhack::parse_daemon(longId).status == Request::Status::Rejected);
	// eight ids of 64 bytes and every allowed character are fine
	json full = {{"t", "daemon"}, {"id", 2147483647}, {"dev", "18446744073709551615"}, {"list", json::array()}, {"strength", 1}};
	for (int i = 0; i < 8; ++i)
		full["list"].push_back(std::string(56, 'a') + "0_.:/-z" + std::to_string(i));
	const Request ok = cb::rhack::parse_daemon(full);
	CHECK(ok.status == Request::Status::Event && ok.event.id == 2147483647 && ok.event.amount == 1.0);
	CHECK(ok.event.text.size() == 8 * 64 + 7);
	CHECK(daemon(R"({"t":"rhack","id":1})").status == Request::Status::Ignored);
}

TEST(rhack_and_daemon_are_kept_while_not_active_and_answered)
{
	// the server waits for their `hackresult`, so they must reach the scripts whatever the state (as `hack` and `devact`)
	cb::world::EventQueue q;
	q.set_active(false);
	q.push(rhack(R"({"t":"rhack","id":4,"hack":"blackout"})").event);
	q.push(daemon(R"({"t":"daemon","id":5,"dev":"8","list":["x"],"strength":2})").event);
	CHECK(q.next() == EventKind::RHack && q.current().id == 4 && q.current().name == "blackout");
	CHECK(q.next() == EventKind::Daemon && q.current().id == 5 && q.current().text == "x");
	CHECK(q.next() == EventKind::None);
}

TEST(rhack_observer_queues_events_and_counts_bad_ones)
{
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	const auto rejectedBefore = q.stats().rejected;
	cb::rhack::on_minecraft_message(json::parse(R"({"t":"rhack","id":3,"target":"9","hack":"veh_brake"})"));
	cb::rhack::on_minecraft_message(json::parse(R"({"t":"daemon","id":6,"dev":"9","list":["y"],"strength":1})"));
	cb::rhack::on_minecraft_message(json::parse(R"({"t":"rhack","id":3,"target":"nine","hack":"veh_brake"})"));
	cb::rhack::on_minecraft_message(json::parse(R"({"t":"cam"})")); // another layer's message
	CHECK(q.stats().rejected == rejectedBefore + 1);
	CHECK(q.next() == EventKind::RHack && q.current().target == 9);
	CHECK(q.next() == EventKind::Daemon && q.current().target == 9);
	CHECK(q.next() == EventKind::None);
	q.clear();
}
