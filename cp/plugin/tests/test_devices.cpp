// Phase 4 (megaplan 2026-10-04 task 4-D1): the device messages, their limits and the device-box probe filter.
#include "devices.hpp"
#include "probes.hpp"
#include "test.hpp"
#include "world.hpp"
#include <cmath>
#include <iterator>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace
{
	using nlohmann::json;
	using cb::Vec3;
	using cb::devices::Device;
	using cb::devices::DevAct;

	constexpr double kOff = 57.0 / 64.0;

	bool near(double a, double b, double eps = 1e-9)
	{
		return std::abs(a - b) <= eps;
	}

	/// Parallel arrays of `n` valid devices, 1 m apart along +X from the origin.
	struct Arrays
	{
		std::vector<uint64_t> ids;
		std::vector<std::string> classes, kinds;
		std::vector<Vec3> pos;
		std::vector<double> yaws;
		std::vector<Vec3> sizes;
		std::vector<int32_t> states, flags;

		explicit Arrays(size_t n)
		{
			for (size_t i = 0; i < n; ++i)
			{
				ids.push_back(1000 + i);
				classes.push_back("Door");
				kinds.push_back("door");
				pos.push_back({double(i), 0.0, 0.0});
				yaws.push_back(0.0);
				sizes.push_back({0.0, 0.0, 0.0});
				states.push_back(0);
				flags.push_back(cb::devices::kFlagPowered);
			}
		}
		std::optional<cb::devices::Validated> validate() const
		{
			return cb::devices::validate(ids, classes, kinds, pos, yaws, sizes, states, flags);
		}
	};

	Device device(uint64_t id, Vec3 pos, std::string kind = "door", int32_t st = 0)
	{
		return Device{id, "Door", std::move(kind), pos, 0.0, st, 0};
	}

	DevAct devact(const char *text)
	{
		return cb::devices::parse_devact(json::parse(text));
	}
}

TEST(devices_validate_keeps_good_entries_in_order)
{
	const Arrays a(3);
	const auto v = a.validate();
	CHECK(v && v->devices.size() == 3 && v->dropped == 0);
	CHECK(v->devices.at(2).id == 1002 && v->devices.at(2).cls == "Door" && v->devices.at(2).kind == "door");
	CHECK(v->devices.at(2).fl == 16 && v->devices.at(2).st == 0 && near(v->devices.at(2).pos.x, 2.0));
}

TEST(devices_validate_needs_parallel_arrays_and_ignores_sizes)
{
	Arrays a(2);
	a.sizes.clear(); // sizes are always zero (amendment item 5): an empty array is fine too
	CHECK(a.validate().has_value());
	for (int field = 0; field < 7; ++field)
	{
		Arrays b(2);
		switch (field)
		{
		case 0: b.classes.pop_back(); break;
		case 1: b.kinds.pop_back(); break;
		case 2: b.pos.pop_back(); break;
		case 3: b.yaws.pop_back(); break;
		case 4: b.sizes.pop_back(); break;
		case 5: b.states.pop_back(); break;
		case 6: b.flags.pop_back(); break;
		}
		CHECK(!b.validate().has_value());
	}
}

TEST(devices_validate_drops_and_counts_bad_entries)
{
	const double nan = std::numeric_limits<double>::quiet_NaN();
	for (int bad = 0; bad < 13; ++bad)
	{
		Arrays a(2);
		switch (bad)
		{
		case 0: a.ids[1] = 0; break;
		case 1: a.ids[1] = a.ids[0]; break;                // a repeated id
		case 2: a.classes[1] = ""; break;
		case 3: a.classes[1] = "Do or"; break;             // not printable ASCII without spaces
		case 4: a.kinds[1] = "toaster"; break;
		case 5: a.kinds[1] = ""; break;                     // natives.cpp passes "" for an unknown kind CName
		case 6: a.pos[1].y = nan; break;
		case 7: a.pos[1].z = 3e7; break;
		case 8: a.yaws[1] = std::numeric_limits<double>::infinity(); break;
		case 9: a.states[1] = -3; break;
		case 10: a.states[1] = 2; break;
		case 11: a.flags[1] = -1; break;
		case 12: a.flags[1] = 32; break;
		}
		const auto v = a.validate();
		CHECK(v && v->devices.size() == 1 && v->devices.at(0).id == 1000 && v->dropped == 1 && !v->reason.empty());
	}
}

