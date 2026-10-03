// The link to the Cyberblock Minecraft mod: a WebSocket client (ws.h) to 127.0.0.1:25600 speaking wire protocol v1
// (shared/protocol.md). A background thread connects, reconnects every second, parses what Minecraft sends and does
// all the socket writes, so the game's threads only queue messages and never wait on the socket.
#pragma once
#include "ws.h"
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <nlohmann/json.hpp>
#include <string>
#include <thread>

namespace cb
{
	class Link
	{
	public:
		using Observer = std::function<void(const nlohmann::json &)>;

		/// Starts the background thread. `observer`, if given, sees every message Minecraft sends (on that thread,
		/// after the link's own handling).
		void start(Observer observer = {});
		void stop();

		/// Connected and Minecraft's `hello` with proto 1 received.
		bool linked() const { return m_linked; }
		/// Bumped on every accepted `hello`: a change means Minecraft (re)connected and has none of our state.
		int session() const { return m_session; }
		/// The last `status.screen`: a Minecraft screen (inventory, chat, ...) is open.
		bool screen_open() const { return m_screenOpen; }
		/// Seconds since the last `status`; 999 if there was none yet.
		float seconds_since_status() const;

		/// Queues a message for Minecraft; dropped while not linked. Over 8 queued messages, the oldest `cam` goes.
		void send(const nlohmann::json &message);
		/// Cyberpunk's backbuffer size (0 while unknown): sent as `view` on connect and whenever it changes.
		void set_view(int width, int height);

	private:
		struct Outgoing
		{
			std::string text;
			bool cam;
		};

		void run();
		void handle(const std::string &text);

		WsClient m_ws;
		Observer m_observer;
		std::thread m_thread;
		std::atomic<bool> m_stop{false};
		std::atomic<bool> m_linked{false};
		std::atomic<bool> m_screenOpen{false};
		std::atomic<int> m_session{0};
		std::atomic<int64_t> m_lastStatus{0}; // steady_clock ticks, 0 = never
		std::atomic<uint32_t> m_view{0};      // width << 16 | height
		std::mutex m_lock;
		std::condition_variable m_wake;
		std::deque<Outgoing> m_queue;
	};

	/// The plugin's link. Never destroyed: it may still own threads when the process exits.
	inline Link &link()
	{
		static Link *instance = new Link;
		return *instance;
	}
}
