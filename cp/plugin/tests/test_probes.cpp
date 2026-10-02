// ProbeSet: which Night City cells Minecraft is told about, and when they are taken back.
#include "probes.hpp"
#include "test.hpp"
#include <vector>

namespace
{
	using Quads = std::vector<int>;
	constexpr double kRadius = 24.0;

	// Cyberpunk-space feet; Minecraft column (10, -20)
	const cb::Vec3 kCenter{10.0, 20.0, 5.0};
	// down hits -> {10, -21, 3, 4} and {11, -21, 3, 4}; wall hit -> {10, -23, 5, 5}
	const std::vector<cb::Vec3> kDown{{10.4, 20.6, 5.0}, {11.2, 20.6, 5.0}};
	const std::vector<cb::Vec3> kWall{{10.4, 22.5, 5.7}};
	const Quads kAll{10, -23, 5, 5, 10, -21, 3, 4, 11, -21, 3, 4}; // ordered by x, z, y

	bool empty(const cb::Delta &d)
	{
		return d.add.empty() && d.remove.empty();
	}
}

TEST(same_hits_twice_send_nothing_new)
{
	cb::ProbeSet set;
	const cb::Delta first = set.submit(kCenter, kDown, kWall, kRadius, 0.0);
	CHECK(first.add == kAll);
	CHECK(first.remove.empty());
	CHECK(empty(set.submit(kCenter, kDown, kWall, kRadius, 0.5)));
}

TEST(moving_30m_removes_ranges_beyond_the_radius)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, kWall, kRadius, 0.0);
	const cb::Delta moved = set.submit({kCenter.x + 30.0, kCenter.y, kCenter.z}, {}, {}, kRadius, 0.1);
	CHECK(moved.add.empty());
	CHECK(moved.remove == kAll);
	// hits that are now out of range are ignored rather than re-added
	CHECK(empty(set.submit({kCenter.x + 30.0, kCenter.y, kCenter.z}, kDown, kWall, kRadius, 0.2)));
}

TEST(range_not_rehit_for_3s_after_its_column_is_probed_again_is_removed)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, {}, kRadius, 0.0);
	// column (10, -21) is probed again and hits higher up (a car roof, a new floor): {10, -21, 7, 8}
	const std::vector<cb::Vec3> higher{{10.4, 20.6, 9.0}};
	const cb::Delta reprobed = set.submit(kCenter, higher, {}, kRadius, 1.0);
	CHECK(reprobed.add == (Quads{10, -21, 7, 8}));
	CHECK(reprobed.remove.empty());
	CHECK(empty(set.submit(kCenter, {}, {}, kRadius, 2.5))); // the old floor was confirmed only 2.5 s ago
	const cb::Delta expired = set.submit(kCenter, {}, {}, kRadius, 3.5);
	CHECK(expired.add.empty());
	CHECK(expired.remove == (Quads{10, -21, 3, 4})); // column (11, -21) was never re-probed, so it stays
}

TEST(clear_then_submit_adds_everything_again)
{
	cb::ProbeSet set;
	set.submit(kCenter, kDown, kWall, kRadius, 0.0);
	set.clear();
	const cb::Delta again = set.submit(kCenter, kDown, kWall, kRadius, 0.5);
	CHECK(again.add == kAll);
	CHECK(again.remove.empty());
}

TEST(overlapping_ranges_keep_their_shared_cells)
{
	// column (2, 3): a step whose top is at 0.3 m ({2, 3, -1, 0}) and its riser hit by a wall ray ({2, 3, 0, 0})
	const cb::Vec3 origin{0.0, 0.0, 0.0};
	const std::vector<cb::Vec3> riser{{2.5, -3.5, 0.5}};
	cb::ProbeSet set;
	const cb::Delta first = set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 0.3}}, riser, kRadius, 0.0);
	CHECK(first.add == (Quads{2, 3, -1, 0}));
	set.submit(origin, std::vector<cb::Vec3>{{2.5, -3.5, 5.0}}, riser, kRadius, 1.0);
	// the step's range expires, but cell 0 is still claimed by the riser
	const cb::Delta expired = set.submit(origin, {}, riser, kRadius, 3.5);
	CHECK(expired.add.empty());
	CHECK(expired.remove == (Quads{2, 3, -1, -1}));
}
