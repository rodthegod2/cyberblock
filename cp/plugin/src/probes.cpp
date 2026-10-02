#include "probes.hpp"
#include <chrono>

namespace
{
	constexpr double kConfirmSeconds = 3.0;

	/// Appends a cell to a quadruple list, growing the last range when the cell sits right on top of it (cells arrive
	/// ordered by x, z, y).
	void append(std::vector<int> &quads, const std::array<int, 3> &cell)
	{
		const size_t n = quads.size();
		if (n >= 4 && quads[n - 4] == cell[0] && quads[n - 3] == cell[1] && quads[n - 1] + 1 == cell[2])
		{
			quads[n - 1] = cell[2];
			return;
		}
		quads.insert(quads.end(), {cell[0], cell[1], cell[2], cell[2]});
	}
}

namespace cb
{
	double steady_seconds()
	{
		return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
	}

	Delta ProbeSet::submit(Vec3 cpCenter, std::span<const Vec3> down, std::span<const Vec3> wall, double radius,
						   double now)
	{
		const Vec3 center = cp_to_mc_pos(cpCenter.x, cpCenter.y, cpCenter.z);
		const auto in_radius = [&](int x, int z) {
			const double dx = x + 0.5 - center.x, dz = z + 0.5 - center.z;
			return dx * dx + dz * dz <= radius * radius;
		};

		std::map<Cell, bool> wasSolid; // every cell this call touches -> solid before it
		const auto count = [&](const Range &r, int step) {
			for (int y = r[2]; y <= r[3]; ++y)
			{
				const Cell cell{r[0], r[1], y};
				int &n = m_cells[cell];
				wasSolid.try_emplace(cell, n > 0);
				if ((n += step) <= 0)
					m_cells.erase(cell);
			}
		};
		const auto hit = [&](const Range &r) {
			if (!in_radius(r[0], r[1]))
				return;
			m_hitAt[{r[0], r[1]}] = now;
			const auto [it, added] = m_ranges.try_emplace(r, now);
			if (added)
				count(r, +1);
			else
				it->second = now;
		};
		for (const Vec3 &h : down)
			hit(down_hit_to_range(h.x, h.y, h.z));
		for (const Vec3 &h : wall)
			hit(wall_hit_to_range(h.x, h.y, h.z));

		for (auto it = m_ranges.begin(); it != m_ranges.end();)
		{
			const Range &r = it->first;
			const auto column = m_hitAt.find({r[0], r[1]});
			const bool disproved =
				now - it->second > kConfirmSeconds && column != m_hitAt.end() && column->second > it->second;
			if (!in_radius(r[0], r[1]) || disproved)
			{
				count(r, -1);
				it = m_ranges.erase(it);
			}
			else
				++it;
		}
		std::erase_if(m_hitAt, [&](const auto &column) { return !in_radius(column.first.first, column.first.second); });

		Delta delta;
		for (const auto &[cell, was] : wasSolid)
		{
			const bool is = m_cells.contains(cell);
			if (is != was)
				append(is ? delta.add : delta.remove, cell);
		}
		return delta;
	}

	void ProbeSet::clear()
	{
		m_ranges.clear();
		m_hitAt.clear();
		m_cells.clear();
	}
}
