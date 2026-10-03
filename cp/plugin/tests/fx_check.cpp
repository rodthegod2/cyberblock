// fx_check (re-test 3 plan task D7): compiles cp/shaders/Cyberblock.fx the way ReShade does for a D3D12 game, with
// ReShade's own FX front end built from third_party/reshade/source (BSD-3-Clause: preprocessor, lexer, parser, symbol
// table, expression evaluator, HLSL code generator) and D3DCompile for every entry point at shader model 5.1. A
// preprocessor, parser or HLSL error fails this program, and so the build (CMakeLists.txt runs it) and ctest.
//
// Usage: fx_check <Cyberblock.fx> <include directory with ReShade.fxh> [width height]
#include "effect_codegen.hpp"
#include "effect_parser.hpp"
#include "effect_preprocessor.hpp"
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <string>

namespace
{
	/// The macros ReShade's runtime defines before it preprocesses an effect (runtime.cpp), for a D3D12 game.
	void define_runtime_macros(reshadefx::preprocessor &pp, int width, int height)
	{
		pp.add_macro_definition("__RESHADE__", "60000");
		pp.add_macro_definition("__RESHADE_PERMUTATION__", "0");
		pp.add_macro_definition("__RESHADE_PERFORMANCE_MODE__", "0");
		pp.add_macro_definition("__VENDOR__", "0");
		pp.add_macro_definition("__DEVICE__", "0");
		pp.add_macro_definition("__RENDERER__", "0xc000"); // D3D12, feature level 12_0
		pp.add_macro_definition("__APPLICATION__", "0");
		pp.add_macro_definition("BUFFER_WIDTH", std::to_string(width));
		pp.add_macro_definition("BUFFER_HEIGHT", std::to_string(height));
		pp.add_macro_definition("BUFFER_RCP_WIDTH", "(1.0 / BUFFER_WIDTH)");
		pp.add_macro_definition("BUFFER_RCP_HEIGHT", "(1.0 / BUFFER_HEIGHT)");
		pp.add_macro_definition("BUFFER_COLOR_SPACE", "1");
		pp.add_macro_definition("BUFFER_COLOR_FORMAT", "28"); // r8g8b8a8_unorm
		pp.add_macro_definition("BUFFER_COLOR_BIT_DEPTH", "8");
	}
}

int main(int argc, char **argv)
{
	if (argc < 3)
	{
		std::fprintf(stderr, "usage: fx_check <effect.fx> <include directory> [width height]\n");
		return 2;
	}
	const std::filesystem::path effect = std::filesystem::u8path(argv[1]);
	const std::filesystem::path includes = std::filesystem::u8path(argv[2]);
	const int width = argc >= 5 ? std::atoi(argv[3]) : 2560;
	const int height = argc >= 5 ? std::atoi(argv[4]) : 1440;

	reshadefx::preprocessor pp;
	pp.add_include_path(includes);
	pp.add_include_path(effect.parent_path());
	define_runtime_macros(pp, width, height);
	if (!pp.append_file(effect))
	{
		std::fprintf(stderr, "fx_check: preprocessing %s failed:\n%s\n", effect.u8string().c_str(), pp.errors().c_str());
		return 1;
	}
	if (!pp.errors().empty())
		std::fprintf(stderr, "%s", pp.errors().c_str()); // warnings

	std::unique_ptr<reshadefx::codegen> codegen(reshadefx::create_codegen_dxbc(51, false, false, 1));
	reshadefx::parser parser;
	const bool parsed = parser.parse(pp.output(), codegen.get());
	if (!parser.errors().empty())
		std::fprintf(stderr, "%s", parser.errors().c_str());
	if (!parsed)
	{
		std::fprintf(stderr, "fx_check: %s does not parse\n", effect.u8string().c_str());
		return 1;
	}

	const reshadefx::effect_module &module = codegen->module();
	int failed = 0;
	for (const auto &[name, type] : module.entry_points)
	{
		std::string cso, assembly, errors;
		const bool ok = codegen->assemble_code_for_entry_point(name, cso, assembly, errors);
		if (!errors.empty())
			std::fprintf(stderr, "%s: %s", name.c_str(), errors.c_str());
		if (!ok || cso.empty())
		{
			std::fprintf(stderr, "fx_check: entry point %s does not compile\n", name.c_str());
			++failed;
		}
	}
	std::printf("fx_check: %s: %zu techniques, %zu uniforms, %zu textures, %zu entry points, %d failed\n",
				effect.filename().u8string().c_str(), module.techniques.size(), module.uniforms.size(), module.textures.size(),
				module.entry_points.size(), failed);
	return failed == 0 && !module.entry_points.empty() ? 0 : 1;
}
