# =============================================================================
# Lux - Dependency Discovery for Android NDK
#
# Auto-discovers third-party libraries in:
#   1. Environment variable override (e.g. LUX_FILAMENT_DIR)
#   2. ${CMAKE_SOURCE_DIR}/third_party/<lib>
#
# All libraries are OPTIONAL — if a lib isn't found, stubs are used instead.
# Check CMake output for "Lux Deps:" lines to see what was found.
# =============================================================================

# ── Helper macro to find a shared library with proper Android paths ────────
macro(lux_find_native_lib LIB_NAME LIB_VAR TARGET_NAME HEADER_SUBDIR)
    set(options "")
    set(oneValueArgs "")
    set(multiValueArgs LIB_NAMES)
    cmake_parse_arguments(PARSE "${options}" "${oneValueArgs}" "${multiValueArgs}" ${ARGN})

    if(DEFINED ENV{LUX_${LIB_NAME}_DIR})
        set(${LIB_NAME}_DIR $ENV{LUX_${LIB_NAME}_DIR})
    else()
        set(${LIB_NAME}_DIR "${CMAKE_SOURCE_DIR}/third_party/${LIB_NAME}")
    endif()

    if(EXISTS "${${LIB_NAME}_DIR}")
        # Find all the .so files
        file(GLOB ${LIB_NAME}_SO_FILES "${${LIB_NAME}_DIR}/lib/${ANDROID_ABI}/*.so")
        list(LENGTH ${LIB_NAME}_SO_FILES ${LIB_NAME}_SO_COUNT)

        if(${${LIB_NAME}_SO_COUNT} GREATER 0)
            # Create IMPORTED targets for each library
            foreach(SO_FILE ${${LIB_NAME}_SO_FILES})
                get_filename_component(SO_NAME "${SO_FILE}" NAME_WE)
                # Remove 'lib' prefix to get the target name
                string(REGEX REPLACE "^lib" "" TARGET "${SO_NAME}")

                if(NOT TARGET ${TARGET_NAME}::${TARGET})
                    add_library(${TARGET_NAME}::${TARGET} UNKNOWN IMPORTED GLOBAL)
                    set_target_properties(${TARGET_NAME}::${TARGET}
                        PROPERTIES IMPORTED_LOCATION "${SO_FILE}"
                    )
                endif()
            endforeach()

            # Include directory
            set(INCLUDE_DIR "${${LIB_NAME}_DIR}/include")
            if(EXISTS "${INCLUDE_DIR}")
                # Set include on all targets that were just created
                foreach(SO_FILE ${${LIB_NAME}_SO_FILES})
                    get_filename_component(SO_NAME "${SO_FILE}" NAME_WE)
                    string(REGEX REPLACE "^lib" "" TARGET "${SO_NAME}")
                    if(TARGET ${TARGET_NAME}::${TARGET})
                        target_include_directories(${TARGET_NAME}::${TARGET}
                            INTERFACE "${INCLUDE_DIR}"
                        )
                    endif()
                endforeach()
            endif()

            message(STATUS "Lux Deps: ${LIB_NAME} found at ${${LIB_NAME}_DIR} (${${LIB_NAME}_SO_COUNT} .so files)")
            set(${LIB_VAR} TRUE PARENT_SCOPE)
        else()
            message(STATUS "Lux Deps: ${LIB_NAME} dir exists but no .so for ${ANDROID_ABI} at ${${LIB_NAME}_DIR}/lib/${ANDROID_ABI}/")
            set(${LIB_VAR} FALSE PARENT_SCOPE)
        endif()
    else()
        message(STATUS "Lux Deps: ${LIB_NAME} not found — stubs will be used")
        set(${LIB_VAR} FALSE PARENT_SCOPE)
    endif()
endmacro()

# ── Prebuilt / System libraries ────────────────────────────────────────────
find_library(ANDROID_LOG_LIBRARY         log           REQUIRED)
find_library(ANDROID_VULKAN_LIBRARY      vulkan)
find_library(ANDROID_NATIVE_WINDOW_LIBRARY nativewindow)
find_library(ANDROID_OPENGLES_LIBRARY    GLESv3)
find_library(ANDROID_EGL_LIBRARY         EGL)

message(STATUS "Lux Deps: System libs — log=OK vulkan=${ANDROID_VULKAN_LIBRARY} nativewindow=${ANDROID_NATIVE_WINDOW_LIBRARY} GLESv3=${ANDROID_OPENGLES_LIBRARY} EGL=${ANDROID_EGL_LIBRARY}")

