// WsClient against a fake server on 127.0.0.1: frames the client must refuse (oversized, bad control frames)
// end the connection and lead to a clean reconnect instead of a crash, and no fragment survives a reconnect.
#include "fake_server.hpp"
#include "test.hpp"
#include "ws.h"
#include <chrono>
#include <cstdint>
#include <string>
#include <thread>

namespace
{
	using namespace fakeserver;

	bool next_message(WsClient &client, std::string &message)
	{
		const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(kTimeoutSeconds);
		while (std::chrono::steady_clock::now() < deadline)
		{
			if (client.poll(message))
				return true;
			std::this_thread::sleep_for(std::chrono::milliseconds(5));
		}
		return false;
	}

	// Sends `bad` on the first connection, expects the client to hang up and reconnect, then checks that the
	// second session delivers its first message intact (nothing left over from the first one).
	void expect_reconnect_after(const std::string &bad)
	{
		FakeServer server;
		CHECK(server.port != 0);
		WsClient client;
		client.start("127.0.0.1", server.port);

		CHECK(server.accept_client());
		CHECK(send_bytes(server.peer, bad));
		CHECK(server.client_hung_up());

		CHECK(server.accept_client());
		CHECK(send_bytes(server.peer, text("fresh")));
		std::string message;
		CHECK(next_message(client, message));
		CHECK(message == "fresh");
		CHECK(client.generation() == 2);

		server.drop();
		closesocket(server.listener); // so a reconnect attempt fails fast and stop() returns
		server.listener = INVALID_SOCKET;
		client.stop();
	}
}

TEST(ws_delivers_text_and_fragmented_messages)
{
	FakeServer server;
	WsClient client;
	client.start("127.0.0.1", server.port);
	CHECK(server.accept_client());
	CHECK(send_bytes(server.peer, text("one") + text("tw", false) + frame(0x80, 1, "o")));
	std::string message;
	CHECK(next_message(client, message) && message == "one");
	CHECK(next_message(client, message) && message == "two");
	server.drop();
	closesocket(server.listener);
	server.listener = INVALID_SOCKET;
	client.stop();
}

TEST(ws_oversized_frame_reconnects)
{
	expect_reconnect_after(frame(0x81, uint64_t(1) << 62)); // header only: the peer claims 4 EiB
}

TEST(ws_oversized_fragmented_message_reconnects)
{
	// 2 bytes so far plus a continuation claiming exactly the limit: over it in total
	expect_reconnect_after(text("ab", false) + frame(0x80, uint64_t(16) << 20));
}

TEST(ws_bad_control_frames_reconnect)
{
	expect_reconnect_after(frame(0x89, 126, std::string(126, 'p'))); // ping over 125 bytes
	expect_reconnect_after(frame(0x09, 0));                          // fragmented ping
}

TEST(ws_fragment_does_not_survive_reconnect)
{
	expect_reconnect_after(text("stale", false) + frame(0x88, 0)); // close mid-message
}