TEST(devices_validate_accepts_every_state_flag_and_kind)
{
	Arrays a(1);
	for (int32_t st = -2; st <= 1; ++st)
		for (int32_t fl : {0, 1, 2, 4, 8, 16, 31})
		{
			a.states[0] = st;
			a.flags[0] = fl;
			CHECK(a.validate()->devices.size() == 1);
		}
	for (const char *kind : cb::devices::kKinds)
	{
		a.kinds[0] = kind;
		CHECK(a.validate()->devices.size() == 1);
	}
}

TEST(devices_validate_cuts_a_long_class_to_48_chars)
{
	Arrays a(1);
	a.classes[0] = std::string(60, 'A');
	const auto v = a.validate();
	CHECK(v->devices.at(0).cls == std::string(48, 'A'));
	a.classes[0] = "gameDoorControllerPS";
	CHECK(a.validate()->devices.at(0).cls == "gameDoorControllerPS");
}

TEST(devices_nearest_keeps_64_within_48_m_nearest_first)
{
	std::vector<Device> list;
	for (int i = 0; i < 100; ++i)
		list.push_back(device(uint64_t(i + 1), {double(100 - i), 0.0, 0.0})); // ids 1..100 at x 100..1
	const auto out = cb::devices::nearest(list, Vec3{0.0, 0.0, 0.0});
	CHECK(out.size() == 48); // x 1..48 are within 48 m (48 is on the edge)
	CHECK(out.front().id == 100 && out.back().id == 53);
	const auto all = cb::devices::nearest(list, Vec3{50.0, 0.0, 0.0});
	CHECK(all.size() == 64 && near(cb::devices::kRadius, 48.0));
	for (size_t i = 1; i < all.size(); ++i)
		CHECK(std::abs(all[i - 1].pos.x - 50.0) <= std::abs(all[i].pos.x - 50.0));
	// distance is 3D: 40 m up and 30 m across is 50 m away
	CHECK(cb::devices::nearest({device(7, {30.0, 0.0, 40.0})}, Vec3{0.0, 0.0, 0.0}).empty());
}

TEST(devices_nearest_without_a_centre_keeps_the_first_64)
{
	std::vector<Device> list;
	for (int i = 0; i < 70; ++i)
		list.push_back(device(uint64_t(i + 1), {1000.0 * i, 0.0, 0.0}));
	const auto out = cb::devices::nearest(list, std::nullopt);
	CHECK(out.size() == 64 && out.front().id == 1 && out.back().id == 64);
}

TEST(devices_entry_is_in_minecraft_space_without_a_size)
{
	Device d{18446744073709551615ull, "Door", "door", {10.25, -20.5, 3.0}, 90.0, -1, 5};
	const json e = cb::devices::entry_json(d, kOff);
	CHECK(e.size() == 7 && !e.contains("size"));
	CHECK(e["id"] == "18446744073709551615" && e["cls"] == "Door" && e["kind"] == "door");
	const Vec3 mc = cb::cp_to_mc_pos(10.25, -20.5, 3.0, kOff); // (x, z - off, -y)
	CHECK(near(e["p"][0].get<double>(), mc.x) && near(e["p"][1].get<double>(), std::round(mc.y * 1e3) / 1e3) &&
		  near(e["p"][2].get<double>(), 20.5));
	CHECK(near(e["yaw"].get<double>(), 90.0)); // Cyberpunk 90 -> Minecraft 180 - 90
	CHECK(e["st"] == -1 && e["fl"] == 5);
	d.yaw = 0.0;
	CHECK(near(cb::devices::entry_json(d, 0.0)["yaw"].get<double>(), 180.0));
}