# ── Filament (Google's PBR renderer) ──────────────────────────────────────
if(LUX_USE_FILAMENT)
    lux_find_native_lib("filament" FILAMENT_FOUND "filament")

    # Special case: create the canonical filament::filament alias
    if(FILAMENT_FOUND AND TARGET filament::filament)
        message(STATUS "Lux Deps: ✅ Filament ready")
    else()
        # Try creating it manually
        if(DEFINED ENV{LUX_FILAMENT_DIR})
            set(FILAMENT_DIR $ENV{LUX_FILAMENT_DIR})
        else()
            set(FILAMENT_DIR "${CMAKE_SOURCE_DIR}/third_party/filament")
        endif()

        if(EXISTS "${FILAMENT_DIR}")
            find_library(FILAMENT_LIBRARY     filament         PATHS "${FILAMENT_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            find_library(FILAMENT_GLTFIO_LIB  filament-gltfio  PATHS "${FILAMENT_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            find_library(FILAMENT_IBL_LIB     filament-ibl     PATHS "${FILAMENT_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)

            if(FILAMENT_LIBRARY OR FILAMENT_GLTFIO_LIB OR FILAMENT_IBL_LIB)
                if(FILAMENT_LIBRARY)
                    add_library(filament::filament UNKNOWN IMPORTED GLOBAL)
                    set_target_properties(filament::filament PROPERTIES IMPORTED_LOCATION "${FILAMENT_LIBRARY}")
                    target_include_directories(filament::filament INTERFACE "${FILAMENT_DIR}/include")
                    message(STATUS "Lux Deps: ✅ Filament (manual) — ${FILAMENT_LIBRARY}")
                endif()
                if(FILAMENT_GLTFIO_LIB)
                    add_library(filament::gltfio UNKNOWN IMPORTED GLOBAL)
                    set_target_properties(filament::gltfio PROPERTIES IMPORTED_LOCATION "${FILAMENT_GLTFIO_LIB}")
                    target_include_directories(filament::gltfio INTERFACE "${FILAMENT_DIR}/include")
                endif()
                if(FILAMENT_IBL_LIB)
                    add_library(filament::ibl UNKNOWN IMPORTED GLOBAL)
                    set_target_properties(filament::ibl PROPERTIES IMPORTED_LOCATION "${FILAMENT_IBL_LIB}")
                endif()
            else()
                message(WARNING "Lux Deps: ❌ Filament not found — renderer stubs will be used")
            endif()
        else()
            message(WARNING "Lux Deps: ❌ Filament not found — renderer stubs will be used")
        endif()
    endif()
endif()

# ── miniaudio (single header — no .so needed!) ───────────────────────────
if(LUX_USE_MINIAUDIO)
    if(DEFINED ENV{LUX_MINIAUDIO_DIR})
        set(MINIAUDIO_DIR $ENV{LUX_MINIAUDIO_DIR})
    else()
        set(MINIAUDIO_DIR "${CMAKE_SOURCE_DIR}/third_party/miniaudio")
    endif()

    if(EXISTS "${MINIAUDIO_DIR}/miniaudio.h")
        add_library(miniaudio INTERFACE)
        target_include_directories(miniaudio INTERFACE "${MINIAUDIO_DIR}")
        target_compile_definitions(miniaudio INTERFACE MINIAUDIO_IMPLEMENTATION)
        message(STATUS "Lux Deps: ✅ miniaudio — ${MINIAUDIO_DIR}/miniaudio.h ($(wc -c < \"${MINIAUDIO_DIR}/miniaudio.h\") bytes)")
    else()
        message(WARNING "Lux Deps: ❌ miniaudio not found — audio stubs will be used")
    endif()
endif()

# ── ozz-animation ─────────────────────────────────────────────────────────
if(LUX_USE_OZZ)
    lux_find_native_lib("ozz-animation" OZZ_FOUND "ozz")

    if(NOT OZZ_FOUND)
        # Fallback: check the old expected locations
        if(DEFINED ENV{LUX_OZZ_DIR})
            set(OZZ_DIR $ENV{LUX_OZZ_DIR})
        else()
            set(OZZ_DIR "${CMAKE_SOURCE_DIR}/third_party/ozz-animation")
        endif()

        if(EXISTS "${OZZ_DIR}")
            find_library(OZZ_ANIMATION_LIB ozz_animation PATHS "${OZZ_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            find_library(OZZ_BASE_LIB      ozz_base      PATHS "${OZZ_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            find_library(OZZ_GEOMETRY_LIB  ozz_geometry  PATHS "${OZZ_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)

            if(OZZ_ANIMATION_LIB OR OZZ_BASE_LIB OR OZZ_GEOMETRY_LIB)
                macro(_import_ozz_lib NAME VAR)
                    if(${VAR})
                        add_library(ozz::${NAME} UNKNOWN IMPORTED GLOBAL)
                        set_target_properties(ozz::${NAME} PROPERTIES IMPORTED_LOCATION "${${VAR}}")
                        target_include_directories(ozz::${NAME} INTERFACE "${OZZ_DIR}/include")
                    endif()
                endmacro()
                _import_ozz_lib(animation OZZ_ANIMATION_LIB)
                _import_ozz_lib(base      OZZ_BASE_LIB)
                _import_ozz_lib(geometry  OZZ_GEOMETRY_LIB)
                message(STATUS "Lux Deps: ✅ ozz-animation (manual)")
            else()
                message(WARNING "Lux Deps: ❌ ozz-animation not found — animation stubs")
            endif()
        else()
            message(WARNING "Lux Deps: ❌ ozz-animation not found — animation stubs")
        endif()
    endif()
endif()

# ── libsodium ──────────────────────────────────────────────────────────────
if(LUX_USE_SODIUM)
    lux_find_native_lib("libsodium" SODIUM_FOUND "sodium")

    if(NOT SODIUM_FOUND)
        # Fallback: create a single sodium::sodium target
        if(DEFINED ENV{LUX_SODIUM_DIR})
            set(SODIUM_DIR $ENV{LUX_SODIUM_DIR})
        else()
            set(SODIUM_DIR "${CMAKE_SOURCE_DIR}/third_party/libsodium")
        endif()

        if(EXISTS "${SODIUM_DIR}")
            find_library(SODIUM_LIBRARY sodium PATHS "${SODIUM_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            if(SODIUM_LIBRARY)
                add_library(sodium::sodium UNKNOWN IMPORTED GLOBAL)
                set_target_properties(sodium::sodium PROPERTIES IMPORTED_LOCATION "${SODIUM_LIBRARY}")
                target_include_directories(sodium::sodium INTERFACE "${SODIUM_DIR}/include")
                message(STATUS "Lux Deps: ✅ libsodium — ${SODIUM_LIBRARY}")
            else()
                message(WARNING "Lux Deps: ❌ libsodium not found — encryption stubs")
            endif()
        else()
            message(WARNING "Lux Deps: ❌ libsodium not found — encryption stubs")
        endif()
    endif()
endif()

# ── Nakama C++ Client ──────────────────────────────────────────────────────
if(LUX_USE_NAKAMA)
    lux_find_native_lib("nakama-cpp" NAKAMA_FOUND "nakama")

    if(NOT NAKAMA_FOUND)
        # Fallback
        if(DEFINED ENV{LUX_NAKAMA_DIR})
            set(NAKAMA_DIR $ENV{LUX_NAKAMA_DIR})
        else()
            set(NAKAMA_DIR "${CMAKE_SOURCE_DIR}/third_party/nakama-cpp")
        endif()

        if(EXISTS "${NAKAMA_DIR}")
            find_library(NAKAMA_CLIENT_LIB nakama-sdk PATHS "${NAKAMA_DIR}/lib/${ANDROID_ABI}" NO_DEFAULT_PATH)
            if(NAKAMA_CLIENT_LIB)
                add_library(nakama::client UNKNOWN IMPORTED GLOBAL)
                set_target_properties(nakama::client PROPERTIES IMPORTED_LOCATION "${NAKAMA_CLIENT_LIB}")
                target_include_directories(nakama::client INTERFACE "${NAKAMA_DIR}/include")
                message(STATUS "Lux Deps: ✅ Nakama SDK — ${NAKAMA_CLIENT_LIB}")
            else()
                message(WARNING "Lux Deps: ❌ Nakama SDK not found — networking stubs")
            endif()
        else()
            message(WARNING "Lux Deps: ❌ Nakama SDK not found — networking stubs")
        endif()
    endif()
endif()

# ── Summary ────────────────────────────────────────────────────────────────
message(STATUS "═══════ Lux Dependencies Summary ═══════")
foreach(LIB "filament" "miniaudio" "ozz-animation" "libsodium" "nakama-cpp")
    get_property(defined TARGET ${LIB}::${LIB} DEFINED)
    if(TARGET ${LIB}::${LIB})
        message(STATUS "  ✅ ${LIB}")
    else()
        message(STATUS "  ⬜ ${LIB} — stub (lib not available)")
    endif()
endforeach()
message(STATUS "═════════════════════════════════════════")
