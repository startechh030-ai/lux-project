# Phase 5 — Top and Left Editor UI

Approved visual: `phase5_top_left_ui_study.png`

Implemented in version 0.36.0 (versionCode 74).

## Included

- Responsive 1600 × 720 reference-pixel scaling.
- Flush top navigation with Luxe monochrome mark.
- Tabs: File, Edit, Sculpt, Texturing, Animation, Node Graph, UVs, Full Rendering, More.
- Edit Preview and Matcap work-render indicators.
- Contextual toolbar that changes visually per workspace.
- Eight-position left tool rail:
  1. Select
  2. Bevel
  3. Selection Mode
  4. Transform
  5. Extrude
  6. Inset
  7. Cut
  8. More Tools
- File tab opens the existing Resource Browser.
- Long-pressing File invokes the existing project save action.
- Editing and workspace controls are explicitly marked as Phase 5 placeholders.
- Existing Phase 4 test controls are retained behind the small DEV toggle.

## Guardrail

No MeshLibs geometry operation is implemented or connected in this version. The viewport, scene manager, selection, transforms, session, and import backend were not rewritten.

## Compact refinement — 0.36.1

- Edit mode is now named WIP mode.
- Original Canvas vector icons replace provisional text symbols.
- Top tabs and preview selector use custom Luxe dropdown panels.
- Unimplemented dropdown entries remain visibly muted placeholder data.

## Approved context-aware redesign — 0.37.0

The 0.36.x header structure was discarded. The replacement uses two context-aware header rows, one vertically scrollable left tool column, viewport operation feedback, and a thin status bar. No right dock is created before MeshLibs controls are defined.
