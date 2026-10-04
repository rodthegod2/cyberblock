// Phase 1 (megaplan 2026-10-04 sections 1.2 and 1.8, task 1-H1): the DLL's `hello.ver`, the `session` message from
// Minecraft (validation, the `session` event for redscript), the probe reset on a new server session and the DLL-side
// cleanup when this host stops being the server's host.
#include "fake_server.hpp"
#include "link.hpp"
#include "probes.hpp"
#include "test.hpp"
#include "world.hpp"
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::world::EventKind;
	using cb::world::SessionState;

	void deliver(const char *text)
	{
		cb::world::on_minecraft_message(json::parse(text));
	}

	/// A fresh Minecraft: no server session known yet, nothing queued, the cleanup flag taken.
	void fresh()
	{
		deliver(R"({"t":"hello","proto":1})");
		cb::world::events().set_active(false);
		cb::world::events().clear();
		cb::world::take_session_cleanup();
	}

	std::optional<cb::world::Session> parse(const std::string &text, std::string *why = nullptr)
	{
		return cb::world::parse_session(json::parse(text), why);
	}
}

// ---- hello.ver ---------------------------------------------------------------------------------------------------------

TEST(the_dll_hello_carries_its_version)
{
	const json hello = cb::make_hello(CB_VERSION);
	CHECK(hello["t"] == "hello" && hello["proto"] == 1 && hello["game"] == "cp2077" && hello["build"] == "2.31");
	CHECK(hello["ver"] == CB_VERSION);
	CHECK(cb::make_hello(std::string(40, '9'))["ver"] == std::string(32, '9')); // at most 32 chars
}

TEST(the_link_says_hello_with_ver_as_soon_as_it_connects)
{
	fakeserver::FakeServer server;
	cb::Link link;
	link.start({}, {}, server.port);
	CHECK(server.accept_client());
	const std::string first = server.read_text();
	CHECK(!first.empty());
	if (!first.empty())
	{
		const json hello = json::parse(first);
		CHECK(hello["t"] == "hello" && hello["proto"] == 1 && hello["ver"] == CB_VERSION);
	}
	server.drop();
	closesocket(server.listener); // so a reconnect attempt fails fast and stop() returns
	server.listener = INVALID_SOCKET;
	link.stop();
}

// ---- session validation ------------------------------------------------------------------------------------------------

