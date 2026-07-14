# Mobile sculpting camera research

## Findings

### Nomad Sculpt

Nomad's official manual describes the clearest touch-first pattern:

- one-finger drag on empty background rotates the camera;
- two-finger movement pans;
- pinch zooms;
- two-finger rotation can roll in trackball mode;
- double tap focuses a picked point or selected mesh;
- camera rotation has Turntable and Trackball modes;
- a visible pivot dot explains the orbit center;
- rotation, translation, and zoom speed are configurable.

Sources:

- https://nomadsculpt.com/manual/camera
- https://nomadsculpt.com/manual/gettingstarted

### ArmorPaint

ArmorPaint separates painting from camera navigation and exposes camera movement speed. Its desktop controls map rotate, pan, and zoom to distinct inputs, while its mobile builds use a touch UI. This reinforces keeping camera intent separate from paint intent and keeping speed configurable.

Source:

- https://armorpaint.org/manual

### Sculpt+ and d3D Sculptor

Public technical documentation for their exact gesture recognizers is limited. Available product information confirms touch-oriented sculpting/navigation. d3D release information also mentions adjustable Zoom, Rotate, and Panning sensitivity and automatic focus on double tap. These are useful product patterns, but they do not expose implementation-level source code.

Reference:

- https://inspirationtuts.com/3d-modeling-apps-for-android/

## Filament comparison

Filament's `ModelViewer.render()` always applies the pose from its `Manipulator` immediately before drawing. Our earlier build passed a separate C++ pose to `viewer.camera.lookAt()`, but `render()` then replaced it, making every gesture appear static. The stable viewer milestone therefore uses ModelViewer's Filament-native `Manipulator` as the single camera owner. Our separate C++ controller remains in the project for a later advanced camera implementation, but it is deliberately not connected while the basic viewer is being validated.

The earlier implementation mixed Android gesture detectors and had a pivot mismatch: Filament normalized the model to a default point around z = -4 while C++ orbited the world origin. The revised implementation:

1. uses ModelViewer's expected normalized placement at `(0, 0, -4)`;
2. captures touches in a transparent ordinary Android `View` above the rendering surface;
3. forwards the original, unmodified multi-pointer event stream to `ModelViewer.onTouchEvent()`;
4. keeps only one active camera owner—the Filament-native `Manipulator`;
5. calls `ModelViewer.render()` without applying a competing camera pose.

## Current interaction map

- One finger drag: turntable orbit around model/panned pivot
- Two finger drag: screen-space pan
- Pinch: dolly zoom
- Double tap: reset view

When painting is introduced, one-finger gestures over the mesh will become paint strokes; navigation can remain available on empty background and through two-finger gestures, following Nomad's interaction model.
