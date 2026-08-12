# Luxe Texture3D — Editor UI and MeshLibs Plan

Status: Working specification; awaiting final UI mockups and confirmation before implementation.

## Product direction

Luxe Texture3D is a simplified, touch-first mobile 3D creation application rather than a texture-only editor. Planned workflows include geometry editing, sculpting, texturing, vertex painting, UV editing, animation, scene setup, lighting, and rendering.

## Current phase boundary

### Phase 5 — Editor UI and file-system presentation

Phase 5 creates the new responsive editor interface and adapts the way Luxe files/resources are presented and accessed from that interface.

Phase 5 includes the responsive UI plus approved file-system and Resource Browser access. Editing-engine controls remain mocked. Existing Filament rendering, selection, transforms, project sessions, imports, and other Phase 4 systems must not be accidentally rewritten while the new shell is being built.

### Phase 6 — MeshLibs engine

Phase 6 implements and connects the first new editing engine, currently named **MeshLibs**. Its intended scope includes both:

- Direct topology editing: vertex, edge, and face selection and geometry operations.
- Higher-level mesh operations/modifiers: subdivide, remesh, decimate, smooth, mirror, hole fill, and related tools.

MeshLibs-related buttons and controls may appear as mock UI during Phase 5, but their real behavior belongs to Phase 6 unless explicitly re-scoped.

## Two UI modes

Luxe will have two coordinated editor interfaces:

1. **Simple UI** — streamlined, button-oriented, touch-first controls.
2. **Full UI** — dense but organized professional controls, comparable to a compact mobile DCC interface without directly copying Blender or another product.

The supplied Luxe mockup is the structural skeleton for the **Full UI**. The Simple UI will be specified by a separate mockup. Other tool screenshots are references for organization, density, hierarchy, brush presentation, viewport controls, panel treatment, and bottom-bar design—not templates to copy literally.

## Confirmed primary tabs

The primary editor tabs are:

1. File
2. Edit
3. Sculpt
4. T/V — Texturing and Vertex Paint
5. UVs
6. Animation
7. Render

Spelling and capitalization should remain consistent throughout the UI. `T/V` is the compact visible label for Texturing / Vertex Paint.

## Editor shell currently inferred from the references

- A dark professional workspace optimized for landscape touch devices.
- A large, unobstructed central 3D viewport.
- A global tab/menu row.
- A mode selector.
- A narrow icon tool rail on the **left**.
- A contextual sub-feature/action row located directly below the primary tab row.
- A contextual properties/hierarchy panel on the **right** for properties, options, hierarchy, materials, or MeshLibs operations.
- A bottom workspace/context bar inspired by the useful organization of Blender's lower tabs, redesigned for Luxe and mobile touch.
- Compact viewport navigation and orientation controls.
- Context changes by active primary tab, selected tool, selected object, and Simple/Full UI mode.

## MeshLibs UI relationship

The icon tool rail and contextual sub-feature row are intended to expose MeshLibs editing operations when MeshLibs is implemented. The exact ownership of every button must be documented before wiring so UI controls do not call ambiguous or incorrect engine actions.

## Visual principles

- Dense, flat, neutral-charcoal workstation styling.
- Muted blue active states.
- Sharp or low-radius corners.
- Clear selected, disabled, pressed, and unavailable states.
- Minimum practical touch targets even when the interface looks compact.
- Consistent scaling based on available window size, not device DPI alone.
- Phone/tablet responsiveness, safe insets, display cutouts, and immersive landscape support.
- Central viewport space takes priority over permanently expanded panels.
- Tool groups should be visually ordered like professional brush/tool systems while remaining understandable on mobile.
- Do not reproduce Blender, Photoshop, or another application's proprietary visual identity.

## Implementation guardrails

- Do not infer engine functionality merely from a reference icon.
- Do not claim mock controls work.
- Do not connect MeshLibs during Phase 5 unless the phase boundary is explicitly changed.
- Preserve existing temporary Phase 4 functionality until replacement controls are intentionally wired.
- Use Luxe-native dialogs, menus, panels, tooltips, and sheets.
- Confirm the exact location and behavior of the tool rail and contextual side panel before implementation.
- Confirm whether Simple UI and Full UI share one adaptive component tree or use separately composed layouts.

## Confirmed decisions

1. The icon tool rail is on the left.
2. The contextual properties/hierarchy panel is on the right.
3. The supplied Luxe skeleton represents the Full UI.
4. MeshLibs owns direct topology editing plus higher-level mesh operations/modifiers.
5. Phase 5 includes the UI and approved file-system/Resource Browser wiring; editing engines remain disconnected.

## Remaining confirmations before coding

1. What belongs in the top contextual row versus the bottom contextual/workspace bar?
2. How does the user switch between Simple UI and Full UI?
3. Which exact File/resource operations are wired during Phase 5?
4. The separate Simple UI mockup and its responsive behavior.
5. The exact MeshLibs operation list and API/data-ownership contract before Phase 6.

## Proposed safe delivery order

1. Approve this written information architecture.
2. Receive and annotate the final Simple UI and Full UI mockups.
3. Define responsive breakpoints and panel collapse rules.
4. Implement a shared Luxe editor design system.
5. Implement the editor shell with mock state.
6. Implement each tab's mock panels and controls.
7. If Phase 5 includes file-system presentation, wire only the explicitly approved file/resource actions.
8. Preserve Phase 4 engine controls in a temporary developer/testing overlay.
9. Begin Phase 6 with a written MeshLibs API and data-ownership contract.
10. Connect MeshLibs controls one verified operation at a time.
