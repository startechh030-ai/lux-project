# =============================================================================
# Mini-Games — each one self-registers into the MiniGameRegistry.
# They can be compiled into the lux shared library or loaded dynamically.
# =============================================================================

# For now, we compile game_1_racing directly into lux_shared.
# Dynamic loading (dlopen) is a future optimization.
add_subdirectory(game_1_racing)
