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
