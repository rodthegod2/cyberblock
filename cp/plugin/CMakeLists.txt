cmake_minimum_required(VERSION 3.28)
project(Cyberblock VERSION 0.1.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)

include(FetchContent)
FetchContent_Declare(json URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz)
FetchContent_MakeAvailable(json)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
# RED4ext.SDK exports the static library `RED4ext.SDK` and its aliases `RED4ext::SDK` / `RED4ext::RED4ext.SDK`.
add_subdirectory(${ROOT}/third_party/RED4ext.SDK ${CMAKE_BINARY_DIR}/red4ext.sdk EXCLUDE_FROM_ALL)

file(GLOB CB_SOURCES CONFIGURE_DEPENDS src/*.cpp)
add_library(Cyberblock SHARED ${CB_SOURCES})
target_include_directories(Cyberblock PRIVATE src ${ROOT}/third_party/reshade/include)
target_link_libraries(Cyberblock PRIVATE RED4ext::SDK nlohmann_json::nlohmann_json ws2_32 user32 dxgi)

enable_testing()
add_executable(cb_tests tests/test_main.cpp tests/test_cbmath.cpp tests/test_probes.cpp tests/test_world.cpp tests/test_events.cpp
	tests/test_snapshots.cpp tests/test_messages.cpp tests/test_keys.cpp tests/test_iat.cpp tests/test_link.cpp tests/test_ws.cpp
	tests/test_follow.cpp
	src/cbmath.cpp src/follow.cpp src/iat.cpp src/keys.cpp src/link.cpp src/log.cpp src/probes.cpp src/snapshots.cpp src/wire.cpp
	src/world.cpp src/ws.cpp)
target_include_directories(cb_tests PRIVATE src)
target_link_libraries(cb_tests PRIVATE nlohmann_json::nlohmann_json ws2_32)
target_compile_definitions(cb_tests PRIVATE CB_VECTORS="${ROOT}/shared/vectors.json")
add_test(NAME cb_tests COMMAND cb_tests)

# Manual link check against a running Minecraft (tests/link_smoke.cpp); built only when asked for.
add_executable(link_smoke EXCLUDE_FROM_ALL tests/link_smoke.cpp src/link.cpp src/ws.cpp src/log.cpp)
target_include_directories(link_smoke PRIVATE src)
target_link_libraries(link_smoke PRIVATE nlohmann_json::nlohmann_json ws2_32)
