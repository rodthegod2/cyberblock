cmake_minimum_required(VERSION 3.28)
project(Cyberblock VERSION 1.0.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)

include(FetchContent)
FetchContent_Declare(json URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz)
FetchContent_MakeAvailable(json)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
# The one place the DLL's version is written (re-test 3 plan section 1.13); main.cpp, natives.hpp and link.cpp read it.
set(CB_VERSION_DEFINITIONS CB_VERSION="${PROJECT_VERSION}" CB_VERSION_MAJOR=${PROJECT_VERSION_MAJOR}
	CB_VERSION_MINOR=${PROJECT_VERSION_MINOR} CB_VERSION_PATCH=${PROJECT_VERSION_PATCH})
# RED4ext.SDK exports the static library `RED4ext.SDK` and its aliases `RED4ext::SDK` / `RED4ext::RED4ext.SDK`.
add_subdirectory(${ROOT}/third_party/RED4ext.SDK ${CMAKE_BINARY_DIR}/red4ext.sdk EXCLUDE_FROM_ALL)

file(GLOB CB_SOURCES CONFIGURE_DEPENDS src/*.cpp)
add_library(Cyberblock SHARED ${CB_SOURCES})
target_include_directories(Cyberblock PRIVATE src ${ROOT}/third_party/reshade/include)
target_link_libraries(Cyberblock PRIVATE RED4ext::SDK nlohmann_json::nlohmann_json ws2_32 user32 dxgi)
target_compile_definitions(Cyberblock PRIVATE ${CB_VERSION_DEFINITIONS})

enable_testing()
add_executable(cb_tests tests/test_main.cpp tests/test_cbmath.cpp tests/test_probes.cpp tests/test_world.cpp tests/test_events.cpp
	tests/test_snapshots.cpp tests/test_messages.cpp tests/test_keys.cpp tests/test_iat.cpp tests/test_link.cpp tests/test_ws.cpp
	tests/test_follow.cpp tests/test_view.cpp tests/test_screenfx.cpp tests/test_survey.cpp
	src/cbmath.cpp src/screenfx.cpp src/follow.cpp src/iat.cpp src/keys.cpp src/link.cpp src/log.cpp src/probes.cpp src/snapshots.cpp src/survey.cpp src/wire.cpp
	src/world.cpp src/ws.cpp)
target_sources(cb_tests PRIVATE tests/test_session.cpp) # Phase 1 (megaplan 2026-10-04 task 1-H1)
target_sources(cb_tests PRIVATE tests/test_figures.cpp) # Phase 5 (megaplan 2026-10-04 task 5-D1)
target_include_directories(cb_tests PRIVATE src)
target_link_libraries(cb_tests PRIVATE nlohmann_json::nlohmann_json ws2_32)
# The UI bridge's pure builders (package U, plan 2026-10-03-retest3-polish section 1.3); ui.cpp includes no RED4ext.
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/ui.cpp AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_ui.cpp)
	target_sources(cb_tests PRIVATE src/ui.cpp tests/test_ui.cpp)
endif()
target_compile_definitions(cb_tests PRIVATE CB_VECTORS="${ROOT}/shared/vectors.json" ${CB_VERSION_DEFINITIONS})
add_test(NAME cb_tests COMMAND cb_tests)

# fx_check (re-test 3 plan task D7): ReShade's own FX front end (third_party/reshade, BSD-3-Clause) compiles
# cp/shaders/Cyberblock.fx with ReShade.fxh as ReShade does for D3D12; a shader error fails the build and ctest.
set(RESHADE_FX ${ROOT}/third_party/reshade/source)
add_executable(fx_check tests/fx_check.cpp ${RESHADE_FX}/effect_lexer.cpp ${RESHADE_FX}/effect_preprocessor.cpp
	${RESHADE_FX}/effect_parser_exp.cpp ${RESHADE_FX}/effect_parser_stmt.cpp ${RESHADE_FX}/effect_symbol_table.cpp
	${RESHADE_FX}/effect_expression.cpp ${RESHADE_FX}/effect_codegen_dxbc.cpp)
target_include_directories(fx_check PRIVATE ${RESHADE_FX})
set_target_properties(fx_check PROPERTIES CXX_STANDARD 17) # ReShade's sources are C++17 (u8string returns std::string)
target_compile_definitions(fx_check PRIVATE _CRT_SECURE_NO_WARNINGS)
target_link_libraries(fx_check PRIVATE d3dcompiler)
set(CB_FX ${ROOT}/cp/shaders/Cyberblock.fx)
set(CB_FX_INCLUDE ${ROOT}/cp/shaders/vendor)
add_test(NAME fx_check COMMAND fx_check ${CB_FX} ${CB_FX_INCLUDE})
add_custom_command(OUTPUT ${CMAKE_BINARY_DIR}/fx_check.stamp
	COMMAND fx_check ${CB_FX} ${CB_FX_INCLUDE}
	COMMAND ${CMAKE_COMMAND} -E touch ${CMAKE_BINARY_DIR}/fx_check.stamp
	DEPENDS fx_check ${CB_FX} ${CB_FX_INCLUDE}/ReShade.fxh
	COMMENT "fx_check: compiling Cyberblock.fx")
add_custom_target(fx_checked ALL DEPENDS ${CMAKE_BINARY_DIR}/fx_check.stamp)

# Manual link check against a running Minecraft (tests/link_smoke.cpp); built only when asked for.
add_executable(link_smoke EXCLUDE_FROM_ALL tests/link_smoke.cpp src/link.cpp src/ws.cpp src/log.cpp)
target_include_directories(link_smoke PRIVATE src)
target_link_libraries(link_smoke PRIVATE nlohmann_json::nlohmann_json ws2_32)
target_compile_definitions(link_smoke PRIVATE ${CB_VERSION_DEFINITIONS})