TEST(devices_stream_sends_the_full_list_then_deltas_then_the_full_list_every_5_s)
{
	cb::devices::Stream stream;
	std::vector<Device> list{device(1, {0, 0, 0}), device(2, {1, 0, 0}), device(3, {2, 0, 0})};
	auto m = stream.submit(list, 0.0, 100.0);
	CHECK(m && (*m)["t"] == "devices" && (*m)["list"].size() == 3);
	CHECK(!stream.submit(list, 0.0, 100.5)); // nothing changed
	list[1].st = 1;                           // device 2 opened
	m = stream.submit(list, 0.0, 101.0);
	CHECK(m && (*m)["list"].size() == 1 && (*m)["list"][0]["id"] == "2" && (*m)["list"][0]["st"] == 1);
	list.push_back(device(4, {3, 0, 0})); // a new one comes into range
	m = stream.submit(list, 0.0, 102.0);
	CHECK(m && (*m)["list"].size() == 1 && (*m)["list"][0]["id"] == "4");
	list.erase(list.begin()); // one leaves: nothing to say (Minecraft times it out)
	CHECK(!stream.submit(list, 0.0, 103.0));
	m = stream.submit(list, 0.0, 105.0); // 5 s after the first full list
	CHECK(m && (*m)["list"].size() == 3);
	CHECK(!stream.submit(list, 0.0, 109.9));
	CHECK(stream.submit(list, 0.0, 110.0)->at("list").size() == 3);
	// a device that left and comes back is new again
	list.push_back(device(1, {0, 0, 0}));
	CHECK(stream.submit(list, 0.0, 111.0)->at("list").size() == 1);
}

TEST(devices_stream_reset_and_a_new_alignment)
{
	cb::devices::Stream stream;
	const std::vector<Device> list{device(1, {0, 0, 0}), device(2, {1, 0, 0})};
	CHECK(stream.submit(list, 0.0, 10.0));
	stream.reset();
	CHECK(stream.submit(list, 0.0, 11.0)->at("list").size() == 2);
	// a position converted with another offset differs: every entry is a delta
	CHECK(stream.submit(list, kOff, 12.0)->at("list").size() == 2);
	CHECK(stream.size() == 2);
}

TEST(devices_stream_skips_an_empty_full_list_and_what_devstate_said)
{
	cb::devices::Stream stream;
	CHECK(!stream.submit({}, 0.0, 1.0));
	std::vector<Device> list{device(1, {0, 0, 0})};
	CHECK(stream.submit(list, 0.0, 2.0)); // a new device goes out as a delta
	stream.note_state(1, 1, 16);          // devstate told Minecraft
	list[0].st = 1;
	list[0].fl = 16;
	CHECK(!stream.submit(list, 0.0, 3.0));
	stream.note_state(99, 1, 0); // unknown id: nothing happens
	CHECK(stream.size() == 1);
}

TEST(devices_stream_never_sends_more_than_it_is_given)
{
	std::vector<Device> list;
	for (int i = 0; i < 64; ++i)
		list.push_back(device(uint64_t(i + 1), {double(i) / 2, 0, 0}));
	cb::devices::Stream stream;
	CHECK(stream.submit(list, 0.0, 0.0)->at("list").size() == cb::devices::kMaxEntries);
}

