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
target_link_libraries(Cyberblock PRIVATE RED4ext::SDK nlohmann_json::nlohmann_json ws2_32 user32)

# Test sources arrive with tasks C2 (cbmath) and C3 (probes). Build `cb_tests` from whichever of them exist, so
# configuring a partial tree does not fail on a missing file (CMake rejects nonexistent sources at generate time).
enable_testing()
set(CB_TEST_SOURCES tests/test_main.cpp tests/test_cbmath.cpp tests/test_probes.cpp src/cbmath.cpp src/probes.cpp)
set(CB_TEST_PRESENT "")
foreach(CB_TEST_SOURCE IN LISTS CB_TEST_SOURCES)
  if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/${CB_TEST_SOURCE})
    list(APPEND CB_TEST_PRESENT ${CB_TEST_SOURCE})
  endif()
endforeach()
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_main.cpp)
  add_executable(cb_tests ${CB_TEST_PRESENT})
  target_include_directories(cb_tests PRIVATE src)
  target_link_libraries(cb_tests PRIVATE nlohmann_json::nlohmann_json)
  target_compile_definitions(cb_tests PRIVATE CB_VECTORS="${ROOT}/shared/vectors.json")
  add_test(NAME cb_tests COMMAND cb_tests)
endif()
