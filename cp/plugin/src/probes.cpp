#include "probes.hpp"
#include <algorithm>
#include <cctype>
#include <chrono>
#include <iterator>
#include <string>

namespace
{
	constexpr double kConfirmSeconds = 3.0;

	/// Appends a cell to a quadruple list (with its code to `codes`), growing the last range when the cell sits right on
	/// top of it with the same code (cells arrive ordered by x, z, y).
	void append(std::vector<int> &quads, std::vector<int> &codes, const std::array<int, 3> &cell, int code)
	{
		const size_t n = quads.size();
		if (n >= 4 && quads[n - 4] == cell[0] && quads[n - 3] == cell[1] && quads[n - 1] + 1 == cell[2] && codes.back() == code)
		{
			quads[n - 1] = cell[2];
			return;
		}
		quads.insert(quads.end(), {cell[0], cell[1], cell[2], cell[2]});
		codes.push_back(code);
	}

	// tools/cbmath.py's working rule (the real names are unproven until Phase 0's CET lines B1-B5); cb_tests pins every
	// list here to shared/vectors.json `materials`.
	constexpr const char *kDyes[] = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
									 "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
	constexpr int kCodeCount = int(std::size(cb::kMaterialCodes));

	/// code -> substrings (MATERIAL_MATCH); the neon codes get neon_<dye>, emissive_<dye> and led_<dye>.
	const std::vector<std::vector<std::string>> &match_table()
	{
		static const std::vector<std::vector<std::string>> table = [] {
			std::vector<std::vector<std::string>> t(kCodeCount);
			t[1] = {"concrete_light", "concrete_white", "plaster", "stucco", "limestone"};
			t[2] = {"concrete_dark", "concrete_black", "concrete_wet", "rubble", "stone_dark"};
			t[3] = {"asphalt", "road_", "tarmac", "street_surface"};
			t[4] = {"sidewalk", "pavement", "curb", "kerb", "paving"};
			t[5] = {"glass", "window", "windshield", "shopfront"};
			t[6] = {"metal", "steel", "iron_", "aluminium", "aluminum", "chrome", "scaffold", "grate"};
			t[7] = {"brick", "masonry"};
			t[8] = {"tile", "ceramic", "marble"};
			t[9] = {"terrain", "dirt", "sand", "gravel", "grass", "soil", "rock_", "mud", "badlands", "ground"};
			t[10] = {"water", "puddle", "pool", "sewage"};
			for (int i = 0; i < int(std::size(kDyes)); ++i)
				t[cb::kNeonFirst + i] = {std::string("neon_") + kDyes[i], std::string("emissive_") + kDyes[i],
										 std::string("led_") + kDyes[i]};
			return t;
		}();
		return table;
	}

	const std::vector<std::string> kNeonAny{"neon", "emissive", "hologram", "led_", "light_strip"};

	/// MATERIAL_ORDER: every dye neon, any other neon, glass, water, metal, brick, tile, asphalt, sidewalk, dark concrete,
	/// light concrete, ground.
	const std::vector<int> kOrder = [] {
		std::vector<int> order;
		for (int code = cb::kNeonFirst; code < kCodeCount; ++code)
			order.push_back(code);
		order.insert(order.end(), {cb::kAnyNeonRule, 5, 10, 6, 7, 8, 3, 4, 2, 1, 9});
		return order;
	}();
}

namespace cb
{
	double steady_seconds()
	{
		return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
	}

