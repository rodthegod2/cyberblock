// Field-by-field JSON validation and number formatting shared by the code that reads and builds wire messages
// (world.cpp, snapshots.cpp). A validator throws Bad; the message parser catches it and rejects the message or entry.
#pragma once
#include "cbmath.hpp"
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <nlohmann/json.hpp>
#include <string>
#include <string_view>

namespace cb::wire
{
	/// Every position and vector component must be finite with |c| < kMaxCoord (plan 2026-10-03 section 1.2).
	inline constexpr double kMaxCoord = 3e7;

	/// A field that failed validation.
	struct Bad
	{
		std::string why;
	};

	/// A JSON number within [lo, hi] (NaN and infinities fail the comparison).
	double number(const nlohmann::json &m, const char *key, double lo, double hi);
	/// A whole number in [lo, hi] (3 and 3.0 are the same: a wire writer may print an int as a float).
	int32_t integer(const nlohmann::json &m, const char *key, double lo, double hi);
	/// A bool, false when the key is absent.
	bool optional_bool(const nlohmann::json &m, const char *key);
	/// [x, y, z] with every |component| < kMaxCoord; `whole` also demands whole numbers (block coordinates).
	Vec3 triple(const nlohmann::json &m, const char *key, bool whole);
	/// A Minecraft-space [x, y, z] position, returned in Cyberpunk space with the ground alignment `off` applied
	/// (mc_to_cp_pos; plan 2026-10-03-retest2 section 1.1).
	Vec3 cp_triple(const nlohmann::json &m, const char *key, double off);
	/// A Minecraft-space [x, y, z] velocity or impulse, returned in Cyberpunk space (mc_vel_to_cp: never offset).
	Vec3 cp_vector(const nlohmann::json &m, const char *key);
	/// A decimal uint64 in a string (the wire cannot carry one as a number).
	uint64_t entity_id(const nlohmann::json &m, const char *key);
	/// A decimal-string id, or 0 when the key is absent or null.
	uint64_t optional_entity_id(const nlohmann::json &m, const char *key);
	/// A string of 1..maxLength characters from [a-z0-9_] (`digits`) or [a-z_].
	std::string word(const nlohmann::json &m, const char *key, size_t maxLength, bool digits);

	/// A string that is one of `allowed`.
	template<size_t N>
	std::string one_of(const nlohmann::json &m, const char *key, const char *const (&allowed)[N])
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a string"};
		const std::string &text = it->get_ref<const std::string &>();
		if (std::find(std::begin(allowed), std::end(allowed), std::string_view(text)) == std::end(allowed))
			throw Bad{std::string(key) + " is not a known name"};
		return text;
	}

	/// `text` cut to at most `maxBytes` bytes without splitting a UTF-8 character.
	std::string truncate_utf8(std::string_view text, size_t maxBytes);

	/// Wire numbers are rounded so floats do not print as 12.345000267028809.
	double rounded(double v, double scale);
	nlohmann::json triple_json(const Vec3 &v);
	bool finite(const Vec3 &v);
}
