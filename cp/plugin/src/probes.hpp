// Night City collision for Minecraft: turns the scripts' raycast hits (Cyberpunk space) into solid Minecraft block
// ranges and remembers what Minecraft already has, so only changes go over the link as `ground` / `unground`.
// Phase 3 (megaplan 2026-10-04 sections 1.8 and 1.9 with the Phase 0 amendment, task 3-D1): cells observed empty are
// told apart from cells that only left the radius (`unground.seen`), and each range carries a material code
// (`ground.m`) from the scripts' material names through one fixed name table.
#pragma once
#include "cbmath.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <span>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace cb
{
	/// Cells that changed, as {x, z, yBottom, yTop} quadruples (the `ground` / `unground` `c` arrays).
	struct Delta
	{
		std::vector<int> add;       // became solid (`ground`)
		std::vector<int> seen;      // observed empty inside the radius (`unground` with `seen:true`)
		std::vector<int> remove;    // only left the radius (`unground` without `seen`: the replica keeps them)
		std::vector<int> materials; // `ground.m`: one code per `add` quadruple; empty when the tick had no materials
	};

	/// The material codes of one tick, parallel to its down and wall hits (Cyberblock_SubmitProbeMaterials).
	struct ProbeMaterials
	{
		std::span<const int> down, wall;
	};

	/// Seconds on a steady clock (the default time base of ProbeSet::submit).
	double steady_seconds();

	/// A range stays solid while its column is within `radius` blocks of the center and it was hit within the last
	/// 3 s; once its column is hit again at another height and 3 s pass without a hit on the range, it is removed and its
	/// freed cells are `seen` (observed empty). Columns that are not hit again keep their ranges (the scripts report hits
	/// only, never misses); a column that leaves the radius frees its cells as `remove`. Cells are counted per range, so
	/// removing a range never frees a cell that an overlapping range still claims.
	class ProbeSet
	{
	public:
		/// `cpCenter`: V's feet in Cyberpunk space; `down` / `wall`: hit positions in Cyberpunk space; `off`: the ground
		/// alignment (plan 2026-10-03-retest2 section 1.1), applied to the centre and to every range. A change of `off`
		/// needs a clear() first: the ranges already sent were made with the old one. `materials`: this tick's codes, or
		/// null when the scripts sent none (then Delta::materials stays empty and Minecraft's heuristic applies); codes
		/// whose counts differ from the hits' are treated as none. A cell keeps the code of the range that made it solid;
		/// stacked cells merge into one quadruple only when their codes match.
		Delta submit(Vec3 cpCenter, std::span<const Vec3> down, std::span<const Vec3> wall, double radius, double off,
					 double now = steady_seconds(), const ProbeMaterials *materials = nullptr);
		/// Forget everything (sent with `clear`, which empties Minecraft's side too).
		void clear();

	private:
		using Column = std::pair<int, int>; // Minecraft x, z
		using Cell = std::array<int, 3>;    // Minecraft x, z, y
		struct RangeState
		{
			double hitAt = 0.0; // time of its last hit
			int material = 0;   // the code of the hit that made it
		};
		struct CellState
		{
			int ranges = 0;   // number of ranges covering it
			int material = 0; // the code of the range that made it solid
		};

		std::map<Range, RangeState> m_ranges;
		std::map<Column, double> m_hitAt; // column -> time of its last hit at any height
		std::map<Cell, CellState> m_cells; // solid cells
	};

	// ---- probe materials (megaplan section 1.8 amendment item 1; values unproven until CET B1-B5) -------------------

	/// The `ground.m` codes, index = code: 0 unknown, 1..10 the replica materials, 11..26 neon in vanilla dye order. They
	/// match the shared vectors key `materials` (cb_tests checks it once tools.gen_vectors writes it).
	inline constexpr const char *kMaterialCodes[] = {
		"unknown",         "concrete_light", "concrete_dark", "asphalt",      "sidewalk",       "glass",
		"metal",           "brick",          "tile",          "ground",       "water",          "neon_white",
		"neon_orange",     "neon_magenta",   "neon_light_blue", "neon_yellow", "neon_lime",     "neon_pink",
		"neon_gray",       "neon_light_gray", "neon_cyan",    "neon_purple",  "neon_blue",      "neon_brown",
		"neon_green",      "neon_red",       "neon_black"};

	/// One entry of the name table: a lower-case substring of a physics material name or mesh appearance name.
	struct MaterialName
	{
		const char *substring;
		int code;
	};
	/// The fixed name table, searched in order (the first entry whose substring the name contains wins).
	std::span<const MaterialName> material_table();
	/// The code of a material or mesh appearance name (case ignored). An empty name is 0 and known (nothing to log);
	/// a name no entry matches is 0 with `known` false.
	int material_code(std::string_view name, bool *known = nullptr);

	/// The codes of the names seen so far, by CName hash, so each name is looked up (and logged when unknown) once. At
	/// most kMaxNames are remembered; past that names are still answered but no longer remembered or flagged.
	class MaterialCache
	{
	public:
		static constexpr size_t kMaxNames = 4096;
		std::optional<int> find(uint64_t key) const;
		/// Looks `name` up and remembers it under `key`; `firstUnknown` is true when an unknown non-empty name was
		/// remembered now (the caller logs it once).
		int add(uint64_t key, std::string_view name, bool &firstUnknown);
		size_t size() const { return m_codes.size(); }

	private:
		std::unordered_map<uint64_t, int> m_codes;
	};
}
