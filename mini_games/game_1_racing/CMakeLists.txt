# =============================================================================
# Racing Mini-Game "Speed Rush"
# =============================================================================

target_sources(lux_shared PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/racing_game.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/racing_game.h
)

target_include_directories(lux_shared PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
