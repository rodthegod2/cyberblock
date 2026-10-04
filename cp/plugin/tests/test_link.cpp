// Link against a fake Minecraft on an ephemeral port (never 25600): what `status.gm` does to Cyberblock_GameMode's state, and
// that the drop handler runs once per lost Minecraft and forgets the game mode (plan 2026-10-03 task D6).
#include "fake_server.hpp"
#include "gamemode.hpp"
#include "link.hpp"
#include "test.hpp"
#include <atomic>
#include <chrono>
#include <string>
#include <thread>
#include <utility>

namespace
{
	using namespace fakeserver;
	using cb::GameMode;

	const std::string kHello = R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3"})";

	std::string status(const std::string &fields)
	{
		return "{\"t\":\"status\",\"hp\":20,\"maxHp\":20,\"food\":20" + fields + "}";
	}

	template<typename Predicate>
	bool wait_for(Predicate done)
	{
		const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(kTimeoutSeconds);
		while (std::chrono::steady_clock::now() < deadline)
		{
			if (done())
				return true;
			std::this_thread::sleep_for(std::chrono::milliseconds(5));
		}
		return done();
	}

	/// A Link talking to a FakeServer, with Minecraft's hello already delivered.
	struct Session
	{
		FakeServer server;
		cb::Link link;
		std::atomic<int> drops{0};
		std::atomic<int> hellos{0};

		bool open()
		{
			link.start(
				[this](const nlohmann::json &message)
				{
					if (message.value("t", "") == "hello")
						++hellos;
				},
				[this] { ++drops; }, server.port);
			return server.accept_client() && send_bytes(server.peer, text(kHello)) && wait_for([this] { return link.linked(); });
		}

		bool send_status(const std::string &fields)
		{
			return send_bytes(server.peer, text(status(fields)));
		}

		bool screen = false;

		/// Sends a status that flips `screen` and waits until the link has handled it: everything sent before it is processed.
		bool sync()
		{
			screen = !screen;
			return send_status(std::string(R"(,"screen":)") + (screen ? "true" : "false")) &&
				   wait_for([this] { return link.screen_open() == screen; });
		}

		~Session()
		{
			server.drop();
			closesocket(server.listener); // so a reconnect attempt fails fast and stop() returns
			server.listener = INVALID_SOCKET;
			link.stop();
		}
	};
}

TEST(game_mode_is_none_until_a_status_names_one)
{
	Session s;
	CHECK(s.link.game_mode() == GameMode::None);
	CHECK(s.open());
	CHECK(s.link.game_mode() == GameMode::None); // linked, but no status yet
	CHECK(s.hellos == 1);
}

TEST(game_mode_follows_the_four_modes_of_status_gm)
{
	Session s;
	CHECK(s.open());
	const std::pair<const char *, GameMode> modes[] = {{"creative", GameMode::Creative},
													   {"survival", GameMode::Survival},
													   {"adventure", GameMode::Adventure},
													   {"spectator", GameMode::Spectator}};
	for (const auto &[name, mode] : modes)
	{
		const std::string fields = std::string(R"(,"gm":")") + name + '"';
		CHECK(s.send_status(fields));
		CHECK(wait_for([&] { return s.link.game_mode() == mode; }));
	}
}

TEST(a_status_gm_that_is_not_a_mode_is_ignored)
{
	Session s;
	CHECK(s.open());
	CHECK(s.send_status(R"(,"gm":"creative")"));
	CHECK(wait_for([&] { return s.link.game_mode() == GameMode::Creative; }));
	for (const char *fields : {R"(,"gm":"hardcore")", R"(,"gm":"")", R"(,"gm":"Survival")", R"(,"gm":5)", R"(,"gm":null)", R"(,"gm":["survival"])", ""})
	{
		CHECK(s.send_status(fields));
		CHECK(s.sync());
		CHECK(s.link.game_mode() == GameMode::Creative); // the last real mode stays
	}
}

TEST(a_new_hello_starts_a_session_without_a_game_mode)
{
	Session s;
	CHECK(s.open());
	CHECK(s.send_status(R"(,"gm":"adventure")"));
	CHECK(wait_for([&] { return s.link.game_mode() == GameMode::Adventure; }));
	CHECK(send_bytes(s.server.peer, text(kHello)));
	CHECK(wait_for([&] { return s.hellos == 2; }));
	CHECK(s.link.game_mode() == GameMode::None);
	CHECK(s.send_status(R"(,"gm":"survival")"));
	CHECK(wait_for([&] { return s.link.game_mode() == GameMode::Survival; }));
}

