#include "link.hpp"
#include "log.hpp"
#include <algorithm>
#include <chrono>
#include <utility>

namespace
{
	constexpr char kHost[] = "127.0.0.1";

	int64_t now_ticks()
	{
		return std::chrono::steady_clock::now().time_since_epoch().count();
	}

	std::string dump(const nlohmann::json &message)
	{
		return message.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
	}

	/// A peer's string for the log: at most 64 bytes, printable ASCII only (anything else becomes '?').
	std::string wire_text(const std::string &value)
	{
		std::string out = value.substr(0, 64);
		for (char &c : out)
			if (c < 0x20 || c > 0x7E)
				c = '?';
		return out;
	}

	/// Messages where only the newest counts: each one carries the whole current state.
	bool is_state_stream(const std::string &type)
	{
		return type == "cam" || type == "npcs" || type == "aim" || type == "cursor" || type == "pcells";
	}

	/// Messages that build Minecraft's Night City ground; a `clear` starts it over.
	bool is_ground(const std::string &type)
	{
		return type == "ground" || type == "unground" || type == "clear";
	}
}

namespace cb
{
	HelloNote describe_hello(const nlohmann::json &hello, const std::string &dllVersion)
	{
		const auto text = [&hello](const char *key, const char *otherwise) -> std::string
		{
			const auto it = hello.find(key);
			return it != hello.end() && it->is_string() ? wire_text(it->get_ref<const std::string &>()) : otherwise;
		};
		const std::string ver = text("ver", "");
		HelloNote note;
		note.line = "Minecraft says hello: mod " + text("mod", "?") + (ver.empty() ? "" : " " + ver) + ", Minecraft " + text("mc", "?");
		if (ver != dllVersion)
			note.mismatch = "version mismatch: DLL " + dllVersion + ", mod " + (ver.empty() ? std::string("unknown") : ver);
		return note;
	}

	void OutQueue::push(const nlohmann::json &message)
	{
		Entry entry{message.value("t", ""), {}};
		const auto erase_queued = [this](auto superseded)
		{
			const size_t before = m_entries.size();
			std::erase_if(m_entries, superseded);
			m_superseded += before - m_entries.size();
		};
		if (entry.type == "look")
		{
			const auto d = message.find("d");
			if (d != message.end() && d->is_array() && d->size() == 2 && (*d)[0].is_number() && (*d)[1].is_number())
			{
				entry.look[0] = (*d)[0].get<double>();
				entry.look[1] = (*d)[1].get<double>();
				const auto queued = std::find_if(m_entries.begin(), m_entries.end(), [](const Entry &e) { return e.type == "look"; });
				if (queued != m_entries.end())
				{
					entry.look[0] += queued->look[0];
					entry.look[1] += queued->look[1];
					m_entries.erase(queued);
					++m_superseded;
					nlohmann::json merged = message;
					merged["d"] = {entry.look[0], entry.look[1]};
					entry.text = dump(merged);
				}
			}
		}
		else if (is_state_stream(entry.type))
			erase_queued([&entry](const Entry &e) { return e.type == entry.type; });
		else if (entry.type == "clear")
			erase_queued([](const Entry &e) { return is_ground(e.type); });
		if (entry.text.empty())
			entry.text = dump(message);
		m_entries.push_back(std::move(entry));
		while (m_entries.size() > kMax)
		{
			m_groundLost = m_groundLost || is_ground(m_entries.front().type);
			m_entries.pop_front();
			++m_dropped;
		}
	}

	void OutQueue::take(std::deque<std::string> &out)
	{
		out.clear();
		for (Entry &entry : m_entries)
			out.push_back(std::move(entry.text));
		m_entries.clear();
	}

	void OutQueue::clear()
	{
		m_entries.clear();
		m_groundLost = false;
	}

	bool OutQueue::take_ground_lost()
	{
		return std::exchange(m_groundLost, false);
	}

	void Link::start(Observer observer, DropHandler onDrop, int port)
	{
		m_observer = std::move(observer);
		m_onDrop = std::move(onDrop);
		m_port = port;
		m_stop = false;
		m_ws.start(kHost, port);
		m_thread = std::thread([this] { run(); });
	}

