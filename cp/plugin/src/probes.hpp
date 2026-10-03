// Night City collision for Minecraft: turns the scripts' raycast hits (Cyberpunk space) into solid Minecraft block
// ranges and remembers what Minecraft already has, so only changes go over the link as `ground` / `unground`.
#pragma once
#include "cbmath.hpp"
#include <map>
#include <span>
#include <utility>
#include <vector>

namespace cb
{
	/// Cells that became solid / free, as {x, z, yBottom, yTop} quadruples (the `ground` / `unground` `c` arrays).
	struct Delta
	{
		std::vector<int> add, remove;
	};

	/// Seconds on a steady clock (the default time base of ProbeSet::submit).
	double steady_seconds();

	/// A range stays solid while its column is within `radius` blocks of the center and it was hit within the last
	/// 3 s; once its column is hit again at another height and 3 s pass without a hit on the range, it is removed.
	/// Columns that are not hit again keep their ranges (the scripts report hits only, never misses). Cells are
	/// counted per range, so removing a range never frees a cell that an overlapping range still claims.
	class ProbeSet
	{
	public:
		/// `cpCenter`: V's feet in Cyberpunk space; `down` / `wall`: hit positions in Cyberpunk space; `off`: the ground
		/// alignment (plan 2026-10-03-retest2 section 1.1), applied to the centre and to every range. A change of `off`
		/// needs a clear() first: the ranges already sent were made with the old one.
		Delta submit(Vec3 cpCenter, std::span<const Vec3> down, std::span<const Vec3> wall, double radius, double off,
					 double now = steady_seconds());
		/// Forget everything (sent with `clear`, which empties Minecraft's side too).
		void clear();

	private:
		using Column = std::pair<int, int>; // Minecraft x, z
		using Cell = std::array<int, 3>;    // Minecraft x, z, y

		std::map<Range, double> m_ranges; // range -> time of its last hit
		std::map<Column, double> m_hitAt; // column -> time of its last hit at any height
		std::map<Cell, int> m_cells;      // solid cell -> number of ranges covering it
	};
}
