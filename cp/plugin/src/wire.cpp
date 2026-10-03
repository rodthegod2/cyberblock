#include "wire.hpp"
#include <charconv>
#include <cmath>

namespace cb::wire
{
	using nlohmann::json;

	double number(const json &m, const char *key, double lo, double hi)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_number())
			throw Bad{std::string(key) + " is missing or not a number"};
		const double v = it->get<double>();
		if (!(v >= lo && v <= hi))
			throw Bad{std::string(key) + " is out of range"};
		return v;
	}

	int32_t integer(const json &m, const char *key, double lo, double hi)
	{
		const double v = number(m, key, lo, hi);
		if (v != std::floor(v))
			throw Bad{std::string(key) + " must be a whole number"};
		return static_cast<int32_t>(v);
	}

	bool optional_bool(const json &m, const char *key)
	{
		const auto it = m.find(key);
		if (it == m.end())
			return false;
		if (!it->is_boolean())
			throw Bad{std::string(key) + " is not a bool"};
		return it->get<bool>();
	}

	Vec3 triple(const json &m, const char *key, bool whole)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_array() || it->size() != 3)
			throw Bad{std::string(key) + " is missing or not [x, y, z]"};
		double v[3];
		for (size_t i = 0; i < 3; ++i)
		{
			const json &c = (*it)[i];
			if (!c.is_number())
				throw Bad{std::string(key) + " holds a non-number"};
			v[i] = c.get<double>();
			if (!(std::abs(v[i]) < kMaxCoord))
				throw Bad{std::string(key) + " is out of range"};
			if (whole && v[i] != std::floor(v[i]))
				throw Bad{std::string(key) + " must be whole numbers"};
		}
		return {v[0], v[1], v[2]};
	}

	Vec3 cp_triple(const json &m, const char *key)
	{
		const Vec3 at = triple(m, key, false);
		return mc_to_cp_pos(at.x, at.y, at.z);
	}

	uint64_t entity_id(const json &m, const char *key)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a decimal string"};
		const std::string &text = it->get_ref<const std::string &>();
		uint64_t id = 0;
		const char *const end = text.data() + text.size();
		const auto [stop, error] = std::from_chars(text.data(), end, id);
		if (error != std::errc{} || stop != end)
			throw Bad{std::string(key) + " is not a decimal uint64"};
		return id;
	}

	uint64_t optional_entity_id(const json &m, const char *key)
	{
		const auto it = m.find(key);
		return it == m.end() || it->is_null() ? 0 : entity_id(m, key);
	}

	std::string word(const json &m, const char *key, size_t maxLength, bool digits)
	{
		const auto it = m.find(key);
		if (it == m.end() || !it->is_string())
			throw Bad{std::string(key) + " is missing or not a string"};
		const std::string &text = it->get_ref<const std::string &>();
		const auto allowed = [digits](char c) { return (c >= 'a' && c <= 'z') || c == '_' || (digits && c >= '0' && c <= '9'); };
		if (text.empty() || text.size() > maxLength || !std::all_of(text.begin(), text.end(), allowed))
			throw Bad{std::string(key) + (digits ? " is not [a-z0-9_]{1," : " is not [a-z_]{1,") + std::to_string(maxLength) + "}"};
		return text;
	}

	double rounded(double v, double scale)
	{
		return std::round(v * scale) / scale;
	}

	json triple_json(const Vec3 &v)
	{
		return {rounded(v.x, 1e3), rounded(v.y, 1e3), rounded(v.z, 1e3)};
	}

	bool finite(const Vec3 &v)
	{
		return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z);
	}
}
