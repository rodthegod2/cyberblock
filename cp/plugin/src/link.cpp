#include "link.hpp"
#include "log.hpp"
#include <algorithm>
#include <chrono>

namespace
{
	constexpr char kHost[] = "127.0.0.1";
	constexpr int kPort = 25600;
	constexpr size_t kMaxQueued = 8;

	int64_t now_ticks()
	{
		return std::chrono::steady_clock::now().time_since_epoch().count();
	}

	std::string dump(const nlohmann::json &message)
	{
		return message.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
	}
}

namespace cb
{
	void Link::start(Observer observer)
	{
		m_observer = std::move(observer);
		m_stop = false;
		m_ws.start(kHost, kPort);
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
		const bool cam = message.value("t", "") == "cam";
		{
			std::lock_guard<std::mutex> lock(m_lock);
			m_queue.push_back({dump(message), cam});
			if (m_queue.size() > kMaxQueued)
			{
				const auto oldestCam = std::find_if(m_queue.begin(), m_queue.end(), [](const Outgoing &o) { return o.cam; });
				if (oldestCam != m_queue.end())
					m_queue.erase(oldestCam);
			}
		}
		m_wake.notify_one();
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
		std::deque<Outgoing> sending;
		while (!m_stop)
		{
			if (m_ws.generation() != generation)
			{
				// (re)connected: a new Minecraft session starts with our hello; nothing queued before it belongs there
				generation = m_ws.generation();
				m_linked = false;
				viewSent = 0;
				{
					std::lock_guard<std::mutex> lock(m_lock);
					m_queue.clear();
				}
				m_ws.send(dump({{"t", "hello"}, {"proto", 1}, {"game", "cp2077"}, {"build", "2.31"}}));
				log::info("connected to Minecraft on %s:%d", kHost, kPort);
			}
			if (!m_ws.connected() && m_linked.exchange(false))
				log::warn("Minecraft disconnected");

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
				m_wake.wait_for(lock, std::chrono::milliseconds(2), [this] { return !m_queue.empty() || m_stop; });
				sending.swap(m_queue);
			}
			for (const Outgoing &message : sending)
				m_ws.send(message.text);
			sending.clear();
		}
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
					log::info("Minecraft says hello: mod %s, Minecraft %s", message.value("mod", "?").c_str(),
							  message.value("mc", "?").c_str());
					++m_session;
					m_linked = true;
				}
				else
					log::error("Minecraft speaks protocol %d, Cyberblock needs 1: not linking", proto);
			}
			else if (type == "status")
			{
				m_screenOpen = message.value("screen", false);
				m_lastStatus = now_ticks();
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
