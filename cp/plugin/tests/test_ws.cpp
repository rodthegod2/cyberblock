// WsClient against a fake server on 127.0.0.1: frames the client must refuse (oversized, bad control frames)
// end the connection and lead to a clean reconnect instead of a crash, and no fragment survives a reconnect.
#include "test.hpp"
#include "ws.h"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <chrono>
#include <cstdint>
#include <string>
#include <thread>

namespace
{
	constexpr long kTimeoutSeconds = 3;

	bool readable(SOCKET s)
	{
		fd_set set;
		FD_ZERO(&set);
		FD_SET(s, &set);
		timeval timeout{kTimeoutSeconds, 0};
		return select(0, &set, nullptr, nullptr, &timeout) == 1;
	}

	bool send_bytes(SOCKET s, const std::string &bytes)
	{
		return ::send(s, bytes.data(), static_cast<int>(bytes.size()), 0) == static_cast<int>(bytes.size());
	}

	// Unmasked server frame header (+ payload) as RFC 6455 lays it out.
	std::string frame(unsigned char first, uint64_t size, const std::string &payload = "")
	{
		std::string out(1, static_cast<char>(first));
		if (size < 126)
			out += static_cast<char>(size);
		else if (size < 65536)
		{
			out += static_cast<char>(126);
			out += static_cast<char>(size >> 8);
			out += static_cast<char>(size);
		}
		else
		{
			out += static_cast<char>(127);
			for (int i = 7; i >= 0; --i)
				out += static_cast<char>(size >> (8 * i));
		}
		return out + payload;
	}

	std::string text(const std::string &payload, bool fin = true)
	{
		return frame(fin ? 0x81 : 0x01, payload.size(), payload);
	}

	// Listens on an ephemeral port and speaks just enough of the server side of the handshake.
	struct FakeServer
	{
		SOCKET listener = INVALID_SOCKET;
		SOCKET peer = INVALID_SOCKET;
		int port = 0;

		FakeServer()
		{
			WSADATA wsa;
			WSAStartup(MAKEWORD(2, 2), &wsa);
			listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
			sockaddr_in addr = {};
			addr.sin_family = AF_INET;
			addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
			int length = sizeof(addr);
			if (bind(listener, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == 0 && listen(listener, 4) == 0 &&
				getsockname(listener, reinterpret_cast<sockaddr *>(&addr), &length) == 0)
				port = ntohs(addr.sin_port);
		}

		~FakeServer()
		{
			drop();
			closesocket(listener);
		}

		/// Accepts the client's next connection and answers its upgrade request.
		bool accept_client()
		{
			drop();
			if (!readable(listener))
				return false;
			peer = accept(listener, nullptr, nullptr);
			std::string request;
			char c;
			while (request.find("\r\n\r\n") == std::string::npos)
			{
				if (!readable(peer) || recv(peer, &c, 1, 0) != 1)
					return false;
				request += c;
			}
			return send_bytes(peer, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n");
		}

		/// True once the client has closed this connection (anything it still sends is skipped).
		bool client_hung_up()
		{
			char buffer[256];
			while (readable(peer))
				if (recv(peer, buffer, sizeof(buffer), 0) <= 0)
					return true;
			return false;
		}

		void drop()
		{
			if (peer != INVALID_SOCKET)
				closesocket(peer);
			peer = INVALID_SOCKET;
		}
	};

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