TEST(devstate_builder_and_limits)
{
	const auto m = cb::devices::make_devstate(42, 1, 17, "npc");
	CHECK(m && *m == json::parse(R"({"t":"devstate","id":"42","st":1,"fl":17,"by":"npc"})"));
	for (const char *by : cb::devices::kBy)
		CHECK(cb::devices::make_devstate(42, -2, 0, by).has_value());
	CHECK(!cb::devices::make_devstate(0, 0, 0, "host"));
	CHECK(!cb::devices::make_devstate(42, -3, 0, "host"));
	CHECK(!cb::devices::make_devstate(42, 2, 0, "host"));
	CHECK(!cb::devices::make_devstate(42, 0, -1, "host"));
	CHECK(!cb::devices::make_devstate(42, 0, 32, "host"));
	CHECK(!cb::devices::make_devstate(42, 0, 0, "player"));
	CHECK(!cb::devices::make_devstate(42, 0, 0, ""));
}

TEST(devresult_builder_and_limits)
{
	CHECK(*cb::devices::make_devresult(7, true, "ignored") ==
		  json::parse(R"({"t":"devresult","id":7,"ok":true,"reason":""})"));
	CHECK(*cb::devices::make_devresult(7, false, "quest locked") ==
		  json::parse(R"({"t":"devresult","id":7,"ok":false,"reason":"quest locked"})"));
	const std::string reason = std::string(63, 'x') + "\xC3\xA9"; // 65 bytes, the last character two bytes wide
	CHECK(cb::devices::make_devresult(7, false, reason)->at("reason") == std::string(63, 'x'));
	CHECK(!cb::devices::make_devresult(-1, false, "no"));
	CHECK(cb::devices::make_devresult(2147483647, false, "")->at("id") == 2147483647);
}

TEST(devices_guest_window_lasts_2_s_for_that_device_only)
{
	cb::devices::GuestWindow w;
	w.note(5, 77, 10.0);
	CHECK(w.recent(77, 10.0) && w.recent(77, 12.0));
	CHECK(!w.recent(77, 12.01));
	CHECK(!w.recent(78, 10.5));
	CHECK(!w.recent(77, 9.0)); // a clock that went back says nothing
	w.forget(5);               // the request failed
	CHECK(!w.recent(77, 10.5));
	CHECK(cb::devices::attribute("npc", true) == "guest" && cb::devices::attribute("npc", false) == "npc");
	CHECK(cb::devices::attribute("script", true) == "guest" && cb::devices::attribute("host", false) == "host");
}

TEST(devices_guest_window_is_bounded)
{
	cb::devices::GuestWindow w;
	for (int i = 0; i < 65; ++i)
		w.note(i, uint64_t(1000 + i), 0.0);
	CHECK(!w.recent(1000, 0.0)); // the oldest went
	CHECK(w.recent(1001, 0.0) && w.recent(1064, 0.0));
	w.clear();
	CHECK(!w.recent(1064, 0.0));
}

TEST(devact_with_a_supported_act_becomes_an_event)
{
	CHECK(std::size(cb::devices::kActs) == 11);
	for (const char *act : cb::devices::kActs)
	{
		json m = json::parse(R"({"t":"devact","id":12,"dev":"18446744073709551615"})");
		m["act"] = act;
		const DevAct d = cb::devices::parse_devact(m);
		CHECK(d.status == DevAct::Status::Event && !d.reply);
		CHECK(d.event.kind == cb::world::EventKind::DevAct && d.event.id == 12);
		CHECK(d.event.target == 18446744073709551615ull && d.event.name == act);
	}
	CHECK(std::string(cb::world::event_name(cb::world::EventKind::DevAct)) == "devact");
}

TEST(devact_with_another_act_is_answered_unsupported)
{
	for (const char *act : {"call", "buy", "breach", "dance", ""})
	{
		json m = json::parse(R"({"t":"devact","id":3,"dev":"9"})");
		m["act"] = act;
		const DevAct d = cb::devices::parse_devact(m);
		CHECK(d.status == DevAct::Status::Unsupported && d.event.kind == cb::world::EventKind::None);
		CHECK(d.reply && *d.reply == json::parse(R"({"t":"devresult","id":3,"ok":false,"reason":"unsupported"})"));
	}
}