	void Link::stop()
	{
		m_stop = true;
		m_wake.notify_one();
		if (m_thread.joinable())
			m_thread.join();
		m_ws.stop();
		m_linked = false;
	}

	float Link::seconds_since_status() const
	{
		const int64_t last = m_lastStatus;
		if (last == 0)
			return 999.0f;
		const auto elapsed = std::chrono::steady_clock::duration(now_ticks() - last);
		return std::chrono::duration<float>(elapsed).count();
	}

	void Link::send(const nlohmann::json &message)
	{
		if (!m_linked)
			return;
		{
			std::lock_guard<std::mutex> lock(m_lock);
			m_queue.push(message);
		}
		m_wake.notify_one();
	}

	Link::SendStats Link::send_stats()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return {m_queue.size(), m_queue.superseded(), m_queue.dropped()};
	}

	bool Link::take_ground_lost()
	{
		std::lock_guard<std::mutex> lock(m_lock);
		return m_queue.take_ground_lost();
	}

	void Link::set_view(int width, int height)
	{
		if (width > 0 && height > 0 && width < 65536 && height < 65536)
			m_view = uint32_t(width) << 16 | uint32_t(height);
	}

	void Link::run()
	{
		int generation = 0;
		uint32_t viewSent = 0;
		std::string text;
		std::deque<std::string> sending;
		while (!m_stop)
		{
			if (m_ws.generation() != generation)
			{
				// (re)connected: a new Minecraft session starts with our hello; nothing queued before it belongs there
				generation = m_ws.generation();
				if (m_linked.exchange(false))
					dropped();
				viewSent = 0;
				{
					std::lock_guard<std::mutex> lock(m_lock);
					m_queue.clear();
				}
				m_ws.send(dump({{"t", "hello"}, {"proto", 1}, {"game", "cp2077"}, {"build", "2.31"}}));
				log::info("connected to Minecraft on %s:%d", kHost, m_port);
			}
			if (!m_ws.connected() && m_linked.exchange(false))
			{
				log::warn("Minecraft disconnected");
				dropped();
			}

			const uint32_t view = m_view;
			if (m_ws.connected() && view != 0 && view != viewSent)
			{
				m_ws.send(dump({{"t", "view"}, {"w", view >> 16}, {"h", view & 0xFFFF}}));
				viewSent = view;
			}

			while (m_ws.poll(text))
				handle(text);

			{
				std::unique_lock<std::mutex> lock(m_lock);
				m_wake.wait_for(lock, std::chrono::milliseconds(2), [this] { return m_queue.size() > 0 || m_stop; });
				m_queue.take(sending);
			}
			for (const std::string &message : sending)
				m_ws.send(message);
			sending.clear();
		}
	}

	void Link::dropped()
	{
		m_gameMode = static_cast<int>(GameMode::None);
		if (m_onDrop)
			m_onDrop();
	}

	void Link::handle(const std::string &text)
	{
		try
		{
			const nlohmann::json message = nlohmann::json::parse(text);
			const std::string type = message.value("t", "");
			if (type == "hello")
			{
				const int proto = message.value("proto", 0);
				if (proto == 1)
				{
					const HelloNote note = describe_hello(message, CB_VERSION);
					log::info("%s", note.line.c_str());
					if (!note.mismatch.empty())
						log::warn("%s", note.mismatch.c_str());
					++m_session;
					m_gameMode = static_cast<int>(GameMode::None); // the new session has not reported yet
					m_linked = true;
				}
				else
					log::error("Minecraft speaks protocol %d, Cyberblock needs 1: not linking", proto);
			}
			else if (type == "status")
			{
				m_screenOpen = message.value("screen", false);
				m_lastStatus = now_ticks();
				if (const auto gm = message.find("gm"); gm != message.end() && gm->is_string())
					if (const GameMode mode = parse_game_mode(gm->get_ref<const std::string &>()); mode != GameMode::None)
						m_gameMode = static_cast<int>(mode); // anything else is ignored: the last mode stays
			}
			// any other type: a later layer's message, ignored here

			if (m_observer)
				m_observer(message);
		}
		catch (const nlohmann::json::exception &e)
		{
			log::warn("ignoring a bad message from Minecraft (%s): %.200s", e.what(), text.c_str());
		}
	}
}