	Delta ProbeSet::submit(Vec3 cpCenter, std::span<const Vec3> down, std::span<const Vec3> wall, double radius, double off,
						   double now, const ProbeMaterials *materials)
	{
		if (materials && (materials->down.size() != down.size() || materials->wall.size() != wall.size()))
			materials = nullptr; // not this tick's codes: send none rather than wrong ones
		const Vec3 center = cp_to_mc_pos(cpCenter.x, cpCenter.y, cpCenter.z, off);
		const auto in_radius = [&](int x, int z) {
			const double dx = x + 0.5 - center.x, dz = z + 0.5 - center.z;
			return dx * dx + dz * dz <= radius * radius;
		};

		std::map<Cell, bool> wasSolid; // every cell this call touches -> solid before it
		const auto count = [&](const Range &r, int step, int material) {
			for (int y = r[2]; y <= r[3]; ++y)
			{
				const Cell cell{r[0], r[1], y};
				CellState &state = m_cells[cell];
				wasSolid.try_emplace(cell, state.ranges > 0);
				if (state.ranges == 0)
					state.material = material;
				if ((state.ranges += step) <= 0)
					m_cells.erase(cell);
			}
		};
		const double downStartY = center.y + kDownRayStart; // Minecraft y of this tick's down ray starts
		const auto hit = [&](const Range &r, int material, bool isDown) {
			if (!in_radius(r[0], r[1]))
				return;
			m_hitAt[{r[0], r[1]}] = now;
			if (isDown)
				m_downAt[{r[0], r[1]}] = DownHit{now, r[3], downStartY};
			const auto [it, added] = m_ranges.try_emplace(r, RangeState{now, material});
			if (added)
				count(r, +1, material);
			else
				it->second.hitAt = now;
		};
		for (size_t i = 0; i < down.size(); ++i)
			hit(down_hit_to_range(down[i].x, down[i].y, down[i].z, off), materials ? materials->down[i] : 0, true);
		for (size_t i = 0; i < wall.size(); ++i)
			hit(wall_hit_to_range(wall[i].x, wall[i].y, wall[i].z, off), materials ? materials->wall[i] : 0, false);

		// freed-cell candidates -> true while every disproved range that covered them had a down ray pass through them
		std::map<Cell, bool> observedEmpty;
		for (auto it = m_ranges.begin(); it != m_ranges.end();)
		{
			const Range &r = it->first;
			const Column column{r[0], r[1]};
			const auto lastHit = m_hitAt.find(column);
			const bool disproved =
				now - it->second.hitAt > kConfirmSeconds && lastHit != m_hitAt.end() && lastHit->second > it->second.hitAt;
			const bool inside = in_radius(r[0], r[1]);
			if (inside && !disproved)
			{
				++it;
				continue;
			}
			if (inside)
			{
				const auto downHit = m_downAt.find(column);
				for (int y = r[2]; y <= r[3]; ++y)
				{
					const bool through = downHit != m_downAt.end() && downHit->second.at > it->second.hitAt &&
										 downHit->second.top < y && y + 1 <= downHit->second.startY;
					const auto [entry, added] = observedEmpty.try_emplace(Cell{r[0], r[1], y}, through);
					entry->second = entry->second && through;
				}
			}
			count(r, -1, 0);
			it = m_ranges.erase(it);
		}
		const auto outside = [&](const auto &entry) { return !in_radius(entry.first.first, entry.first.second); };
		std::erase_if(m_hitAt, outside);
		std::erase_if(m_downAt, outside);

		Delta delta;
		std::vector<int> unused; // the removal lists carry no codes
		for (const auto &[cell, was] : wasSolid)
		{
			const auto solid = m_cells.find(cell);
			const bool is = solid != m_cells.end();
			if (is == was)
				continue;
			if (is)
			{
				append(delta.add, delta.materials, cell, solid->second.material);
				continue;
			}
			const auto observed = observedEmpty.find(cell);
			append(observed != observedEmpty.end() && observed->second ? delta.seen : delta.remove, unused, cell, 0);
		}
		if (!materials)
			delta.materials.clear();
		return delta;
	}

	void ProbeSet::clear()
	{
		m_ranges.clear();
		m_hitAt.clear();
		m_downAt.clear();
		m_cells.clear();
	}

	bool inside(const Aabb &box, Vec3 p)
	{
		return p.x >= box.min.x && p.x <= box.max.x && p.y >= box.min.y && p.y <= box.max.y && p.z >= box.min.z &&
			   p.z <= box.max.z;
	}

	size_t drop_hits_in_boxes(std::vector<Vec3> &hits, std::vector<int> *codes, std::span<const Aabb> boxes)
	{
		if (codes && codes->size() != hits.size())
			codes = nullptr;
		size_t kept = 0;
		for (size_t i = 0; i < hits.size(); ++i)
		{
			if (std::ranges::any_of(boxes, [&](const Aabb &box) { return inside(box, hits[i]); }))
				continue;
			hits[kept] = hits[i];
			if (codes)
				(*codes)[kept] = (*codes)[i];
			++kept;
		}
		const size_t dropped = hits.size() - kept;
		hits.resize(kept);
		if (codes)
			codes->resize(kept);
		return dropped;
	}

	std::span<const std::string> material_match(int code)
	{
		if (code < 0 || code >= kCodeCount)
			return {};
		return match_table()[code];
	}

	std::span<const std::string> material_neon_any()
	{
		return kNeonAny;
	}

	std::span<const int> material_order()
	{
		return kOrder;
	}

	int material_code(std::string_view name, bool *known)
	{
		if (known)
			*known = true;
		if (name.empty())
			return 0;
		std::string lower(name);
		std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return char(std::tolower(c)); });
		for (const int rule : kOrder)
		{
			const auto needles = rule == kAnyNeonRule ? material_neon_any() : material_match(rule);
			if (std::ranges::any_of(needles, [&](const std::string &n) { return lower.find(n) != std::string::npos; }))
				return rule == kAnyNeonRule ? kNeonFirst : rule;
		}
		if (known)
			*known = false;
		return 0;
	}

	std::optional<int> MaterialCache::find(uint64_t key) const
	{
		const auto it = m_codes.find(key);
		return it == m_codes.end() ? std::nullopt : std::optional(it->second);
	}

	int MaterialCache::add(uint64_t key, std::string_view name, bool &firstUnknown)
	{
		bool known = true;
		const int code = material_code(name, &known);
		firstUnknown = false;
		if (m_codes.size() < kMaxNames || m_codes.contains(key))
			firstUnknown = m_codes.try_emplace(key, code).second && !known;
		return code;
	}
}