TEST(a_bad_devact_is_rejected_and_others_ignored)
{
	const auto rejected = [](const char *text) {
		const DevAct d = devact(text);
		return d.status == DevAct::Status::Rejected && !d.reply && !d.reason.empty();
	};
	CHECK(rejected(R"({"t":"devact","dev":"9","act":"open"})"));                // no id
	CHECK(rejected(R"({"t":"devact","id":-1,"dev":"9","act":"open"})"));        // negative
	CHECK(rejected(R"({"t":"devact","id":1.5,"dev":"9","act":"open"})"));       // not whole
	CHECK(rejected(R"({"t":"devact","id":2147483648,"dev":"9","act":"open"})")); // past Int32
	CHECK(rejected(R"({"t":"devact","id":1,"act":"open"})"));                   // no dev
	CHECK(rejected(R"({"t":"devact","id":1,"dev":9,"act":"open"})"));           // dev not a string
	CHECK(rejected(R"({"t":"devact","id":1,"dev":"0","act":"open"})"));         // dev 0
	CHECK(rejected(R"({"t":"devact","id":1,"dev":"9x","act":"open"})"));        // not decimal
	CHECK(rejected(R"({"t":"devact","id":1,"dev":"18446744073709551616","act":"open"})")); // past uint64
	CHECK(rejected(R"({"t":"devact","id":1,"dev":"9"})"));                      // no act
	CHECK(rejected(R"({"t":"devact","id":1,"dev":"9","act":3})"));              // act not a string
	CHECK(devact(R"({"t":"devresult","id":1})").status == DevAct::Status::Ignored);
	CHECK(devact(R"([1,2])").status == DevAct::Status::Ignored);
	CHECK(devact(R"({"id":1})").status == DevAct::Status::Ignored);
}

TEST(devact_is_kept_while_not_active)
{
	cb::world::EventQueue q; // not Active: the server waits for `devresult` all the same
	cb::world::Event e;
	e.kind = cb::world::EventKind::DevAct;
	e.id = 4;
	e.target = 99;
	e.name = "open";
	q.push(e);
	q.set_active(true);
	q.set_active(false);
	CHECK(q.next() == cb::world::EventKind::DevAct && q.current().id == 4 && q.current().target == 99 &&
		  q.current().name == "open");
}

TEST(devices_on_minecraft_message_queues_devact_and_notes_the_guest)
{
	cb::world::events().clear();
	cb::devices::guest_window().clear();
	cb::devices::on_minecraft_message(json::parse(R"({"t":"devact","id":8,"dev":"31","act":"toggle"})"));
	CHECK(cb::devices::guest_window().recent(31, cb::steady_seconds()));
	CHECK(cb::world::events().next() == cb::world::EventKind::DevAct && cb::world::events().current().id == 8);
	// unsupported: answered (the link drops it while not linked), nothing queued, no window
	cb::devices::on_minecraft_message(json::parse(R"({"t":"devact","id":9,"dev":"32","act":"buy"})"));
	CHECK(cb::world::events().next() == cb::world::EventKind::None);
	CHECK(!cb::devices::guest_window().recent(32, cb::steady_seconds()));
	// a hello forgets the old session's requests
	cb::devices::on_minecraft_message(json::parse(R"({"t":"hello","proto":1})"));
	CHECK(!cb::devices::guest_window().recent(31, cb::steady_seconds()));
	cb::devices::guest_window().note(1, 31, cb::steady_seconds());
	cb::devices::on_link_dropped();
	CHECK(!cb::devices::guest_window().recent(31, cb::steady_seconds()));
	cb::world::events().clear();
}

