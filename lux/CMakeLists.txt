# =============================================================================
# Lux Engine - Library CMake
# Builds the core lux library as both STATIC and SHARED (.so).
# =============================================================================

# ── Gather all source files ────────────────────────────────────────────────
file(GLOB_RECURSE LUX_SOURCES
    core/*.cpp
    renderer/*.cpp
    audio/*.cpp
    animation/*.cpp
    assets/*.cpp
    encryption/*.cpp
    networking/*.cpp
    physics/*.cpp
    game/*.cpp
    platform/*.cpp
)

file(GLOB_RECURSE LUX_HEADERS
    core/*.h
    renderer/*.h
    audio/*.h
    animation/*.h
    assets/*.h
    encryption/*.h
    networking/*.h
    physics/*.h
    game/*.h
    platform/*.h
)

# ── Shared library (the .so loaded by Android) ────────────────────────────
add_library(lux_shared SHARED ${LUX_SOURCES} ${LUX_HEADERS})

target_include_directories(lux_shared PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/core
    ${CMAKE_CURRENT_SOURCE_DIR}/game
    ${CMAKE_SOURCE_DIR}                           # For mini_games/ to find lux/core/*.h
    ${CMAKE_SOURCE_DIR}/mini_games                # For cross-mini-game includes
)

target_link_libraries(lux_shared PUBLIC
    ${ANDROID_LOG_LIBRARY}
    ${ANDROID_VULKAN_LIBRARY}
    ${ANDROID_NATIVE_WINDOW_LIBRARY}
    ${ANDROID_OPENGLES_LIBRARY}
    ${ANDROID_EGL_LIBRARY}
)

# ── Optional third-party backends ──────────────────────────────────────────
if(TARGET filament::filament)
    target_link_libraries(lux_shared PUBLIC filament::filament)
    target_compile_definitions(lux_shared PRIVATE LUX_USE_FILAMENT=1)
endif()
if(TARGET filament::gltfio)
    target_link_libraries(lux_shared PUBLIC filament::gltfio)
endif()

if(TARGET miniaudio)
    target_link_libraries(lux_shared PUBLIC miniaudio)
    target_compile_definitions(lux_shared PRIVATE LUX_USE_MINIAUDIO=1)
endif()

if(TARGET ozz::animation)
    target_link_libraries(lux_shared PUBLIC ozz::animation ozz::base ozz::geometry)
    target_compile_definitions(lux_shared PRIVATE LUX_USE_OZZ=1)
endif()

if(TARGET sodium::sodium)
    target_link_libraries(lux_shared PUBLIC sodium::sodium)
    target_compile_definitions(lux_shared PRIVATE LUX_USE_SODIUM=1)
endif()

if(TARGET nakama::client)
    target_link_libraries(lux_shared PUBLIC nakama::client)
    target_compile_definitions(lux_shared PRIVATE LUX_USE_NAKAMA=1)
endif()

# ── Compiler flags ─────────────────────────────────────────────────────────
target_compile_definitions(lux_shared PRIVATE
    LUX_BUILD_SHARED
    $<$<CONFIG:Debug>:LUX_DEBUG>
    $<$<CONFIG:Release>:LUX_RELEASE>
)

if(LUX_ENABLE_HOT_RELOAD)
    target_compile_definitions(lux_shared PRIVATE LUX_ENABLE_HOT_RELOAD=1)
endif()

# ── Mini games are linked in from mini_games/ ──────────────────────────────
# They register themselves via the MiniGameRegistry.