TEST(the_drop_handler_runs_once_per_lost_minecraft_and_the_game_mode_is_forgotten)
{
	Session s;
	CHECK(s.open());
	CHECK(s.send_status(R"(,"gm":"creative")"));
	CHECK(wait_for([&] { return s.link.game_mode() == GameMode::Creative; }));
	CHECK(s.drops == 0);

	s.server.drop();
	CHECK(wait_for([&] { return s.drops == 1; }));
	CHECK(!s.link.linked() && s.link.game_mode() == GameMode::None);

	// the client reconnects by itself; the new session is a fresh one, and no second drop is counted for the old one
	CHECK(s.server.accept_client());
	CHECK(send_bytes(s.server.peer, text(kHello)));
	CHECK(wait_for([&] { return s.link.linked(); }));
	CHECK(s.drops == 1 && s.hellos == 2);

	s.server.drop();
	CHECK(wait_for([&] { return s.drops == 2; }));
}

TEST(a_connection_that_never_said_hello_is_no_drop)
{
	Session s;
	s.link.start({}, [&s] { ++s.drops; }, s.server.port);
	CHECK(s.server.accept_client()); // connected, but Minecraft never says hello: not linked
	s.server.drop();
	CHECK(s.server.accept_client()); // the client reconnects
	std::this_thread::sleep_for(std::chrono::milliseconds(100));
	CHECK(!s.link.linked() && s.drops == 0);
}

TEST(game_mode_names_round_trip_and_anything_else_is_none)
{
	for (const char *name : {"survival", "creative", "adventure", "spectator"})
		CHECK(std::string(cb::game_mode_name(cb::parse_game_mode(name))) == name);
	for (const char *name : {"", "Creative", "hardcore", "survival ", "gm1"})
		CHECK(cb::parse_game_mode(name) == GameMode::None);
	CHECK(std::string(cb::game_mode_name(GameMode::None)).empty()); // the empty CName: n"" before any status
}

// ---- the outgoing queue (cb::OutQueue): bounded however long Minecraft stops reading ----

namespace
{
	std::deque<nlohmann::json> drain(cb::OutQueue &queue)
	{
		std::deque<std::string> texts;
		queue.take(texts);
		std::deque<nlohmann::json> out;
		for (const std::string &text : texts)
			out.push_back(nlohmann::json::parse(text));
		return out;
	}

	nlohmann::json key(int n)
	{
		return {{"t", "key"}, {"k", "attack"}, {"down", n % 2 == 0}, {"n", n}};
	}
}

TEST(out_queue_keeps_only_the_newest_message_of_each_state_stream)
{
	cb::OutQueue q;
	q.push({{"t", "cam"}, {"f", 1}});
	q.push(key(0));
	q.push({{"t", "npcs"}, {"list", {1}}});
	q.push({{"t", "cam"}, {"f", 2}});
	q.push({{"t", "npcs"}, {"list", {2}}});
	q.push({{"t", "aim"}, {"d", 1.0}});
	q.push({{"t", "aim"}, {"d", 2.0}});
	q.push({{"t", "cursor"}, {"x", 0.1}, {"y", 0.1}});
	q.push({{"t", "cursor"}, {"x", 0.2}, {"y", 0.3}});
	q.push({{"t", "pcells"}, {"c", {1, 2, 3}}});
	q.push({{"t", "pcells"}, {"c", {4, 5, 6}}});
	CHECK(q.superseded() == 5 && q.dropped() == 0);
	const auto out = drain(q);
	CHECK(out.size() == 6);
	CHECK(out[0]["t"] == "key");
	CHECK(out[1]["t"] == "cam" && out[1]["f"] == 2);
	CHECK(out[2]["t"] == "npcs" && out[2]["list"][0] == 2);
	CHECK(out[3]["t"] == "aim" && out[3]["d"] == 2.0);
	CHECK(out[4]["t"] == "cursor" && out[4]["y"] == 0.3);
	CHECK(out[5]["t"] == "pcells" && out[5]["c"][0] == 4);
	CHECK(q.size() == 0);
}

TEST(out_queue_adds_look_deltas_up_instead_of_losing_them)
{
	cb::OutQueue q;
	q.push({{"t", "look"}, {"d", {1.0, 2.0}}});
	q.push(key(0));
	q.push({{"t", "look"}, {"d", {0.5, -1.0}}});
	q.push({{"t", "look"}, {"d", {-0.25, 0.5}}});
	const auto out = drain(q);
	CHECK(out.size() == 2);
	CHECK(out[0]["t"] == "key");
	CHECK(out[1]["t"] == "look" && out[1]["d"][0] == 1.25 && out[1]["d"][1] == 1.5);
}