TEST(session_accepts_the_four_states)
{
	const std::pair<const char *, SessionState> states[] = {
		{"none", SessionState::None}, {"host", SessionState::Host}, {"guest", SessionState::Guest}, {"refused", SessionState::Refused}};
	for (const auto &[name, state] : states)
	{
		const auto s = parse(std::string(R"({"t":"session","state":")") + name + R"(","reason":"","perms":{}})");
		CHECK(s && s->state == state && s->reason.empty() && s->perms == 0);
		CHECK(std::string(cb::world::session_state_name(state)) == name);
	}
	CHECK(std::string(cb::world::session_state_name(SessionState::Unknown)).empty());
}

TEST(session_perms_become_the_hs_ack_bitmask)
{
	const auto all = parse(R"({"t":"session","state":"host","perms":{"explosions":true,"damage":true,"hacks":true,"devices":true,"spawns":true}})");
	CHECK(all && all->perms == 31);
	const auto some = parse(R"({"t":"session","state":"host","perms":{"explosions":true,"damage":false,"hacks":true,"later":7}})");
	CHECK(some && some->perms == (1 | 4)); // absent keys are false, unknown keys are ignored
	const auto spawns = parse(R"({"t":"session","state":"host","perms":{"spawns":true}})");
	CHECK(spawns && spawns->perms == 16);
	const auto bare = parse(R"({"t":"session","state":"none"})"); // no reason, no perms
	CHECK(bare && bare->reason.empty() && bare->perms == 0);
}

TEST(session_reason_is_kept_up_to_128_bytes)
{
	const std::string reason = "Cyberblock: your Cyberpunk plugin is 0.9.0 but your mod is 1.0.0; you can play as a guest.";
	const auto s = parse(json{{"t", "session"}, {"state", "refused"}, {"reason", reason}}.dump());
	CHECK(s && s->reason == reason);
	std::string exact; // 64 two-byte characters: exactly 128 bytes
	for (int i = 0; i < 64; ++i)
		exact += "\xC3\xA9";
	CHECK(parse(json{{"t", "session"}, {"state", "refused"}, {"reason", exact}}.dump()));
	std::string why;
	CHECK(!parse(json{{"t", "session"}, {"state", "refused"}, {"reason", exact + "x"}}.dump(), &why));
	CHECK(why == "reason is longer than 128 bytes");
}

TEST(bad_sessions_are_rejected_with_the_field_named)
{
	const std::pair<const char *, const char *> bad[] = {
		{R"({"t":"session"})", "state is missing or not a string"},
		{R"({"t":"session","state":"Host"})", "state is not a known name"},
		{R"({"t":"session","state":"active"})", "state is not a known name"},
		{R"({"t":"session","state":1})", "state is missing or not a string"},
		{R"({"t":"session","state":"guest","reason":5})", "reason is not a string"},
		{R"({"t":"session","state":"guest","reason":null})", "reason is not a string"},
		{R"({"t":"session","state":"guest","perms":31})", "perms is not an object"},
		{R"({"t":"session","state":"guest","perms":[true]})", "perms is not an object"},
		{R"({"t":"session","state":"guest","perms":{"hacks":1}})", "hacks is not a bool"},
		{R"(["session"])", "not an object"},
	};
	for (const auto &[text, expected] : bad)
	{
		std::string why;
		CHECK(!parse(text, &why));
		CHECK(why == expected);
	}
}

// ---- the session event -------------------------------------------------------------------------------------------------

TEST(a_session_change_queues_one_session_event_even_while_inactive)
{
	fresh();
	cb::world::EventQueue &q = cb::world::events();
	deliver(R"j({"t":"session","state":"refused","reason":"another Cyberpunk host is already active (max 1)","perms":{"explosions":true,"damage":true}})j");
	CHECK(q.stats().queued == 1);
	CHECK(q.next() == EventKind::Session);
	const cb::world::Event e = q.current();
	CHECK(e.name == "refused" && e.text == "another Cyberpunk host is already active (max 1)" && e.extra == 3);
	CHECK(std::string(cb::world::event_name(EventKind::Session)) == "session");
	CHECK(cb::world::session().state == SessionState::Refused && cb::world::session().perms == 3);

	// the client repeats its last verdict: nothing new for the scripts
	deliver(R"j({"t":"session","state":"refused","reason":"another Cyberpunk host is already active (max 1)","perms":{"explosions":true,"damage":true}})j");
	CHECK(q.stats().queued == 0);
	// another reason or other perms are a change
	deliver(R"({"t":"session","state":"refused","reason":"not on the host list","perms":{"explosions":true,"damage":true}})");
	deliver(R"({"t":"session","state":"refused","reason":"not on the host list","perms":{}})");
	CHECK(q.stats().queued == 2);
	q.set_active(true); // a state change discards transient events only
	q.set_active(false);
	CHECK(q.next() == EventKind::Session && q.current().text == "not on the host list" && q.current().extra == 3);
	CHECK(q.next() == EventKind::Session && q.current().extra == 0);
	CHECK(q.next() == EventKind::None && q.current().text.empty());
	fresh();
}

TEST(a_bad_session_is_counted_and_changes_nothing)
{
	fresh();
	deliver(R"({"t":"session","state":"guest","reason":"x"})");
	cb::world::EventQueue &q = cb::world::events();
	q.clear();
	const uint64_t before = q.stats().rejected;
	deliver(R"({"t":"session","state":"host","perms":{"damage":"yes"}})");
	CHECK(q.stats().rejected == before + 1 && q.stats().queued == 0);
	CHECK(cb::world::session().state == SessionState::Guest && cb::world::session().reason == "x");
	fresh();
}

TEST(a_hello_or_a_link_drop_forgets_the_server_session)
{
	fresh();
	deliver(R"({"t":"session","state":"host"})");
	CHECK(cb::world::session().state == SessionState::Host);
	deliver(R"({"t":"hello","proto":1})");
	CHECK(cb::world::session().state == SessionState::Unknown);
	deliver(R"({"t":"session","state":"guest"})");
	cb::world::on_link_dropped();
	CHECK(cb::world::session() == cb::world::Session{});
	fresh();
}

// ---- the probe reset ---------------------------------------------------------------------------------------------------

TEST(becoming_the_host_restarts_the_ground_once)
{
	fresh();
	int seen = cb::world::ground_epoch();
	CHECK(!cb::world::take_ground_restart(seen));
	deliver(R"({"t":"session","state":"host","perms":{"damage":true}})"); // Unknown -> host: the server has none of our ground
	CHECK(cb::world::take_ground_restart(seen));
	CHECK(!cb::world::take_ground_restart(seen));
	deliver(R"({"t":"session","state":"host","perms":{"damage":true}})"); // a repeat
	deliver(R"({"t":"session","state":"host","perms":{"damage":false}})"); // the guest toggles changed: same server session
	CHECK(!cb::world::take_ground_restart(seen));
	deliver(R"({"t":"session","state":"none","reason":"server connection lost"})");
	CHECK(!cb::world::take_ground_restart(seen));
	deliver(R"({"t":"session","state":"host"})"); // reconnected
	CHECK(cb::world::take_ground_restart(seen));
	deliver(R"({"t":"hello","proto":1})");
	deliver(R"({"t":"session","state":"host"})"); // a new Minecraft
	CHECK(cb::world::take_ground_restart(seen));
	fresh();
}

TEST(after_the_restart_the_new_session_gets_the_whole_ground_again)
{
	// natives.cpp's probe state as SubmitProbes keeps it: a ProbeSet and the epoch it was built for
	fresh();
	cb::ProbeSet probes;
	int seen = cb::world::ground_epoch();
	const cb::Vec3 feet{10.0, 20.0, 5.0};
	const std::vector<cb::Vec3> down{{10.4, 20.6, 5.0}, {11.2, 20.6, 5.0}};
	const std::vector<int> all{10, -21, 3, 4, 11, -21, 3, 4};
	CHECK(probes.submit(feet, down, {}, 24.0, 0.0, 0.0).add == all);
	CHECK(probes.submit(feet, down, {}, 24.0, 0.0, 0.1).add.empty()); // already sent
	deliver(R"({"t":"session","state":"host"})");
	if (cb::world::take_ground_restart(seen)) // restart_ground: `clear` goes out and the probe state is forgotten
		probes.clear();
	CHECK(probes.submit(feet, down, {}, 24.0, 0.0, 0.2).add == all);
	fresh();
}

// ---- the DLL-side cleanup ----------------------------------------------------------------------------------------------

TEST(leaving_the_host_role_asks_for_the_link_drop_cleanup_once)
{
	fresh();
	deliver(R"({"t":"session","state":"host"})");
	CHECK(!cb::world::take_session_cleanup());
	deliver(R"j({"t":"session","state":"guest","reason":"Cyberblock: another Cyberpunk host is already active (max 1)"})j");
	CHECK(cb::world::take_session_cleanup());
	CHECK(!cb::world::take_session_cleanup());
	deliver(R"({"t":"session","state":"refused","reason":"r"})"); // already cleaned: no second DLL cleanup
	CHECK(!cb::world::take_session_cleanup());
	deliver(R"({"t":"session","state":"host"})");
	deliver(R"({"t":"session","state":"none","reason":"server connection lost"})");
	CHECK(cb::world::take_session_cleanup());
	fresh();
	deliver(R"({"t":"session","state":"refused","reason":"version mismatch"})"); // the first verdict of a Minecraft
	CHECK(cb::world::take_session_cleanup());
	cb::world::on_link_dropped();
	deliver(R"({"t":"session","state":"guest"})");
	CHECK(cb::world::take_session_cleanup());
	fresh();
}
