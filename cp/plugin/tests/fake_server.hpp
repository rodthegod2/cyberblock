// A fake WebSocket server on 127.0.0.1 for the tests that need a peer: it listens on an ephemeral port (never 25600) and
// speaks just enough of the server side of RFC 6455 for WsClient and Link.
#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <cstdint>
#include <string>

namespace fakeserver
{
	inline constexpr long kTimeoutSeconds = 3;

	inline bool readable(SOCKET s)
	{
		fd_set set;
		FD_ZERO(&set);
		FD_SET(s, &set);
		timeval timeout{kTimeoutSeconds, 0};
		return select(0, &set, nullptr, nullptr, &timeout) == 1;
	}

	inline bool send_bytes(SOCKET s, const std::string &bytes)
	{
		return ::send(s, bytes.data(), static_cast<int>(bytes.size()), 0) == static_cast<int>(bytes.size());
	}

	// Unmasked server frame header (+ payload) as RFC 6455 lays it out.
	inline std::string frame(unsigned char first, uint64_t size, const std::string &payload = "")
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

	inline std::string text(const std::string &payload, bool fin = true)
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
}