TEST(out_queue_clear_replaces_the_queued_ground)
{
	cb::OutQueue q;
	q.push({{"t", "ground"}, {"c", {1, 2, 3, 4}}});
	q.push({{"t", "unground"}, {"c", {1, 2, 3, 4}}});
	q.push(key(0));
	q.push({{"t", "clear"}});
	q.push({{"t", "ground"}, {"c", {5, 6, 7, 8}}});
	q.push({{"t", "clear"}});
	const auto out = drain(q);
	CHECK(out.size() == 2);
	CHECK(out[0]["t"] == "key" && out[1]["t"] == "clear");
	CHECK(q.superseded() == 4 && q.dropped() == 0 && !q.take_ground_lost());
}

TEST(out_queue_past_its_limit_drops_the_oldest_and_counts_it)
{
	cb::OutQueue q;
	for (int i = 0; i < int(cb::OutQueue::kMax) + 5; ++i)
		q.push(key(i));
	CHECK(q.size() == cb::OutQueue::kMax && q.dropped() == 5);
	CHECK(!q.take_ground_lost()); // only keys went
	const auto out = drain(q);
	CHECK(out.front()["n"] == 5 && out.back()["n"] == int(cb::OutQueue::kMax) + 4);
}

TEST(out_queue_dropping_ground_asks_for_a_ground_restart_once)
{
	cb::OutQueue q;
	q.push({{"t", "ground"}, {"c", {1, 2, 3, 4}}});
	for (int i = 0; i < int(cb::OutQueue::kMax); ++i)
		q.push(key(i));
	CHECK(q.dropped() == 1);
	CHECK(q.take_ground_lost());
	CHECK(!q.take_ground_lost());
}

TEST(out_queue_stays_bounded_under_every_kind_of_message)
{
	cb::OutQueue q;
	for (int i = 0; i < 20000; ++i)
	{
		q.push({{"t", "cam"}, {"f", i}});
		q.push({{"t", "npcs"}, {"list", nlohmann::json::array()}});
		q.push({{"t", "look"}, {"d", {0.1, 0.1}}});
		q.push({{"t", "ground"}, {"c", {i, 0, 1, 2}}});
		q.push(key(i));
	}
	CHECK(q.size() <= cb::OutQueue::kMax);
	CHECK(q.dropped() > 0);
	q.clear();
	CHECK(q.size() == 0 && !q.take_ground_lost());
}

// ---- re-test 3 (plan 2026-10-03-retest3-polish task D8): version 2.0.0 (was 1.0.0) and hello.ver -----------------------------------

TEST(the_dll_version_is_2_0_0_from_cmake)
{
	CHECK(std::string(CB_VERSION) == "2.0.0");
	CHECK(CB_VERSION_MAJOR == 2 && CB_VERSION_MINOR == 0 && CB_VERSION_PATCH == 0);
}

TEST(hello_ver_is_logged_and_a_mismatch_is_named)
{
	const auto same = cb::describe_hello(nlohmann::json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3","ver":"1.0.0"})"), "1.0.0");
	CHECK(same.line == "Minecraft says hello: mod cyberblock 1.0.0, Minecraft 26.3" && same.mismatch.empty());
	const auto other = cb::describe_hello(nlohmann::json::parse(R"({"t":"hello","proto":1,"mod":"cyberblock","mc":"26.3","ver":"0.9.2"})"), "1.0.0");
	CHECK(other.line == "Minecraft says hello: mod cyberblock 0.9.2, Minecraft 26.3");
	CHECK(other.mismatch == "version mismatch: DLL 1.0.0, mod 0.9.2");
	const auto older = cb::describe_hello(nlohmann::json::parse(kHello), "1.0.0"); // no ver
	CHECK(older.line == "Minecraft says hello: mod cyberblock, Minecraft 26.3");
	CHECK(older.mismatch == "version mismatch: DLL 1.0.0, mod unknown");
	const auto odd = cb::describe_hello(nlohmann::json::parse(R"({"t":"hello","ver":"1.0.0\n\u00e9","mod":7})"), "1.0.0");
	CHECK(odd.line == "Minecraft says hello: mod ? 1.0.0???, Minecraft ?"); // control and non-ASCII bytes are masked
	CHECK(!odd.mismatch.empty());
}
