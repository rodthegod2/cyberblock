// Manual check of the Minecraft link without the game: with the Minecraft dev client (or any server speaking protocol
// v1) on 127.0.0.1:25600, prints the received hello and three status lines, then exits 0. Exits 1 after 30 s.
// Build on demand: cmake --build cp/plugin/build --config Release --target link_smoke
#include "link.hpp"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>

int main()
{
	std::atomic<int> statuses{0};
	cb::Link &link = cb::link();
	link.set_view(1280, 720);
	link.start([&](const nlohmann::json &message) {
		const std::string type = message.value("t", "");
		if (type == "hello" || (type == "status" && statuses < 3))
		{
			std::printf("%s\n", message.dump().c_str());
			std::fflush(stdout);
			if (type == "status")
				++statuses;
		}
	});

	const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
	while (statuses < 3 && std::chrono::steady_clock::now() < deadline)
		std::this_thread::sleep_for(std::chrono::milliseconds(20));
	const bool ok = statuses >= 3 && link.linked();
	std::printf("linked=%s screen=%s seconds_since_status=%.3f -> %s\n", link.linked() ? "true" : "false",
				link.screen_open() ? "true" : "false", link.seconds_since_status(), ok ? "OK" : "FAILED");
	link.stop();
	return ok ? 0 : 1;
}
