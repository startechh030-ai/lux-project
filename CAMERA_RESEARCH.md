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

Filament's `ModelViewer` can create its own `Manipulator`, but Luxe Texture3D intentionally passes `manipulator = null`. This prevents two camera systems from fighting each other. Filament remains responsible for projection and rendering; the native C++ controller owns orbit target, yaw, pitch, distance, pan, clamping, and damping.

The earlier implementation mixed Android gesture detectors and had a pivot mismatch: Filament normalized the model to a default point around z = -4 while C++ orbited the world origin. The revised implementation:

1. normalizes the model to `(0, 0, 0)`;
2. uses a dedicated `CameraSurfaceView` that directly owns touch events;
3. tracks pointer count, centroid, and two-pointer span itself;
4. sends one-finger deltas to native orbit;
5. sends simultaneous two-finger centroid and span changes to native pan and zoom;
6. discards deltas whenever pointer indices change;
7. pans in the camera's screen plane rather than fixed world X/Y;
8. clamps pitch and distance;
9. applies frame-rate-independent exponential smoothing each rendered frame;
10. resets on double tap.

## Current interaction map

- One finger drag: turntable orbit around model/panned pivot
- Two finger drag: screen-space pan
- Pinch: dolly zoom
- Double tap: reset view

When painting is introduced, one-finger gestures over the mesh will become paint strokes; navigation can remain available on empty background and through two-finger gestures, following Nomad's interaction model.