TEST(device_box_follows_kind_size_and_yaw)
{
	const double m = cb::devices::kBoxMargin;
	const cb::Aabb door = cb::devices::device_box(device(1, {10.0, 20.0, 5.0}, "door"));
	CHECK(near(door.min.x, 10.0 - 1.2 - m) && near(door.max.x, 10.0 + 1.2 + m)); // width along right (+X at yaw 0)
	CHECK(near(door.min.y, 20.0 - 0.3 - m) && near(door.max.y, 20.0 + 0.3 + m)); // depth along forward (+Y)
	CHECK(near(door.min.z, 5.0 - m) && near(door.max.z, 5.0 + 2.8 + m));        // stands on its position
	Device turned = device(1, {10.0, 20.0, 5.0}, "door");
	turned.yaw = 90.0;
	const cb::Aabb t = cb::devices::device_box(turned);
	CHECK(near(t.max.x - t.min.x, 0.6 + 2 * m, 1e-9) && near(t.max.y - t.min.y, 2.4 + 2 * m, 1e-9));
	turned.yaw = 45.0; // the turned footprint's bounding box
	const double half = (2.4 / 2 + 0.6 / 2) * std::sqrt(0.5) + m;
	CHECK(near(cb::devices::device_box(turned).max.x, 10.0 + half, 1e-9));
	const cb::Aabb light = cb::devices::device_box(device(1, {0.0, 0.0, 4.0}, "light")); // wall-mounted: centred
	CHECK(near(light.min.z, 4.0 - 0.4 - m) && near(light.max.z, 4.0 + 0.4 + m));
	for (const char *kind : cb::devices::kKinds)
	{
		const auto s = cb::devices::default_size(kind);
		CHECK(s.w > 0 && s.h > 0 && s.d > 0 && s.w <= 4.0 && s.h <= 6.0);
	}
	const auto other = cb::devices::default_size("toaster");
	CHECK(near(other.w, 1.0) && near(other.h, 1.0) && !other.centered);
}

TEST(probe_hits_inside_device_boxes_never_become_ground)
{
	const std::vector<cb::Aabb> boxes{cb::devices::device_box(device(1, {0.0, 0.0, 0.0}, "door"))};
	std::vector<Vec3> wall{{0.0, 0.3, 1.0}, {5.0, 0.0, 1.0}, {1.0, -0.3, 2.5}, {0.0, 0.0, 3.2}};
	std::vector<int> codes{6, 7, 8, 9};
	CHECK(cb::drop_hits_in_boxes(wall, &codes, boxes) == 2);
	CHECK(wall.size() == 2 && near(wall[0].x, 5.0) && near(wall[1].z, 3.2)); // beside it, and above it
	CHECK(codes == std::vector<int>({7, 9}));
	std::vector<Vec3> down{{0.0, 0.0, 2.8}, {0.0, 0.0, -1.0}};
	std::vector<int> wrong{1}; // not one code per hit: left alone
	CHECK(cb::drop_hits_in_boxes(down, &wrong, boxes) == 1 && down.size() == 1 && wrong.size() == 1);
	CHECK(cb::drop_hits_in_boxes(down, nullptr, {}) == 0 && down.size() == 1);
	CHECK(cb::inside(boxes[0], boxes[0].min) && cb::inside(boxes[0], boxes[0].max));

	// the filtered hits never reach the ground ProbeSet sends
	std::vector<Vec3> hits{{0.0, 0.0, 1.5}};
	cb::drop_hits_in_boxes(hits, nullptr, boxes);
	cb::ProbeSet set;
	const cb::Delta d = set.submit({0.0, 0.0, 0.0}, {}, hits, 32.0, 0.0, 1.0);
	CHECK(d.add.empty());
}

TEST(device_boxes_expire_and_are_bounded)
{
	cb::devices::BoxSet set;
	CHECK(set.current(0.0).empty());
	std::vector<Device> list;
	for (int i = 0; i < 300; ++i)
		list.push_back(device(uint64_t(i + 1), {double(i), 0.0, 0.0}));
	set.set(list, 100.0);
	CHECK(set.current(100.0).size() == cb::devices::BoxSet::kMax);
	CHECK(set.current(105.0).size() == cb::devices::BoxSet::kMax);
	CHECK(set.current(105.1).empty());
	set.set({}, 106.0);
	CHECK(set.current(106.0).empty());
}
