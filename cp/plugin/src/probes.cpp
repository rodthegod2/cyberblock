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

	/// Searched in order: neon colours before plain neon, light_blue / light_gray before blue / gray, concrete_dark before
	/// concrete. The physics material and mesh appearance names are unproven (Phase 0 b, CET B1-B5).
	constexpr cb::MaterialName kTable[] = {
		{"unknown", 0},
		{"neon_white", 11}, {"neon_orange", 12}, {"neon_magenta", 13}, {"neon_light_blue", 14}, {"neon_yellow", 15},
		{"neon_lime", 16}, {"neon_pink", 17}, {"neon_light_gray", 19}, {"neon_gray", 18}, {"neon_cyan", 20},
		{"neon_purple", 21}, {"neon_blue", 22}, {"neon_brown", 23}, {"neon_green", 24}, {"neon_red", 25},
		{"neon_black", 26},
		{"neon", 13}, // a neon of no known colour glows magenta
		{"concrete_dark", 2}, {"concrete_light", 1}, {"concrete", 1},
		{"asphalt", 3},
		{"sidewalk", 4}, {"pavement", 4},
		{"glass", 5},
		{"metal", 6}, {"steel", 6},
		{"brick", 7},
		{"tile", 8},
		{"water", 10},
		{"ground", 9}, {"dirt", 9}, {"grass", 9}, {"sand", 9}, {"mud", 9},
	};
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
		const auto hit = [&](const Range &r, int material) {
			if (!in_radius(r[0], r[1]))
				return;
			m_hitAt[{r[0], r[1]}] = now;
			const auto [it, added] = m_ranges.try_emplace(r, RangeState{now, material});
			if (added)
				count(r, +1, material);
			else
				it->second.hitAt = now;
		};
		for (size_t i = 0; i < down.size(); ++i)
			hit(down_hit_to_range(down[i].x, down[i].y, down[i].z, off), materials ? materials->down[i] : 0);
		for (size_t i = 0; i < wall.size(); ++i)
			hit(wall_hit_to_range(wall[i].x, wall[i].y, wall[i].z, off), materials ? materials->wall[i] : 0);

		for (auto it = m_ranges.begin(); it != m_ranges.end();)
		{
			const Range &r = it->first;
			const auto column = m_hitAt.find({r[0], r[1]});
			const bool disproved =
				now - it->second.hitAt > kConfirmSeconds && column != m_hitAt.end() && column->second > it->second.hitAt;
			if (!in_radius(r[0], r[1]) || disproved)
			{
				count(r, -1, 0);
				it = m_ranges.erase(it);
			}
			else
				++it;
		}
		std::erase_if(m_hitAt, [&](const auto &column) { return !in_radius(column.first.first, column.first.second); });

		Delta delta;
		std::vector<int> unused; // the removal lists carry no codes
		for (const auto &[cell, was] : wasSolid)
		{
			const auto solid = m_cells.find(cell);
			const bool is = solid != m_cells.end();
			if (is == was)
				continue;
			if (is)
				append(delta.add, delta.materials, cell, solid->second.material);
			else
				append(in_radius(cell[0], cell[1]) ? delta.seen : delta.remove, unused, cell, 0); // a column is in or out as a whole
		}
		if (!materials)
			delta.materials.clear();
		return delta;
	}

	void ProbeSet::clear()
	{
		m_ranges.clear();
		m_hitAt.clear();
		m_cells.clear();
	}

	std::span<const MaterialName> material_table()
	{
		return kTable;
	}

	int material_code(std::string_view name, bool *known)
	{
		if (known)
			*known = true;
		if (name.empty())
			return 0;
		std::string lower(name);
		std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return char(std::tolower(c)); });
		for (const MaterialName &entry : kTable)
			if (lower.find(entry.substring) != std::string::npos)
				return entry.code;
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
