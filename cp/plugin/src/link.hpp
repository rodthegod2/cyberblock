// The link to the Cyberblock Minecraft mod: a WebSocket client (ws.h) to 127.0.0.1:25600 speaking wire protocol v1
// (shared/protocol.md). A background thread connects, reconnects every second, parses what Minecraft sends and does
// all the socket writes, so the game's threads only queue messages and never wait on the socket.
#pragma once
#include "gamemode.hpp"
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
	/// The link's outgoing messages, bounded so that a Minecraft that stops reading cannot grow it without limit:
	/// - a state stream (`cam`, `npcs`, `aim`, `cursor`, `pcells`, `feed`) keeps only its newest message, which replaces the
	///   queued one of its type at the back of the queue;
	/// - a `look` adds its deltas into the queued `look`;
	/// - a `clear` replaces every queued `ground`, `unground` and `clear` (Minecraft forgets that ground anyway);
	/// - everything else keeps its order, and past kMax messages the oldest one goes and is counted as dropped. A dropped
	///   `ground`, `unground` or `clear` leaves Minecraft's ground wrong, so it raises take_ground_lost().
	/// Not thread-safe: Link guards it with its lock.
	class OutQueue
	{
	public:
		static constexpr size_t kMax = 1024;

		/// Queues a message object (`t` names its type).
		void push(const nlohmann::json &message);
		/// Moves every queued message, oldest first, into `out` (which is cleared first).
		void take(std::deque<std::string> &out);
		/// Forgets every queued message (a new Minecraft session starts); the counts stay.
		void clear();
		size_t size() const { return m_entries.size(); }
		/// Messages a newer one of the same state stream replaced (or a `look` that was merged).
		uint64_t superseded() const { return m_superseded; }
		/// Messages that went because the queue was full.
		uint64_t dropped() const { return m_dropped; }
		/// True once after a `ground`, `unground` or `clear` was dropped.
		bool take_ground_lost();

	private:
		struct Entry
		{
			std::string type;
			std::string text;
			double look[2] = {0.0, 0.0}; // a `look`'s deltas, for merging
		};

		std::deque<Entry> m_entries;
		uint64_t m_superseded = 0;
		uint64_t m_dropped = 0;
		bool m_groundLost = false;
	};

	/// What the DLL logs about Minecraft's `hello` (re-test 3 plan task D8): `line` is "Minecraft says hello: mod
	/// cyberblock 1.0.0, Minecraft 26.3"; `mismatch` is "version mismatch: DLL 1.0.0, mod X" when `hello.ver` is not
	/// `dllVersion` (X is "unknown" for an older mod that sends no `ver`), else empty.
	struct HelloNote
	{
		std::string line;
		std::string mismatch;
	};
	HelloNote describe_hello(const nlohmann::json &hello, const std::string &dllVersion);

	/// Longest `hello.ver` (megaplan 2026-10-04 section 1.2).
	inline constexpr size_t kMaxVersion = 32;
	/// The DLL's `hello`, sent first on every connection: {"t":"hello","proto":1,"game":"cp2077","build":"2.31","ver"}
	/// with `ver` = `dllVersion` (CB_VERSION) cut to 32 chars, so Minecraft's handshake can compare it with its mod.
	nlohmann::json make_hello(const std::string &dllVersion);

	class Link
	{
	public:
		using Observer = std::function<void(const nlohmann::json &)>;
		using DropHandler = std::function<void()>;

		static constexpr int kDefaultPort = 25600;

		/// Starts the background thread. `observer`, if given, sees every message Minecraft sends (on that thread,
		/// after the link's own handling); `onDrop`, if given, runs on that thread each time a linked Minecraft goes away
		/// (disconnect or reconnect), so state kept from its messages can be forgotten. `port` is Minecraft's.
		void start(Observer observer = {}, DropHandler onDrop = {}, int port = kDefaultPort);
		void stop();

		/// Connected and Minecraft's `hello` with proto 1 received.
		bool linked() const { return m_linked; }
		/// Bumped on every accepted `hello`: a change means Minecraft (re)connected and has none of our state.
		int session() const { return m_session; }
		/// The last `status.screen`: a Minecraft screen (inventory, chat, ...) is open.
		bool screen_open() const { return m_screenOpen; }
		/// The last `status.gm`; None before any status of this session (and after a drop), and when the value was not one of
		/// the four modes.
		GameMode game_mode() const { return static_cast<GameMode>(m_gameMode.load()); }
		/// Seconds since the last `status`; 999 if there was none yet.
		float seconds_since_status() const;

		/// Queues a message for Minecraft (an object with `t`); dropped while not linked. The queue is bounded (OutQueue).
		void send(const nlohmann::json &message);
		/// What the outgoing queue holds now, and how many messages it replaced or dropped so far (OutQueue).
		struct SendStats
		{
			size_t queued = 0;
			uint64_t superseded = 0;
			uint64_t dropped = 0;
		};
		SendStats send_stats();
		/// True once after the queue dropped a `ground`, `unground` or `clear`: Minecraft's ground must start over.
		bool take_ground_lost();
		/// Cyberpunk's backbuffer size (0 while unknown): sent as `view` on connect and whenever it changes.
		void set_view(int width, int height);

	private:
		void run();
		void handle(const std::string &text);
		/// A linked Minecraft is gone: forget its game mode and run the drop handler.
		void dropped();

		WsClient m_ws;
		Observer m_observer;
		DropHandler m_onDrop;
		std::thread m_thread;
		std::atomic<bool> m_stop{false};
		std::atomic<bool> m_linked{false};
		std::atomic<bool> m_screenOpen{false};
		int m_port = kDefaultPort;
		std::atomic<int> m_session{0};
		std::atomic<int> m_gameMode{static_cast<int>(GameMode::None)};
		std::atomic<int64_t> m_lastStatus{0}; // steady_clock ticks, 0 = never
		std::atomic<uint32_t> m_view{0};      // width << 16 | height
		std::mutex m_lock;
		std::condition_variable m_wake;
		OutQueue m_queue;
	};

	/// The plugin's link. Never destroyed: it may still own threads when the process exits.
	inline Link &link()
	{
		static Link *instance = new Link;
		return *instance;
	}
}
